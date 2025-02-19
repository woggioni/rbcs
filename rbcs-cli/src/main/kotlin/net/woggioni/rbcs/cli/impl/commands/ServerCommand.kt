package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.jwo.Application
import net.woggioni.jwo.JWO
import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.cli.impl.converters.DurationConverter
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.info
import net.woggioni.rbcs.server.RemoteBuildCacheServer
import net.woggioni.rbcs.server.RemoteBuildCacheServer.Companion.DEFAULT_CONFIGURATION_URL
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@CommandLine.Command(
    name = "server",
    description = ["RBCS server"],
    showDefaultValues = true
)
class ServerCommand(app : Application) : RbcsCommand() {
    companion object {
        private val log = createLogger<ServerCommand>()
    }

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
        val handle = server.run()
        val shutdownHook = Thread.ofPlatform().unstarted {
            handle.sendShutdownSignal()
            try {
                handle.get(60, TimeUnit.SECONDS)
            } catch (ex : Throwable) {
                log.warn(ex.message, ex)
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        if(timeout != null) {
            Thread.sleep(timeout)
            handle.sendShutdownSignal()
        }
        handle.get()
    }
}