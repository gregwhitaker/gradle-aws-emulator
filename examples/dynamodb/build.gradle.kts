import io.github.gregwhitaker.awsemulator.aws.dynamodb.CreateDynamoDbTableTask
import software.amazon.awssdk.services.dynamodb.model.*

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

repositories { mavenCentral() }

val integrationSourceSet = sourceSets.create("integration")

dependencies {
    "integrationImplementation"(platform("software.amazon.awssdk:bom:2.39.6"))
    "integrationImplementation"("software.amazon.awssdk:dynamodb")
    "integrationImplementation"("software.amazon.awssdk:url-connection-client")
    "integrationImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val createProducts = tasks.register<CreateDynamoDbTableTask>("createProducts") {
    tableName.set("example-products")
    keySchema.add(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
    attributeDefinitions.set(listOf("id", "category").map {
        AttributeDefinition.builder().attributeName(it).attributeType(ScalarAttributeType.S).build()
    })
    globalSecondaryIndexes.add(GlobalSecondaryIndex.builder()
        .indexName("by-category")
        .keySchema(KeySchemaElement.builder().attributeName("category").keyType(KeyType.HASH).build())
        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
        .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5L).writeCapacityUnits(5L).build())
        .build())
    streamSpecification.set(StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build())
    initializer.set("example.buildsrc.dynamodb.ProductTableInitializer")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Reads initialized DynamoDB items and queries a secondary index."
    group = "verification"
    dependsOn(createProducts)
    testClassesDirs = integrationSourceSet.output.classesDirs
    classpath = integrationSourceSet.runtimeClasspath
    useJUnitPlatform()
}

awsEmulator {
    port.set(0)
    useWith(integrationTest)
}
