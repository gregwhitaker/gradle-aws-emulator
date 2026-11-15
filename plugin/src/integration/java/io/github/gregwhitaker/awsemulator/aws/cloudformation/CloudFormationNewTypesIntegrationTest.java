/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gregwhitaker.awsemulator.aws.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorCredentials;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorTls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.AppSyncException;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.xray.XRayClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the nine newly audited 1.0.385 types through the services that own their resources. */
class CloudFormationNewTypesIntegrationTest {
    @TempDir Path project;

    @Test void newNativeTypesCreateUsableResourcesAndDeleteThemAfterAnUpdate() throws Exception {
        fixture();
        try {
            runner(project, "deploy").build();
            var outputs = new ObjectMapper().readTree(project.resolve("build/outputs.json").toFile());
            String api = outputs.path("ApiId").asText();
            String function = outputs.path("FunctionId").asText();
            String group = outputs.path("GroupArn").asText();
            String ca = outputs.path("AuthorityArn").asText();
            try (var appsync = client(AppSyncClient.builder()); var acmpca = client(AcmPcaClient.builder());
                 var redshift = client(RedshiftClient.builder()); var xray = client(XRayClient.builder())) {
                assertEquals("audit-initial", appsync.getGraphqlApi(r -> r.apiId(api)).graphqlApi().name());
                assertEquals("SUCCESS", appsync.getSchemaCreationStatus(r -> r.apiId(api)).statusAsString());
                assertEquals("NONE", appsync.getDataSource(r -> r.apiId(api).name("local")).dataSource().typeAsString());
                assertEquals("identity", appsync.getFunction(r -> r.apiId(api).functionId(function)).functionConfiguration().name());
                assertEquals(function, appsync.getResolver(r -> r.apiId(api).typeName("Query").fieldName("hello"))
                    .resolver().pipelineConfig().functions().get(0));
                assertEquals(1, appsync.listApiKeys(r -> r.apiId(api)).apiKeys().size());
                assertEquals("audit-traces", xray.getGroup(r -> r.groupARN(group)).group().groupName());
                var grant = redshift.describeSnapshotCopyGrants(r -> r.snapshotCopyGrantName("audit-grant"))
                    .snapshotCopyGrants().get(0);
                assertTrue(grant.kmsKeyId().endsWith(outputs.path("KeyId").asText()), grant.kmsKeyId());
                var permission = acmpca.listPermissions(r -> r.certificateAuthorityArn(ca)).permissions().get(0);
                assertEquals("acm.amazonaws.com", permission.principal());
                assertTrue(permission.actionsAsStrings().contains("IssueCertificate"));

                assertTrue(runner(project, "deploy").build().getOutput().contains("Reusing configuration cache"));
                runner(project, "deploy", "-Pschema=schema { query: Query } type Query { hello: String, goodbye: String }").build();
                assertEquals("SUCCESS", appsync.getSchemaCreationStatus(r -> r.apiId(api)).statusAsString());
                assertTrue(appsync.getIntrospectionSchema(r -> r.apiId(api).format("SDL"))
                    .schema().asUtf8String().contains("goodbye"));
                runner(project, "deleteCloudFormationStack", "--stackName=new-types-audit").build();
                assertEquals("NotFoundException", assertThrows(AppSyncException.class,
                    () -> appsync.getGraphqlApi(r -> r.apiId(api))).awsErrorDetails().errorCode());
                assertTrue(xray.getGroups().groups().stream().noneMatch(value -> group.equals(value.groupARN())));
                assertEquals("SnapshotCopyGrantNotFoundFault", assertThrows(RedshiftException.class,
                    () -> redshift.describeSnapshotCopyGrants(r -> r.snapshotCopyGrantName("audit-grant")))
                    .awsErrorDetails().errorCode());
                // The authority is retained so an empty listing proves the permission itself was revoked.
                assertTrue(acmpca.listPermissions(r -> r.certificateAuthorityArn(ca)).permissions().isEmpty());
            }
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
    }

    @Test void apiRenameWithDependentResourcesFailsInsteadOfReportingSuccess() throws Exception {
        fixture();
        try {
            runner(project, "deploy").build();
            String outputs = Files.readString(project.resolve("build/outputs.json"));
            var failure = runner(project, "deploy", "-Plabel=audit-renamed").buildAndFail();
            assertTrue(failure.getOutput().contains("failed to update"), failure.getOutput());
            assertTrue(failure.getOutput().contains("a rolled-back or failed deployment is not success"), failure.getOutput());
            assertEquals(outputs, Files.readString(project.resolve("build/outputs.json")));
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
    }

    private <B extends AwsSyncClientBuilder<B, C> & AwsClientBuilder<B, C>, C extends SdkClient> C client(B builder) throws Exception {
        EmulatorConnection connection;
        try (var directories = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
            connection = new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), "us-east-1", directory);
        }
        var trust = EmulatorTls.trustManagers(connection.trustStore());
        return builder.endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(StaticCredentialsProvider.create(EmulatorCredentials.read(connection.credentials())))
            .httpClientBuilder(UrlConnectionHttpClient.builder().tlsTrustManagersProvider(() -> trust)
                .connectionTimeout(Duration.ofSeconds(30)).socketTimeout(Duration.ofSeconds(30))
                .proxyConfiguration(proxy -> proxy.useSystemPropertyValues(false).useEnvironmentVariablesValues(false)))
            .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(30))).build();
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"new-types-audit\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.DeployCloudFormationStackTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            tasks.register<DeployCloudFormationStackTask>("deploy") {
                stackName.set("new-types-audit")
                templateFile.set(layout.projectDirectory.file("stack.yaml"))
                mode.set(EmulatorMode.DEVELOPMENT)
                parameters.put("Label", providers.gradleProperty("label").orElse("audit-initial"))
                parameters.put("Definition", providers.gradleProperty("schema")
                    .orElse("schema { query: Query } type Query { hello: String }"))
                outputsFile.set(layout.buildDirectory.file("outputs.json"))
            }
            """);
        Files.writeString(project.resolve("stack.yaml"), """
            Parameters:
              Label: {Type: String}
              Definition: {Type: String}
            Resources:
              Key:
                Type: AWS::KMS::Key
                DeletionPolicy: Retain
                Properties: {Description: Audit snapshot copy grant}
              Grant:
                Type: AWS::Redshift::SnapshotCopyGrant
                Properties: {SnapshotCopyGrantName: audit-grant, KmsKeyId: !Ref Key}
              Authority:
                Type: AWS::ACMPCA::CertificateAuthority
                DeletionPolicy: Retain
                Properties:
                  Type: ROOT
                  KeyAlgorithm: RSA_2048
                  SigningAlgorithm: SHA256WITHRSA
                  Subject: {CommonName: audit.local}
              Permission:
                Type: AWS::ACMPCA::Permission
                Properties:
                  CertificateAuthorityArn: !Ref Authority
                  Principal: acm.amazonaws.com
                  Actions: [IssueCertificate, GetCertificate, ListPermissions]
              Group:
                Type: AWS::XRay::Group
                Properties: {GroupName: audit-traces, FilterExpression: 'service("audit")'}
              Api:
                Type: AWS::AppSync::GraphQLApi
                Properties: {Name: !Ref Label, AuthenticationType: API_KEY}
              Schema:
                Type: AWS::AppSync::GraphQLSchema
                Properties:
                  ApiId: !GetAtt Api.ApiId
                  Definition: !Ref Definition
              DataSource:
                Type: AWS::AppSync::DataSource
                Properties: {ApiId: !GetAtt Api.ApiId, Name: local, Type: NONE}
              Function:
                Type: AWS::AppSync::FunctionConfiguration
                Properties:
                  ApiId: !GetAtt Api.ApiId
                  Name: identity
                  DataSourceName: !GetAtt DataSource.Name
                  FunctionVersion: '2018-05-29'
                  RequestMappingTemplate: '{"version":"2018-05-29","payload":{}}'
                  ResponseMappingTemplate: '$util.toJson($ctx.result)'
              Resolver:
                Type: AWS::AppSync::Resolver
                DependsOn: Schema
                Properties:
                  ApiId: !GetAtt Api.ApiId
                  TypeName: Query
                  FieldName: hello
                  Kind: PIPELINE
                  PipelineConfig: {Functions: [!GetAtt Function.FunctionId]}
                  RequestMappingTemplate: '{}'
                  ResponseMappingTemplate: '$util.toJson($ctx.prev.result)'
              ApiKey:
                Type: AWS::AppSync::ApiKey
                Properties: {ApiId: !GetAtt Api.ApiId, Description: audited-key}
            Outputs:
              ApiId: {Value: !GetAtt Api.ApiId}
              FunctionId: {Value: !GetAtt Function.FunctionId}
              GroupArn: {Value: !GetAtt Group.GroupARN}
              AuthorityArn: {Value: !Ref Authority}
              KeyId: {Value: !Ref Key}
            """);
    }
}
