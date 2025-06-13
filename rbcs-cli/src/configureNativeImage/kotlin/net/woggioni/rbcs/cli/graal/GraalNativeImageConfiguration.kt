package net.woggioni.rbcs.cli.graal

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutionException
import java.util.zip.Deflater
import net.woggioni.jwo.NullOutputStream
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.api.Configuration.User
import net.woggioni.rbcs.api.Role
import net.woggioni.rbcs.cli.RemoteBuildCacheServerCli
import net.woggioni.rbcs.cli.impl.commands.BenchmarkCommand
import net.woggioni.rbcs.cli.impl.commands.GetCommand
import net.woggioni.rbcs.cli.impl.commands.HealthCheckCommand
import net.woggioni.rbcs.cli.impl.commands.PutCommand
import net.woggioni.rbcs.client.Configuration as ClientConfiguration
import net.woggioni.rbcs.client.impl.Parser as ClientConfigurationParser
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.PasswordSecurity.hashPassword
import net.woggioni.rbcs.common.RBCS
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.server.RemoteBuildCacheServer
import net.woggioni.rbcs.server.cache.FileSystemCacheConfiguration
import net.woggioni.rbcs.server.cache.InMemoryCacheConfiguration
import net.woggioni.rbcs.server.configuration.Parser
import net.woggioni.rbcs.server.memcache.MemcacheCacheConfiguration

object GraalNativeImageConfiguration {
    @JvmStatic
    fun main(vararg args : String) {

        val serverURL = URI.create("file:conf/rbcs-server.xml").toURL()
        val serverDoc = serverURL.openStream().use {
            Xml.parseXml(serverURL, it)
        }
        Parser.parse(serverDoc)

        val url = URI.create("file:conf/rbcs-client.xml").toURL()
        val clientDoc = url.openStream().use {
            Xml.parseXml(url, it)
        }
        ClientConfigurationParser.parse(clientDoc)

        val PASSWORD = "password"
        val readersGroup = Configuration.Group("readers", setOf(Role.Reader, Role.Healthcheck), null, null)
        val writersGroup = Configuration.Group("writers", setOf(Role.Writer), null, null)


        val users = listOf(
            User("user1", hashPassword(PASSWORD), setOf(readersGroup), null),
            User("user2", hashPassword(PASSWORD), setOf(writersGroup), null),
            User("user3", hashPassword(PASSWORD), setOf(readersGroup, writersGroup), null),
            User("", null, setOf(readersGroup), null),
            User("user4", hashPassword(PASSWORD), setOf(readersGroup),
                Configuration.Quota(1, Duration.of(1, ChronoUnit.DAYS), 0, 1)
            ),
            User("user5", hashPassword(PASSWORD), setOf(readersGroup),
                Configuration.Quota(1, Duration.of(5, ChronoUnit.SECONDS), 0, 1)
            )
        )

        val serverPort = RBCS.getFreePort()

        val caches = listOf<Configuration.Cache>(
            InMemoryCacheConfiguration(
                maxAge = Duration.ofSeconds(3600),
                digestAlgorithm = "MD5",
                compressionLevel = Deflater.DEFAULT_COMPRESSION,
                compressionEnabled = false,
                maxSize = 0x1000000,
            ),
            FileSystemCacheConfiguration(
                Path.of(System.getProperty("java.io.tmpdir")).resolve("rbcs"),
                maxAge = Duration.ofSeconds(3600),
                digestAlgorithm = "MD5",
                compressionLevel = Deflater.DEFAULT_COMPRESSION,
                compressionEnabled = false,
            ),
            MemcacheCacheConfiguration(
                listOf(MemcacheCacheConfiguration.Server(
                    HostAndPort("127.0.0.1", 11211),
                    1000,
                    4)
                ),
                Duration.ofSeconds(60),
                "MD5",
                null,
                1,
            )
        )

        for (cache in caches) {
            val serverConfiguration = Configuration(
                "127.0.0.1",
                serverPort,
                100,
                null,
                Configuration.EventExecutor(true),
                Configuration.RateLimiter(
                    false, 0x100000, 10
                ),
                Configuration.Connection(
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(15),
                    0x10000,
                    0x1000
                ),
                users.asSequence().map { it.name to it }.toMap(),
                sequenceOf(writersGroup, readersGroup).map { it.name to it }.toMap(),
                cache,
                Configuration.BasicAuthentication(),
                null,
            )

            MemcacheCacheConfiguration(
                listOf(
                    MemcacheCacheConfiguration.Server(
                        HostAndPort("127.0.0.1", 11211),
                        1000,
                        4
                    )
                ),
                Duration.ofSeconds(60),
                "MD5",
                null,
                1,
            )

            val serverHandle = RemoteBuildCacheServer(serverConfiguration).run()


            val clientProfile = ClientConfiguration.Profile(
                URI.create("http://127.0.0.1:$serverPort/"),
                ClientConfiguration.Connection(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(7),
                    true,
                ),
                ClientConfiguration.Authentication.BasicAuthenticationCredentials("user3", PASSWORD),
                Duration.ofSeconds(3),
                10,
                true,
                ClientConfiguration.RetryPolicy(
                    3,
                    1000,
                    1.2
                ),
                ClientConfiguration.TrustStore(null, null, false, false)
            )

            HealthCheckCommand.execute(clientProfile)

            BenchmarkCommand.execute(
                clientProfile,
                1000,
                0x100,
                true
            )

            PutCommand.execute(
                clientProfile,
                "some-file.bin",
                ByteArrayInputStream(ByteArray(0x1000) { it.toByte() }),
                "application/octet-setream",
                "attachment; filename=\"some-file.bin\""
            )

            GetCommand.execute(
                clientProfile,
                "some-file.bin",
                NullOutputStream()
            )

            serverHandle.sendShutdownSignal()
            try {
                serverHandle.get()
            } catch (ee : ExecutionException) {
            }
        }
        System.setProperty("net.woggioni.rbcs.conf.dir", System.getProperty("gradle.tmp.dir"))
        RemoteBuildCacheServerCli.createCommandLine().execute("--version")
        RemoteBuildCacheServerCli.createCommandLine().execute("server", "-t", "PT10S")
    }
}