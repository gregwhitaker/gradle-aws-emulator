import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

plugins { id("io.github.gregwhitaker.aws-emulator") }

awsEmulator {
    port.set(0)
    val application = resourceSets.register("application") {
        provision("ordersQueue", CreateSqsQueueTask::class.java) {
            queueName.set("multi-project-orders")
        }
    }
    scenarios { register("default") { resources(application) } }
}
