package net.woggioni.rbcs.cli

import net.woggioni.jwo.Application
import net.woggioni.rbcs.cli.impl.AbstractVersionProvider
import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.cli.impl.commands.BenchmarkCommand
import net.woggioni.rbcs.cli.impl.commands.ClientCommand
import net.woggioni.rbcs.cli.impl.commands.GetCommand
import net.woggioni.rbcs.cli.impl.commands.HealthCheckCommand
import net.woggioni.rbcs.cli.impl.commands.PasswordHashCommand
import net.woggioni.rbcs.cli.impl.commands.PutCommand
import net.woggioni.rbcs.cli.impl.commands.ServerCommand
import net.woggioni.rbcs.common.RbcsUrlStreamHandlerFactory
import net.woggioni.rbcs.common.contextLogger
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec


@CommandLine.Command(
    name = "rbcs", versionProvider = RemoteBuildCacheServerCli.VersionProvider::class
)
class RemoteBuildCacheServerCli : RbcsCommand() {

    class VersionProvider : AbstractVersionProvider()
    companion object {
        private fun setPropertyIfNotPresent(key: String, value: String) {
            System.getProperty(key) ?: System.setProperty(key, value)
        }
        @JvmStatic
        fun main(vararg args: String) {
            setPropertyIfNotPresent("logback.configurationFile", "net/woggioni/rbcs/cli/logback.xml")
            setPropertyIfNotPresent("io.netty.leakDetectionLevel", "DISABLED")
            val currentClassLoader = RemoteBuildCacheServerCli::class.java.classLoader
            Thread.currentThread().contextClassLoader = currentClassLoader
            if(currentClassLoader.javaClass.name == "net.woggioni.envelope.loader.ModuleClassLoader") {
                //We're running in an envelope jar and custom URL protocols won't work
                RbcsUrlStreamHandlerFactory.install()
            }
            val log = contextLogger()
            val app = Application.builder("rbcs")
                .configurationDirectoryEnvVar("RBCS_CONFIGURATION_DIR")
                .configurationDirectoryPropertyKey("net.woggioni.rbcs.conf.dir")
                .build()
            val rbcsCli = RemoteBuildCacheServerCli()
            val commandLine = CommandLine(rbcsCli)
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