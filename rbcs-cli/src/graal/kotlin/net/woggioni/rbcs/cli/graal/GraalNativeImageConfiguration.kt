package net.woggioni.rbcs.cli.graal

import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.api.Configuration.User
import net.woggioni.rbcs.api.Role
import net.woggioni.rbcs.cli.RemoteBuildCacheServerCli
import net.woggioni.rbcs.cli.impl.commands.BenchmarkCommand
import net.woggioni.rbcs.cli.impl.commands.HealthCheckCommand
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.PasswordSecurity.hashPassword
import net.woggioni.rbcs.common.RBCS
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.server.RemoteBuildCacheServer
import net.woggioni.rbcs.server.cache.FileSystemCacheConfiguration
import net.woggioni.rbcs.server.cache.InMemoryCacheConfiguration
import net.woggioni.rbcs.server.configuration.Parser
import net.woggioni.rbcs.server.memcache.MemcacheCacheConfiguration
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutionException
import java.util.zip.Deflater

object GraalNativeImageConfiguration {
    @JvmStatic
    fun main(vararg args : String) {

        val serverDoc = RemoteBuildCacheServer.DEFAULT_CONFIGURATION_URL.openStream().use {
            Xml.parseXml(RemoteBuildCacheServer.DEFAULT_CONFIGURATION_URL, it)
        }
        Parser.parse(doc)

        val clientDoc = RemoteBuildCacheClient.Configuration.openStream().use {
            Xml.parseXml(RemoteBuildCacheServer.DEFAULT_CONFIGURATION_URL, it)
        }
        Parser.parse(doc)

        val PASSWORD = "password"
        val readersGroup = Configuration.Group("readers", setOf(Role.Reader), null, null)
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
                chunkSize = 0x1000
            ),
            FileSystemCacheConfiguration(
                Path.of(System.getProperty("java.io.tmpdir")).resolve("rbcs"),
                maxAge = Duration.ofSeconds(3600),
                digestAlgorithm = "MD5",
                compressionLevel = Deflater.DEFAULT_COMPRESSION,
                compressionEnabled = false,
                chunkSize = 0x1000
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
                0x1000
            )
        )

        for (cache in caches) {
            val serverConfiguration = Configuration(
                "127.0.0.1",
                serverPort,
                100,
                null,
                Configuration.EventExecutor(true),
                Configuration.Connection(
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(15),
                    0x10000,
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
                0x1000
            )

            val serverHandle = RemoteBuildCacheServer(serverConfiguration).run()


            val clientProfile = RemoteBuildCacheClient.Configuration.Profile(
                URI.create("http://127.0.0.1:$serverPort/"),
                null,
                RemoteBuildCacheClient.Configuration.Authentication.BasicAuthenticationCredentials("user3", PASSWORD),
                Duration.ofSeconds(3),
                10,
                true,
                RemoteBuildCacheClient.Configuration.RetryPolicy(
                    3,
                    1000,
                    1.2
                ),
                RemoteBuildCacheClient.Configuration.TrustStore(null, null, false, false)
            )

            HealthCheckCommand.run(clientProfile)

            BenchmarkCommand.run(
                clientProfile,
                1000,
                0x100,
                true
            )

            serverHandle.sendShutdownSignal()
            try {
                serverHandle.get()
            } catch (ee : ExecutionException) {
            }
        }
        RemoteBuildCacheServerCli.main("--help")
    }
}