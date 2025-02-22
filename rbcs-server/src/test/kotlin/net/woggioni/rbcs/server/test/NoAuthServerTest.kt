package net.woggioni.rbcs.server.test

import io.netty.handler.codec.http.HttpResponseStatus
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.server.cache.InMemoryCacheConfiguration
import net.woggioni.rbcs.server.configuration.Serializer
import net.woggioni.rbcs.server.test.utils.NetworkUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.zip.Deflater
import kotlin.random.Random


class NoAuthServerTest : AbstractServerTest() {

    private lateinit var cacheDir: Path

    private val random = Random(101325)
    private val keyValuePair = newEntry(random)
    private val serverPath = "/some/nested/path"

    override fun setUp() {
        this.cacheDir = testDir.resolve("cache")
        cfg = Configuration(
            "127.0.0.1",
            NetworkUtils.getFreePort(),
            100,
            serverPath,
            Configuration.EventExecutor(false),
            Configuration.Connection(
                Duration.of(60, ChronoUnit.SECONDS),
                Duration.of(30, ChronoUnit.SECONDS),
                Duration.of(30, ChronoUnit.SECONDS),
                0x1000
            ),
            emptyMap(),
            emptyMap(),
            InMemoryCacheConfiguration(
                maxAge = Duration.ofSeconds(3600 * 24),
                compressionEnabled = true,
                digestAlgorithm = "MD5",
                compressionLevel = Deflater.DEFAULT_COMPRESSION,
                maxSize = 0x1000000,
                chunkSize = 0x1000
            ),
            null,
            null,
        )
        Xml.write(Serializer.serialize(cfg), System.out)
    }

    override fun tearDown() {
    }

    fun newRequestBuilder(key: String) = HttpRequest.newBuilder()
        .uri(URI.create("http://${cfg.host}:${cfg.port}/$serverPath/$key"))

    fun newEntry(random: Random): Pair<String, ByteArray> {
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
        val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
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
        val (key, value) = keyValuePair
        val requestBuilder = newRequestBuilder(key)
            .GET()
        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(3)
    fun getMissingKey() {
        val client: HttpClient = HttpClient.newHttpClient()

        val (key, _) = newEntry(random)
        val requestBuilder = newRequestBuilder(key).GET()

        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode())
    }

    @Test
    @Order(4)
    fun getUnhandledPath() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, _) = newEntry(random)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("http://${cfg.host}:${cfg.port}/some/other/path/$key"))
        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
    }

    @Test
    @Order(5)
    fun putUnhandledPath() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, value) = newEntry(random)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("http://${cfg.host}:${cfg.port}/some/other/path/$key"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
    }

    @Test
    @Order(6)
    fun getRelativeUnhandledPath() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, _) = newEntry(random)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("http://${cfg.host}:${cfg.port}/some/nested/path/../../../some/other/path/$key"))
        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
    }

    @Test
    @Order(7)
    fun getRelativePath() {
        val client: HttpClient = HttpClient.newHttpClient()
        val (key, value) = keyValuePair
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("http://${cfg.host}:${cfg.port}/some/other/path/../../nested/path/$key"))
        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        Assertions.assertArrayEquals(value, response.body())
    }

    @Test
    @Order(10)
    fun traceTest() {
        val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val requestBuilder = newRequestBuilder("").method(
            "TRACE",
            HttpRequest.BodyPublishers.ofByteArray("sfgsdgfaiousfiuhsd".toByteArray())
        )

        val response: HttpResponse<ByteArray> =
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        println(String(response.body()))
    }
}