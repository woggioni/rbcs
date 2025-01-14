package net.woggioni.gbcs.test

import io.netty.handler.codec.http.HttpResponseStatus
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.api.Role
import net.woggioni.gbcs.base.PasswordSecurity.hashPassword
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse


class BasicAuthServerTest : AbstractBasicAuthServerTest() {

    companion object {
        private const val PASSWORD = "password"
    }

    override val users = listOf(
        Configuration.User("user1", hashPassword(PASSWORD), setOf(readersGroup)),
        Configuration.User("user2", hashPassword(PASSWORD), setOf(writersGroup)),
        Configuration.User("user3", hashPassword(PASSWORD), setOf(readersGroup, writersGroup)),
        Configuration.User("", null, setOf(readersGroup))
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
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }

    @Test
    @Order(2)
    fun putAsAReaderUser() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Reader in it.roles && Role.Writer !in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }

    @Test
    @Order(3)
    fun getAsAWriterUser() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, _) = keyValuePair
        val user = cfg.users.values.find {
            Role.Writer in it.roles
        } ?: throw RuntimeException("Reader user not found")

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }

    @Test
    @Order(4)
    fun putAsAWriterUser() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Writer in it.roles
        } ?: throw RuntimeException("Reader user not found")

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.CREATED.code(), response.statusCode())
    }

    @Test
    @Order(5)
    fun getAsAReaderUser() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Reader in it.roles
        } ?: throw RuntimeException("Reader user not found")

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(6)
    fun getAsAnonymousUser() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, value) = keyValuePair

        val requestBuilder = newRequestBuilder(key)
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(7)
    fun getMissingKeyAsAReaderUser() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, _) = newEntry(random)
        val user = cfg.users.values.find {
            Role.Reader in it.roles
        } ?: throw RuntimeException("Reader user not found")

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode())
    }
}