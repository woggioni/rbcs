package net.woggioni.gbcs.test

import io.netty.handler.codec.http.HttpResponseStatus
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
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.random.Random


class NoAuthServerTest : AbstractServerTest() {

    private lateinit var cacheDir : Path

    private val random = Random(101325)
    private val keyValuePair = newEntry(random)

    override fun setUp() {
        this.cacheDir = testDir.resolve("cache")
        cfg = Configuration.of(
            cache = Configuration.FileSystemCache(this.cacheDir, maxAge = Duration.ofSeconds(3600 * 24)),
            host = "127.0.0.1",
            port = ServerSocket(0).localPort + 1,
            users = emptyMap(),
            groups = emptyMap(),
            authentication = null,
            useVirtualThread = true,
            tls = null,
            serverPath = "/"
        )
        Xml.write(Serializer.serialize(cfg), System.out)
    }

    override fun tearDown() {
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
        Assertions.assertEquals(HttpResponseStatus.CREATED.code(), response.statusCode())
    }

    @Test
    @Order(2)
    fun getWithNoAuthorizationHeader() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, value ) = keyValuePair
        val requestBuilder = newRequestBuilder(key)
            .GET()
        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(3)
    fun getMissingKey() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, _) = newEntry(random)
        val requestBuilder = newRequestBuilder(key).GET()

        val response: HttpResponse<ByteArray> = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode())
    }
}