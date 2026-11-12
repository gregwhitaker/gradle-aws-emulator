package example

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Starts a real HTTP server, exercises its SQS route, and shuts down. */
fun main() = runBlocking {
    val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { module() }.start(wait = false)
    try {
        val port = server.engine.resolvedConnectors().single().port
        val response = withContext(Dispatchers.IO) {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/message"))
                    .timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
        }
        check(response.statusCode() == 200) { "Unexpected HTTP response: ${response.statusCode()}" }
        check(response.body().startsWith("Hello from Ktor: ")) { "Unexpected message: ${response.body()}" }
        println("Received from AWS Emulator: ${response.body()}")
        check(!java.lang.Boolean.getBoolean("example.fail")) { "Intentional application failure" }
    } finally {
        server.stop(0, 5000)
    }
}

/** The SDK reads the endpoint, region, credentials, and TLS settings supplied to this JVM. */
fun Application.module() {
    val sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()
    monitor.subscribe(ApplicationStopped) { sqs.close() }
    routing {
        get("/message") {
            val body = withContext(Dispatchers.IO) { roundTrip(sqs) }
            call.respondText(body)
        }
    }
}

private fun roundTrip(sqs: SqsClient): String {
    val url = sqs.getQueueUrl { it.queueName("ktor-example-events") }.queueUrl()
    val body = "Hello from Ktor: ${UUID.randomUUID()}"
    sqs.sendMessage { it.queueUrl(url).messageBody(body) }
    val message = sqs.receiveMessage { it.queueUrl(url).waitTimeSeconds(5) }.messages().firstOrNull()
        ?: error("Expected an SQS message")
    sqs.deleteMessage { it.queueUrl(url).receiptHandle(message.receiptHandle()) }
    check(message.body() == body) { "Unexpected SQS message: ${message.body()}" }
    return message.body()
}
