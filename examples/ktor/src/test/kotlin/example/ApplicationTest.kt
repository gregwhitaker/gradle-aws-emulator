package example

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test
    fun routeUsesTheProvisionedQueueOverHttps() = testApplication {
        application { module() }
        assertTrue(System.getenv("AWS_ENDPOINT_URL").startsWith("https://127.0.0.1:"))
        val response = client.get("/message")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().startsWith("Hello from Ktor: "))
        assertFalse(java.lang.Boolean.getBoolean("example.fail"), "Intentional test failure")
    }
}
