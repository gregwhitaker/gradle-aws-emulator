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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorCredentials;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorTls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.AppSyncException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.NamespaceNotFoundException;
import software.amazon.awssdk.services.servicediscovery.model.ServiceNotFoundException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

/** Checks service state behind newly audited catalogue and dedicated dispatch families in 1.0.471. */
class CloudFormationExpandedTypesIntegrationTest {
    @TempDir Path project;

    @Test void eventBusPoliciesAndMaintenanceTargetsReachTheirOwningServices() throws Exception {
        fixture("""
            Parameters:
              Label: {Type: String}
            Resources:
              Bus:
                Type: AWS::Events::EventBus
                DeletionPolicy: Retain
                Properties: {Name: audit-events, Description: !Ref Label}
              Policy:
                Type: AWS::Events::EventBusPolicy
                Properties:
                  EventBusName: !Ref Bus
                  StatementId: audit-permission
                  Action: events:PutEvents
                  Principal: '123456789012'
              Window:
                Type: AWS::SSM::MaintenanceWindow
                DeletionPolicy: Retain
                Properties:
                  Name: audit-window
                  AllowUnassociatedTargets: true
                  Schedule: rate(1 day)
                  Duration: 2
                  Cutoff: 1
              Target:
                Type: AWS::SSM::MaintenanceWindowTarget
                Properties:
                  WindowId: !Ref Window
                  ResourceType: INSTANCE
                  Targets: [{Key: 'tag:environment', Values: [audit]}]
            Outputs:
              WindowId: {Value: !Ref Window}
              TargetId: {Value: !Ref Target}
            """);
        try {
            runner(project, "deploy").build();
            String window = outputs().path("WindowId").asText();
            String target = outputs().path("TargetId").asText();
            var factory = new AwsClientFactory(connection(), 30);
            try (var events = factory.eventBridge(); var ssm = factory.ssm()) {
                var bus = events.describeEventBus(r -> r.name("audit-events"));
                assertEquals("initial", bus.description());
                var policy = new ObjectMapper().readTree(bus.policy()).path("Statement").get(0);
                assertEquals("audit-permission", policy.path("Sid").asText());
                assertEquals("audit-window", ssm.getMaintenanceWindow(r -> r.windowId(window)).name());
                assertEquals(2, ssm.getMaintenanceWindow(r -> r.windowId(window)).duration());
                assertEquals(target, ssm.describeMaintenanceWindowTargets(r -> r.windowId(window))
                    .targets().get(0).windowTargetId());
                assertTrue(runner(project, "deploy").build().getOutput().contains("Reusing configuration cache"));
                runner(project, "deploy", "-Plabel=updated").build();
                assertEquals("updated", events.describeEventBus(r -> r.name("audit-events")).description());
                runner(project, "deleteCloudFormationStack", "--stackName=expanded-types-audit").build();
                // Retained parents prove that deleting the stack revoked the children themselves.
                String remaining = events.describeEventBus(r -> r.name("audit-events")).policy();
                assertTrue(remaining == null || remaining.isBlank()
                    || new ObjectMapper().readTree(remaining).path("Statement").isEmpty(), remaining);
                assertTrue(ssm.describeMaintenanceWindowTargets(r -> r.windowId(window)).targets().isEmpty());
                events.deleteEventBus(r -> r.name("audit-events"));
                ssm.deleteMaintenanceWindow(r -> r.windowId(window));
                assertThrows(ResourceNotFoundException.class, () -> events.describeEventBus(r -> r.name("audit-events")));
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
    }

    @Test void cloudMapNamespacesAndSecurityGroupRulesCreateUpdateAndDeleteRealState() throws Exception {
        fixture("""
            Parameters:
              Label: {Type: String}
            Resources:
              Vpc:
                Type: AWS::EC2::VPC
                Properties: {CidrBlock: 10.42.0.0/16, EnableDnsSupport: true, EnableDnsHostnames: true}
              Group:
                Type: AWS::EC2::SecurityGroup
                Properties: {GroupDescription: audit-group, VpcId: !Ref Vpc}
              Ingress:
                Type: AWS::EC2::SecurityGroupIngress
                Properties: {GroupId: !GetAtt Group.GroupId, IpProtocol: tcp, FromPort: 8080, ToPort: 8080, CidrIp: 10.42.0.0/16}
              Egress:
                Type: AWS::EC2::SecurityGroupEgress
                Properties: {GroupId: !GetAtt Group.GroupId, IpProtocol: tcp, FromPort: 443, ToPort: 443, CidrIp: 10.42.0.0/16}
              Http:
                Type: AWS::ServiceDiscovery::HttpNamespace
                Properties: {Name: audit-http, Description: !Ref Label}
              Public:
                Type: AWS::ServiceDiscovery::PublicDnsNamespace
                Properties: {Name: audit-public.test}
              Private:
                Type: AWS::ServiceDiscovery::PrivateDnsNamespace
                Properties: {Name: audit-private.test, Vpc: !Ref Vpc}
              Service:
                Type: AWS::ServiceDiscovery::Service
                Properties: {Name: audit-service, NamespaceId: !Ref Http, Type: HTTP, Description: audit-service}
              Instance:
                Type: AWS::ServiceDiscovery::Instance
                Properties:
                  ServiceId: !Ref Service
                  InstanceId: audit-instance
                  InstanceAttributes: {AWS_INSTANCE_IPV4: 10.42.0.10, version: !Ref Label}
            Outputs:
              GroupId: {Value: !GetAtt Group.GroupId}
              HttpId: {Value: !Ref Http}
              PublicId: {Value: !Ref Public}
              PrivateId: {Value: !Ref Private}
              ServiceId: {Value: !Ref Service}
            """);
        try {
            runner(project, "deploy").build();
            JsonNode outputs = outputs();
            String group = outputs.path("GroupId").asText();
            String service = outputs.path("ServiceId").asText();
            try (var ec2 = client(Ec2Client.builder()); var discovery = client(ServiceDiscoveryClient.builder())) {
                var rules = ec2.describeSecurityGroupRules(r -> r.filters(f -> f.name("group-id").values(group)))
                    .securityGroupRules();
                assertTrue(rules.stream().anyMatch(r -> !r.isEgress() && Integer.valueOf(8080).equals(r.fromPort())
                    && "10.42.0.0/16".equals(r.cidrIpv4())));
                assertTrue(rules.stream().anyMatch(r -> r.isEgress() && Integer.valueOf(443).equals(r.fromPort())
                    && "10.42.0.0/16".equals(r.cidrIpv4())));
                assertEquals("HTTP", discovery.getNamespace(r -> r.id(outputs.path("HttpId").asText())).namespace().typeAsString());
                assertEquals("DNS_PUBLIC", discovery.getNamespace(r -> r.id(outputs.path("PublicId").asText())).namespace().typeAsString());
                assertEquals("DNS_PRIVATE", discovery.getNamespace(r -> r.id(outputs.path("PrivateId").asText())).namespace().typeAsString());
                assertEquals(outputs.path("HttpId").asText(), discovery.getService(r -> r.id(service)).service().namespaceId());
                assertEquals("initial", discovery.getInstance(r -> r.serviceId(service).instanceId("audit-instance"))
                    .instance().attributes().get("version"));
                assertEquals("audit-instance", discovery.discoverInstances(r -> r.namespaceName("audit-http")
                    .serviceName("audit-service")).instances().get(0).instanceId());
                assertTrue(runner(project, "deploy").build().getOutput().contains("Reusing configuration cache"));
                runner(project, "deploy", "-Plabel=updated").build();
                assertEquals("updated", discovery.getNamespace(r -> r.id(outputs.path("HttpId").asText())).namespace().description());
                assertEquals("updated", discovery.getInstance(r -> r.serviceId(service).instanceId("audit-instance"))
                    .instance().attributes().get("version"));
                runner(project, "deleteCloudFormationStack", "--stackName=expanded-types-audit").build();
                assertThrows(ServiceNotFoundException.class, () -> discovery.getService(r -> r.id(service)));
                for (String name : new String[] {"HttpId", "PublicId", "PrivateId"}) {
                    assertThrows(NamespaceNotFoundException.class, () -> discovery.getNamespace(r -> r.id(outputs.path(name).asText())));
                }
                assertEquals("InvalidGroup.NotFound", assertThrows(Ec2Exception.class,
                    () -> ec2.describeSecurityGroups(r -> r.groupIds(group))).awsErrorDetails().errorCode());
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
    }

    @Test void appSyncEventApiSupportsCreateDeleteButReportsDependentRenameFailures() throws Exception {
        fixture("""
            Parameters:
              Label: {Type: String}
            Resources:
              Api:
                Type: AWS::AppSync::Api
                Properties:
                  Name: !Ref Label
                  EventConfig:
                    AuthProviders: [{AuthType: AWS_IAM}]
                    ConnectionAuthModes: [{AuthType: AWS_IAM}]
                    DefaultPublishAuthModes: [{AuthType: AWS_IAM}]
                    DefaultSubscribeAuthModes: [{AuthType: AWS_IAM}]
              Namespace:
                Type: AWS::AppSync::ChannelNamespace
                Properties: {ApiId: !GetAtt Api.ApiId, Name: audit}
            Outputs:
              ApiId: {Value: !GetAtt Api.ApiId}
              NamespaceArn: {Value: !Ref Namespace}
            """);
        try {
            runner(project, "deploy").build();
            String api = outputs().path("ApiId").asText();
            try (var appsync = client(AppSyncClient.builder())) {
                assertEquals("initial", appsync.getApi(r -> r.apiId(api)).api().name());
                assertEquals(outputs().path("NamespaceArn").asText(), appsync.getChannelNamespace(r -> r.apiId(api).name("audit"))
                    .channelNamespace().channelNamespaceArn());
                assertTrue(runner(project, "deploy").build().getOutput().contains("Reusing configuration cache"));
                runner(project, "deleteCloudFormationStack", "--stackName=expanded-types-audit").build();
                assertEquals("NotFoundException", assertThrows(AppSyncException.class,
                    () -> appsync.getApi(r -> r.apiId(api))).awsErrorDetails().errorCode());
            }
            runner(project, "deploy").build();
            String saved = Files.readString(project.resolve("build/outputs.json"));
            var failure = runner(project, "deploy", "-Plabel=updated").buildAndFail();
            assertTrue(failure.getOutput().contains("failed to update: [Namespace]"), failure.getOutput());
            assertTrue(failure.getOutput().contains("a rolled-back or failed deployment is not success"), failure.getOutput());
            assertEquals(saved, Files.readString(project.resolve("build/outputs.json")));
            try (var stacks = new AwsClientFactory(connection(), 30).cloudFormation()) {
                assertTrue(stacks.describeStackEvents(r -> r.stackName("expanded-types-audit")).stackEvents().stream()
                    .anyMatch(e -> "Namespace".equals(e.logicalResourceId()) && "UPDATE_FAILED".equals(e.resourceStatusAsString())));
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
    }

    private JsonNode outputs() throws Exception {
        return new ObjectMapper().readTree(project.resolve("build/outputs.json").toFile());
    }

    private EmulatorConnection connection() throws Exception {
        try (var directories = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), "us-east-1", directory);
        }
    }

    private <B extends AwsSyncClientBuilder<B, C> & AwsClientBuilder<B, C>, C extends SdkClient> C client(B builder) throws Exception {
        var connection = connection();
        var trust = EmulatorTls.trustManagers(connection.trustStore());
        return builder.endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(StaticCredentialsProvider.create(EmulatorCredentials.read(connection.credentials())))
            .httpClientBuilder(UrlConnectionHttpClient.builder().tlsTrustManagersProvider(() -> trust)
                .connectionTimeout(Duration.ofSeconds(30)).socketTimeout(Duration.ofSeconds(30))
                .proxyConfiguration(proxy -> proxy.useSystemPropertyValues(false).useEnvironmentVariablesValues(false)))
            .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(30))
                .putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true)).build();
    }

    private void fixture(String template) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"expanded-types-audit\"\n");
        Files.writeString(project.resolve("stack.yaml"), template);
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.DeployCloudFormationStackTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            tasks.register<DeployCloudFormationStackTask>("deploy") {
                stackName.set("expanded-types-audit")
                templateFile.set(layout.projectDirectory.file("stack.yaml"))
                mode.set(EmulatorMode.DEVELOPMENT)
                parameters.put("Label", providers.gradleProperty("label").orElse("initial"))
                outputsFile.set(layout.buildDirectory.file("outputs.json"))
            }
            """);
    }
}
