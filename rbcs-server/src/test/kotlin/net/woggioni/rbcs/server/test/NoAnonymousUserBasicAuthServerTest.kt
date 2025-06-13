package net.woggioni.rbcs.server.test

import io.netty.handler.codec.http.HttpResponseStatus
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.PasswordSecurity.hashPassword
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test


class NoAnonymousUserBasicAuthServerTest : AbstractBasicAuthServerTest() {

    companion object {
        private const val PASSWORD = "anotherPassword"
    }

    override val users = listOf(
        Configuration.User("user1", hashPassword(PASSWORD), setOf(readersGroup), null),
        Configuration.User("user2", hashPassword(PASSWORD), setOf(writersGroup), null),
        Configuration.User("user3", hashPassword(PASSWORD), setOf(readersGroup, writersGroup), null),
    )

    @Test
    @Order(1)
    fun putWithNoAuthorizationHeader() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, value) = keyValuePair

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode())
    }

    @Test
    @Order(2)
    fun getWithNoAuthorizationHeader() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, value) = keyValuePair

        val requestBuilder = newRequestBuilder(key)
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode())
    }
}