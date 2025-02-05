package net.woggioni.gbcs.cli

import net.woggioni.gbcs.cli.impl.AbstractVersionProvider
import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.gbcs.cli.impl.commands.BenchmarkCommand
import net.woggioni.gbcs.cli.impl.commands.ClientCommand
import net.woggioni.gbcs.cli.impl.commands.GetCommand
import net.woggioni.gbcs.cli.impl.commands.HealthCheckCommand
import net.woggioni.gbcs.cli.impl.commands.PasswordHashCommand
import net.woggioni.gbcs.cli.impl.commands.PutCommand
import net.woggioni.gbcs.cli.impl.commands.ServerCommand
import net.woggioni.gbcs.common.GbcsUrlStreamHandlerFactory
import net.woggioni.gbcs.common.contextLogger
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
            val currentClassLoader = GradleBuildCacheServerCli::class.java.classLoader
            Thread.currentThread().contextClassLoader = currentClassLoader
            if(currentClassLoader.javaClass.name == "net.woggioni.envelope.loader.ModuleClassLoader") {
                //We're running in an envelope jar and custom URL protocols won't work
                GbcsUrlStreamHandlerFactory.install()
            }
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
                    addSubcommand(PutCommand())
                    addSubcommand(GetCommand())
                    addSubcommand(HealthCheckCommand())
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