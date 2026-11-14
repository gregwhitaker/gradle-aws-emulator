# CloudFormation

Deploy a local JSON/YAML template with a provisioning check before Docker starts. Requires Java 17+, Docker with Linux containers, and the repository's Gradle wrapper.

From the repository root:

```sh
./gradlew -p examples/cloudformation validateCloudFormationTemplate
./gradlew -p examples/cloudformation integrationTest --configuration-cache
./gradlew -p examples/cloudformation integrationTest --configuration-cache
```

Validation is local and does not require Docker. The integration test deploys an S3 bucket, SQS queue, and DynamoDB table; writes and reads through each service API; and cleans up the managed emulator. The second run reuses the configuration cache while deploying into a fresh emulator.

The example waits for DynamoDB table activation before item operations: emulator 1.0.322 can report CloudFormation completion while service initialization is still in progress.

Try an unsupported resource:

```sh
./gradlew -p examples/cloudformation validateCloudFormationTemplate --templateFile=fixtures/unsupported.yaml
```

This command intentionally fails with `Resources.Alarm (AWS::CloudWatch::Alarm)`, even though its condition is false. No emulator is started and no stack is submitted.

For persistent development, deploy, repeat, update a parameter, and delete:

```sh
./gradlew -p examples/cloudformation deployExampleStack -Pdevelopment=true
./gradlew -p examples/cloudformation deployExampleStack -Pdevelopment=true
./gradlew -p examples/cloudformation deployExampleStack -Pdevelopment=true -PqueueName=cloudformation-updated-events
./gradlew -p examples/cloudformation listCloudFormationStacks
./gradlew -p examples/cloudformation deleteCloudFormationStack --stackName=cloudformation-example
./gradlew -p examples/cloudformation stopAwsEmulator
```

`deployExampleStack` creates or updates the named stack and writes a JSON object of stack outputs to `build/aws-emulator/cloudformation/deployExampleStack/outputs.json`. An unchanged deployment succeeds without changing the stack. Updating parameters can replace resources; deletion follows the template's deletion policies and service constraints.

Validation is pinned to AWS Emulator `1.0.322`. Unknown/declaration-only types are rejected. Transforms (including SAM), nested stacks, custom providers, and YAML anchors/aliases/merges are rejected because the plugin cannot inspect every resource they may introduce. Supply a fully expanded template or use the plugin's service-specific resource tasks. See the [CloudFormation guide](../../docs/src/docs/asciidoc/cloudformation.adoc).

To check managed cleanup on failure:

```sh
./gradlew -p examples/cloudformation integrationTest -PfailExample=true
```
