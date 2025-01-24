package net.woggioni.gbcs.server.test

import io.netty.handler.codec.http.HttpResponseStatus
import net.woggioni.gbcs.api.Configuration
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class NoAnonymousUserTlsServerTest : AbstractTlsServerTest() {

    override val users = listOf(
        Configuration.User("user1", null, setOf(readersGroup), null),
        Configuration.User("user2", null, setOf(writersGroup), null),
        Configuration.User("user3", null, setOf(readersGroup, writersGroup), null),
    )

    @Test
    @Order(1)
    fun getAsAnonymousUser() {
        val (key, _) = keyValuePair
        val client: HttpClient = getHttpClient(null)

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode())
    }

    @Test
    @Order(2)
    fun putAsAnonymousUser() {
        val (key, value) = keyValuePair
        val client: HttpClient = getHttpClient(null)

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode())
    }
}