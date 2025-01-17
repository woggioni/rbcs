package net.woggioni.gbcs.server.test

import io.netty.handler.codec.http.HttpResponseStatus
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.api.Role
import org.bouncycastle.asn1.x500.X500Name
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse


class TlsServerTest : AbstractTlsServerTest() {

    override val users = listOf(
        Configuration.User("user1", null, setOf(readersGroup)),
        Configuration.User("user2", null, setOf(writersGroup)),
        Configuration.User("user3", null, setOf(readersGroup, writersGroup)),
        Configuration.User("", null, setOf(readersGroup))
    )

    @Test
    @Order(1)
    fun putAsAReaderUser() {
        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Reader in it.roles && Role.Writer !in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))
        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }

    @Test
    @Order(2)
    fun getAsAWriterUser() {
        val (key, _) = keyValuePair
        val user = cfg.users.values.find {
            Role.Writer in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .GET()

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }

    @Test
    @Order(3)
    fun putAsAWriterUser() {
        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Writer in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.CREATED.code(), response.statusCode())
    }

    @Test
    @Order(4)
    fun getAsAReaderUser() {
        val (key, value) = keyValuePair
        val user = cfg.users.values.find {
            Role.Reader in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .GET()

        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(5)
    fun getMissingKeyAsAReaderUser() {
        val (key, _) = newEntry(random)
        val user = cfg.users.values.find {
            Role.Reader in it.roles
        } ?: throw RuntimeException("Reader user not found")
        val client: HttpClient = getHttpClient(getClientKeyStore(ca, X500Name("CN=${user.name}")))

        val requestBuilder = newRequestBuilder(key)
            .GET()

        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode())
    }

    @Test
    @Order(6)
    fun getAsAnonymousUser() {
        val (key, value) = keyValuePair
        val client: HttpClient = getHttpClient(null)

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(7)
    fun putAsAnonymousUser() {
        val (key, value) = keyValuePair
        val client: HttpClient = getHttpClient(null)

        val requestBuilder = newRequestBuilder(key)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))

        val response: HttpResponse<String> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode())
    }
}