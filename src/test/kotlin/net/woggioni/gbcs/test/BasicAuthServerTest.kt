package net.woggioni.gbcs.test

import io.netty.handler.codec.Headers
import io.netty.handler.codec.http.HttpResponseStatus
import net.woggioni.gbcs.AbstractNettyHttpAuthenticator.Companion.hashPassword
import net.woggioni.gbcs.Authorizer
import net.woggioni.gbcs.Role
import net.woggioni.gbcs.Xml
import net.woggioni.gbcs.configuration.Configuration
import net.woggioni.gbcs.configuration.Serializer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.random.Random


class BasicAuthServerTest : AbstractServerTest() {

    companion object {
        private const val PASSWORD = "password"
    }

    private lateinit var cacheDir : Path

    private val random = Random(101325)
    private val keyValuePair = newEntry(random)

    override fun setUp() {
        this.cacheDir = testDir.resolve("cache")
        val readersGroup = Configuration.Group("readers", setOf(Role.Reader))
        val writersGroup = Configuration.Group("writers", setOf(Role.Writer))
        cfg = Configuration.of(
            cache = Configuration.FileSystemCache(this.cacheDir, maxAge = Duration.ofSeconds(3600 * 24)),
            host = "127.0.0.1",
            port = ServerSocket(0).localPort + 1,
            users = listOf(
                Configuration.User("user1", hashPassword(PASSWORD), setOf(readersGroup)),
                Configuration.User("user2", hashPassword(PASSWORD), setOf(writersGroup)),
                Configuration.User("user3", hashPassword(PASSWORD), setOf(readersGroup, writersGroup))
            ).asSequence().map { it.name to it}.toMap(),
            groups = sequenceOf(writersGroup, readersGroup).map { it.name to it}.toMap(),
            authentication = Configuration.BasicAuthentication(),
            useVirtualThread = true,
            tls = null,
            serverPath = "/"
        )
        Xml.write(Serializer.serialize(cfg), System.out)
    }

    override fun tearDown() {
    }

    fun buildAuthorizationHeader(user : Configuration.User, password : String) : String {
        val b64 = Base64.getEncoder().encode("${user.name}:${password}".toByteArray(Charsets.UTF_8)).let{
            String(it, StandardCharsets.UTF_8)
        }
        return "Basic $b64"
    }

    fun newRequestBuilder(key : String) = HttpRequest.newBuilder()
        .uri(URI.create("http://${cfg.host}:${cfg.port}/$key"))


    fun newEntry(random : Random) : Pair<String, ByteArray> {
        val key = ByteArray(0x10).let {
            random.nextBytes(it)
            Base64.getUrlEncoder().encodeToString(it)
        }
        val value = ByteArray(0x1000).also {
            random.nextBytes(it)
        }
        return key to value
    }

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