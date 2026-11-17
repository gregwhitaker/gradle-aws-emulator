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

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CloudFormationTemplateTest {
    @TempDir Path directory;

    @Test void acceptsJsonAndYamlIntrinsicsWithoutMistakingPropertyTextForResources() {
        assertDoesNotThrow(() -> CloudFormationTemplate.validate("""
            AWSTemplateFormatVersion: '2010-09-09'
            Parameters:
              QueueName: {Type: String}
            Resources:
              Queue:
                Type: AWS::SQS::Queue
                Properties:
                  QueueName: !Ref QueueName
                  Tags: [{Key: purpose, Value: !Sub '${AWS::StackName}-test'}]
              Policy:
                Type: AWS::SQS::QueuePolicy
                Properties:
                  Queues: [!Ref Queue]
                  PolicyDocument: {Version: '2012-10-17', Statement: []}
              Report: {Type: AWS::CDK::Metadata}
            Outputs:
              QueueArn: {Value: !GetAtt Queue.Arn}
            """));
        assertDoesNotThrow(() -> CloudFormationTemplate.validate("""
            {"Resources":{"Bucket":{"Type":"AWS::S3::Bucket","Metadata":{
              "example":"AWS::CloudWatch::Alarm","Type":"AWS::Cognito::UserPool"
            }}}}
            """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWS::DynamoDB::Table", "AWS::EC2::Instance", "AWS::ApiGateway::UsagePlan",
        "AWS::Config::ConfigurationRecorder", "AWS::Lambda::EventSourceMapping", "AWS::IAM::GroupPolicy",
        "AWS::CloudFormation::WaitCondition", "AWS::CloudWatch::Alarm", "AWS::Cognito::UserPool",
        "AWS::Cognito::UserPoolClient", "AWS::Cognito::UserPoolResourceServer", "AWS::ECS::Service",
        "AWS::ElasticLoadBalancingV2::LoadBalancer", "AWS::ApiGateway::Model", "AWS::SSM::Document",
        "AWS::ACMPCA::Permission", "AWS::Redshift::SnapshotCopyGrant", "AWS::XRay::Group",
        "AWS::AppSync::GraphQLApi", "AWS::AppSync::GraphQLSchema", "AWS::AppSync::DataSource",
        "AWS::AppSync::Resolver", "AWS::AppSync::FunctionConfiguration", "AWS::AppSync::ApiKey"})
    void acceptsMainAndDedicatedProvisioners(String type) {
        assertDoesNotThrow(() -> CloudFormationTemplate.validate(template(type)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWS::Amplify::Branch", "AWS::AppSync::Api",
        "AWS::AppSync::ChannelNamespace", "AWS::AppSync::DomainName",
        "AWS::AppSync::DomainNameApiAssociation", "AWS::AppSync::SourceApiAssociation",
        "AWS::Bedrock::Flow", "AWS::CustomerProfiles::Domain",
        "AWS::EC2::SecurityGroupEgress", "AWS::EC2::SecurityGroupIngress",
        "AWS::ECS::CapacityProvider", "AWS::EKS::Addon",
        "AWS::EKS::Nodegroup", "AWS::Events::EventBus",
        "AWS::Events::EventBusPolicy", "AWS::Glue::TableOptimizer",
        "AWS::MemoryDB::ParameterGroup", "AWS::MemoryDB::SubnetGroup",
        "AWS::NeptuneGraph::Graph", "AWS::NeptuneGraph::GraphSnapshot",
        "AWS::Rbin::Rule", "AWS::SSM::MaintenanceWindow",
        "AWS::SSM::MaintenanceWindowTarget", "AWS::SageMaker::Endpoint",
        "AWS::SageMaker::EndpointConfig", "AWS::ServiceDiscovery::HttpNamespace",
        "AWS::ServiceDiscovery::Instance", "AWS::ServiceDiscovery::PrivateDnsNamespace",
        "AWS::ServiceDiscovery::PublicDnsNamespace", "AWS::ServiceDiscovery::Service"})
    void acceptsTypesAddedInTheCurrentAudit(String type) {
        assertDoesNotThrow(() -> CloudFormationTemplate.validate(template(type)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWS::CloudWatch::CompositeAlarm", "AWS::Cognito::UserPoolDomain",
        "AWS::DynamoDB::GlobalTable", "AWS::SES::EmailIdentity", "AWS::Lambda::Url",
        "AWS::FutureService::Resource", "aws::s3::bucket",
        "AWS::Serverless::Application", "AWS::CloudFormation::WaitConditionHandle"})
    void rejectsKnownAcknowledgementsAndUnknownTypes(String type) {
        var error = assertThrows(GradleException.class, () -> CloudFormationTemplate.validate(template(type)));
        assertTrue(error.getMessage().contains("Resources.Resource (" + type + ")"), error.getMessage());
        assertTrue(error.getMessage().contains("No stack was submitted"));
    }

    @Test void reportsAllUnsupportedDeclarationsEvenBehindFalseConditions() {
        var error = assertThrows(GradleException.class, () -> CloudFormationTemplate.validate("""
            Conditions: {Never: {Fn::Equals: [one, two]}}
            Resources:
              Alarm: {Type: AWS::CloudWatch::CompositeAlarm, Condition: Never}
              Pool: {Type: AWS::Cognito::UserPoolDomain}
              Bucket: {Type: AWS::S3::Bucket}
            """));
        assertTrue(error.getMessage().contains("Resources.Alarm (AWS::CloudWatch::CompositeAlarm)"));
        assertTrue(error.getMessage().contains("Resources.Pool (AWS::Cognito::UserPoolDomain)"));
        assertFalse(error.getMessage().contains("Resources.Bucket"));
    }

    @ParameterizedTest @ValueSource(strings = {
        "Transform: AWS::Serverless-2016-10-31\nResources: {Queue: {Type: AWS::SQS::Queue}}",
        "Resources: {Queue: {Type: AWS::SQS::Queue, Properties: {Fn::Transform: {Name: AWS::Include}}}}",
        "Resources: {Fn::ForEach::Loop: [Name, [A], {}]}",
        "Resources: {Child: {Type: AWS::CloudFormation::Stack, Properties: {TemplateURL: 'https://example.test/child.yml'}}}",
        "Resources: {Custom: {Type: 'Custom::Provider'}}",
        "Resources: {Custom: {Type: 'AWS::CloudFormation::CustomResource'}}"
    })
    void rejectsUninspectableExpansionAndIndirectProvisioning(String body) {
        assertThrows(GradleException.class, () -> CloudFormationTemplate.validate(body));
    }

    @ParameterizedTest @ValueSource(strings = {
        "Resources: {Resource: {Type: AWS::CloudWatch::Alarm, Type: AWS::S3::Bucket}}",
        "{\"Resources\":{\"Resource\":{\"Type\":\"AWS::CloudWatch::Alarm\",\"Type\":\"AWS::S3::Bucket\"}}}",
        "Resources: {Resource: {Type: AWS::CloudWatch::Alarm}, Resource: {Type: AWS::S3::Bucket}}",
        "Resources: {Resource: {Type: AWS::S3::Bucket}}\nResources: {Resource: {Type: AWS::SQS::Queue}}",
        "Resources: {Resource: &r {Type: AWS::S3::Bucket}, Copy: *r}",
        "Resources: {Resource: {<<: {Type: AWS::CloudWatch::Alarm}, Type: AWS::S3::Bucket}}",
        "Resources: {Resource: {Type: !Sub 'AWS::S3::Bucket'}}",
        "Resources: {Resource: {Type: {Ref: ResourceType}}}",
        "Resources: {Resource: {Properties: {Name: example}}}",
        "Resources: {}",
        "Resources: []",
        "---\nResources: {Resource: {Type: AWS::S3::Bucket}}\n---\nResources: {}",
        "Resources: !!java.util.LinkedHashMap {}"
    })
    void rejectsAmbiguousOrMalformedDocuments(String body) {
        assertThrows(GradleException.class, () -> CloudFormationTemplate.validate(body));
    }

    @Test void fileReadIsBoundedUtf8AndVersionSpecificWithoutParserValueLeaks() throws Exception {
        Path file = directory.resolve("template.yml");
        String valid = template("AWS::S3::Bucket");
        Files.writeString(file, valid);
        assertEquals(valid, CloudFormationTemplate.read(file, "1.0.471"));
        assertTrue(assertThrows(GradleException.class, () -> CloudFormationTemplate.read(file, "1.0.385"))
            .getMessage().contains("audited only"));
        Files.writeString(file, "Resources: [private-password: broken: syntax]");
        var failure = assertThrows(GradleException.class, () -> CloudFormationTemplate.read(file, "1.0.471"));
        assertFalse(failure.getMessage().contains("private-password"));
        assertNull(failure.getCause());
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(GradleException.class, () -> CloudFormationTemplate.read(file, "1.0.471"));
        Files.writeString(file, "x".repeat(CloudFormationTemplate.MAX_BYTES + 1));
        assertTrue(assertThrows(GradleException.class, () -> CloudFormationTemplate.read(file, "1.0.471"))
            .getMessage().contains("51200"));
    }

    private static String template(String type) { return "Resources: {Resource: {Type: '" + type + "'}}"; }
}
