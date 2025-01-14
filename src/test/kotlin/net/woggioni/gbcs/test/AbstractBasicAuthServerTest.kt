package net.woggioni.gbcs.test

import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.api.Role
import net.woggioni.gbcs.base.Xml
import net.woggioni.gbcs.cache.FileSystemCacheConfiguration
import net.woggioni.gbcs.configuration.Serializer
import net.woggioni.gbcs.utils.NetworkUtils
import java.net.URI
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.zip.Deflater
import kotlin.random.Random


abstract class AbstractBasicAuthServerTest : AbstractServerTest() {

    private lateinit var cacheDir : Path

    protected val random = Random(101325)
    protected val keyValuePair = newEntry(random)
    protected val serverPath = "gbcs"
    protected val readersGroup = Configuration.Group("readers", setOf(Role.Reader))
    protected val writersGroup = Configuration.Group("writers", setOf(Role.Writer))

    abstract protected val users : List<Configuration.User>

    override fun setUp() {
        this.cacheDir = testDir.resolve("cache")
        cfg = Configuration(
            "127.0.0.1",
            NetworkUtils.getFreePort(),
            serverPath,
            users.asSequence().map { it.name to it}.toMap(),
            sequenceOf(writersGroup, readersGroup).map { it.name to it}.toMap(),
            FileSystemCacheConfiguration(this.cacheDir,
                maxAge = Duration.ofSeconds(3600 * 24),
                digestAlgorithm = "MD5",
                compressionLevel = Deflater.DEFAULT_COMPRESSION,
                compressionEnabled = false
            ),
            Configuration.BasicAuthentication(),
            null,
            true,
        )
        Xml.write(Serializer.serialize(cfg), System.out)
    }

    override fun tearDown() {
    }

    protected fun buildAuthorizationHeader(user : Configuration.User, password : String) : String {
        val b64 = Base64.getEncoder().encode("${user.name}:${password}".toByteArray(Charsets.UTF_8)).let{
            String(it, StandardCharsets.UTF_8)
        }
        return "Basic $b64"
    }

    protected fun newRequestBuilder(key : String) = HttpRequest.newBuilder()
        .uri(URI.create("http://${cfg.host}:${cfg.port}/$serverPath/$key"))


    protected fun newEntry(random : Random) : Pair<String, ByteArray> {
        val key = ByteArray(0x10).let {
            random.nextBytes(it)
            Base64.getUrlEncoder().encodeToString(it)
        }
        val value = ByteArray(0x1000).also {
            random.nextBytes(it)
        }
        return key to value
    }
}