package net.woggioni.gbcs.cli

import net.woggioni.gbcs.GradleBuildCacheServer
import net.woggioni.gbcs.GradleBuildCacheServer.Companion.DEFAULT_CONFIGURATION_URL
import net.woggioni.gbcs.base.ClasspathUrlStreamHandlerFactoryProvider
import net.woggioni.gbcs.base.contextLogger
import net.woggioni.gbcs.base.debug
import net.woggioni.gbcs.base.info
import net.woggioni.gbcs.cli.impl.AbstractVersionProvider
import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.gbcs.cli.impl.commands.PasswordHashCommand
import net.woggioni.jwo.Application
import net.woggioni.jwo.JWO
import org.slf4j.Logger
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path


@CommandLine.Command(
    name = "gbcs", versionProvider = GradleBuildCacheServerCli.VersionProvider::class
)
class GradleBuildCacheServerCli(application : Application, private val log : Logger) : GbcsCommand() {

    class VersionProvider : AbstractVersionProvider()
    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            Thread.currentThread().contextClassLoader = GradleBuildCacheServerCli::class.java.classLoader
            ClasspathUrlStreamHandlerFactoryProvider.install()
            val log = contextLogger()
            val app = Application.builder("gbcs")
                .configurationDirectoryEnvVar("GBCS_CONFIGURATION_DIR")
                .configurationDirectoryPropertyKey("net.woggioni.gbcs.conf.dir")
                .build()
            val gbcsCli = GradleBuildCacheServerCli(app, log)
            val commandLine = CommandLine(gbcsCli)
            commandLine.setExecutionExceptionHandler { ex, cl, parseResult ->
                log.error(ex.message, ex)
                CommandLine.ExitCode.SOFTWARE
            }
            commandLine.addSubcommand(PasswordHashCommand())
            System.exit(commandLine.execute(*args))
        }
    }

    @CommandLine.Option(
        names = ["-c", "--config-file"],
        description = ["Read the application configuration from this file"],
        paramLabel = "CONFIG_FILE"
    )
    private var configurationFile: Path = findConfigurationFile(application)

    @CommandLine.Option(names = ["-V", "--version"], versionHelp = true)
    var versionHelp = false
        private set

    @CommandLine.Spec
    private lateinit var spec: CommandSpec

    private fun findConfigurationFile(app : Application): Path {
        val confDir = app.computeConfigurationDirectory()
        val configurationFile = confDir.resolve("gbcs.xml")
        return configurationFile
    }

    private fun createDefaultConfigurationFile(configurationFile : Path) {
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
        GradleBuildCacheServer(configuration).run().use {
        }
    }
}