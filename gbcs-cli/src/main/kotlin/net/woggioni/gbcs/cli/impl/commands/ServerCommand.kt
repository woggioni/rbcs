package net.woggioni.gbcs.cli.impl.commands

import net.woggioni.gbcs.server.GradleBuildCacheServer
import net.woggioni.gbcs.server.GradleBuildCacheServer.Companion.DEFAULT_CONFIGURATION_URL
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.common.debug
import net.woggioni.gbcs.common.info
import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.jwo.Application
import net.woggioni.jwo.JWO
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

@CommandLine.Command(
    name = "server",
    description = ["GBCS server"],
    showDefaultValues = true
)
class ServerCommand(app : Application) : GbcsCommand() {

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
        names = ["-c", "--config-file"],
        description = ["Read the application configuration from this file"],
        paramLabel = "CONFIG_FILE"
    )
    private var configurationFile: Path = findConfigurationFile(app, "gbcs-server.xml")

    val configuration : Configuration by lazy {
        GradleBuildCacheServer.loadConfiguration(configurationFile)
    }

    override fun run() {
        if (!Files.exists(configurationFile)) {
            Files.createDirectories(configurationFile.parent)
            createDefaultConfigurationFile(configurationFile)
        }

        val configuration = GradleBuildCacheServer.loadConfiguration(configurationFile)
        log.debug {
            ByteArrayOutputStream().also {
                GradleBuildCacheServer.dumpConfiguration(configuration, it)
            }.let {
                "Server configuration:\n${String(it.toByteArray())}"
            }
        }
        val server = GradleBuildCacheServer(configuration)
        server.run().use {
        }
    }
}