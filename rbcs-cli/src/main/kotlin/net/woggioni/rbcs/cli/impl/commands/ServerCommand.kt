package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.cli.impl.converters.DurationConverter
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.info
import net.woggioni.rbcs.server.RemoteBuildCacheServer
import net.woggioni.rbcs.server.RemoteBuildCacheServer.Companion.DEFAULT_CONFIGURATION_URL
import net.woggioni.jwo.Application
import net.woggioni.jwo.JWO
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@CommandLine.Command(
    name = "server",
    description = ["RBCS server"],
    showDefaultValues = true
)
class ServerCommand(app : Application) : RbcsCommand() {

    private val log = contextLogger()

    private fun createDefaultConfigurationFile(configurationFile: Path) {
        log.info {
            "Creating default configuration file at '$configurationFile'"
        }
        val defaultConfigurationFileResource = DEFAULT_CONFIGURATION_URL
        Files.newOutputStream(configurationFile).use { outputStream ->
            defaultConfigurationFileResource.openStream().use { inputStream ->
                JWO.copy(inputStream, outputStream)
            }
        }
    }

    @CommandLine.Option(
        names = ["-t", "--timeout"],
        description = ["Exit after the specified time"],
        paramLabel = "TIMEOUT",
        converter = [DurationConverter::class]
    )
    private var timeout: Duration? = null

    @CommandLine.Option(
        names = ["-c", "--config-file"],
        description = ["Read the application configuration from this file"],
        paramLabel = "CONFIG_FILE"
    )
    private var configurationFile: Path = findConfigurationFile(app, "rbcs-server.xml")

    override fun run() {
        if (!Files.exists(configurationFile)) {
            Files.createDirectories(configurationFile.parent)
            createDefaultConfigurationFile(configurationFile)
        }

        val configuration = RemoteBuildCacheServer.loadConfiguration(configurationFile)
        log.debug {
            ByteArrayOutputStream().also {
                RemoteBuildCacheServer.dumpConfiguration(configuration, it)
            }.let {
                "Server configuration:\n${String(it.toByteArray())}"
            }
        }
        val server = RemoteBuildCacheServer(configuration)
        server.run().use { server ->
            timeout?.let {
                Thread.sleep(it)
                server.shutdown()
            }
        }
    }
}