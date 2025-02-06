package net.woggioni.rbcs.server.test

import io.netty.handler.codec.http.HttpResponseStatus
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.api.Role
import net.woggioni.rbcs.common.PasswordSecurity.hashPassword
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.temporal.ChronoUnit


class BasicAuthServerTest : AbstractBasicAuthServerTest() {

    companion object {
        private const val PASSWORD = "password"
    }

    override val users = listOf(
        Configuration.User("user1", hashPassword(PASSWORD), setOf(readersGroup), null),
        Configuration.User("user2", hashPassword(PASSWORD), setOf(writersGroup), null),
        Configuration.User("user3", hashPassword(PASSWORD), setOf(readersGroup, writersGroup), null),
        Configuration.User("", null, setOf(readersGroup), null),
        Configuration.User("user4", hashPassword(PASSWORD), setOf(readersGroup),
            Configuration.Quota(1, Duration.of(1, ChronoUnit.DAYS), 0, 1)
        ),
        Configuration.User("user5", hashPassword(PASSWORD), setOf(readersGroup),
            Configuration.Quota(1, Duration.of(5, ChronoUnit.SECONDS), 0, 1)
        )
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

    @Test
    @Order(6)
    fun getAsAThrottledUser() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            it.name == "user4"
        } ?: throw RuntimeException("user4 not found")

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS.code(), response.statusCode())
    }

    @Test
    @Order(7)
    fun getAsAThrottledUser2() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            it.name == "user5"
        } ?: throw RuntimeException("user5 not found")

        val requestBuilder = newRequestBuilder(key)
            .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }
}