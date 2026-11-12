pluginManagement {
    includeBuild("../..")
}
rootProject.name = "aws-emulator-multi-project-example"
include("local-aws", "orders-api", "orders-worker")
