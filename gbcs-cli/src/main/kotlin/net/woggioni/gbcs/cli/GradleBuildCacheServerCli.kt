package net.woggioni.gbcs.cli

import net.woggioni.gbcs.base.GbcsUrlStreamHandlerFactory
import net.woggioni.gbcs.base.contextLogger
import net.woggioni.gbcs.cli.impl.AbstractVersionProvider
import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.gbcs.cli.impl.commands.BenchmarkCommand
import net.woggioni.gbcs.cli.impl.commands.ClientCommand
import net.woggioni.gbcs.cli.impl.commands.PasswordHashCommand
import net.woggioni.gbcs.cli.impl.commands.ServerCommand
import net.woggioni.jwo.Application
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec


@CommandLine.Command(
    name = "gbcs", versionProvider = GradleBuildCacheServerCli.VersionProvider::class
)
class GradleBuildCacheServerCli : GbcsCommand() {

    class VersionProvider : AbstractVersionProvider()
    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            Thread.currentThread().contextClassLoader = GradleBuildCacheServerCli::class.java.classLoader
            GbcsUrlStreamHandlerFactory.install()
            val log = contextLogger()
            val app = Application.builder("gbcs")
                .configurationDirectoryEnvVar("GBCS_CONFIGURATION_DIR")
                .configurationDirectoryPropertyKey("net.woggioni.gbcs.conf.dir")
                .build()
            val gbcsCli = GradleBuildCacheServerCli()
            val commandLine = CommandLine(gbcsCli)
            commandLine.setExecutionExceptionHandler { ex, cl, parseResult ->
                log.error(ex.message, ex)
                CommandLine.ExitCode.SOFTWARE
            }
            commandLine.addSubcommand(ServerCommand(app))
            commandLine.addSubcommand(PasswordHashCommand())
            commandLine.addSubcommand(
                CommandLine(ClientCommand(app)).apply {
                    addSubcommand(BenchmarkCommand())
                })
            System.exit(commandLine.execute(*args))
        }
    }

    @CommandLine.Option(names = ["-V", "--version"], versionHelp = true)
    var versionHelp = false
        private set

    @CommandLine.Spec
    private lateinit var spec: CommandSpec


    override fun run() {
        spec.commandLine().usage(System.out);
    }
}