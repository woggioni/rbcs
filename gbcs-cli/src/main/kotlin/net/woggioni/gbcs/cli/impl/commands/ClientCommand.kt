package net.woggioni.gbcs.cli.impl.commands

import net.woggioni.gbcs.client.GbcsClient

import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.jwo.Application
import picocli.CommandLine
import java.nio.file.Path

@CommandLine.Command(
    name = "client",
    description = ["GBCS client"],
    showDefaultValues = true
)
class ClientCommand(app : Application) : GbcsCommand() {

    companion object {
        private fun findConfigurationFile(app: Application): Path {
            val confDir = app.computeConfigurationDirectory()
            val configurationFile = confDir.resolve("gbcs-client.xml")
            return configurationFile
        }
    }

    @CommandLine.Option(
        names = ["-c", "--configuration"],
        description = ["Path to the client configuration file"],
        paramLabel = "CONFIGURATION_FILE"
    )
    private var configurationFile : Path = findConfigurationFile(app)

    @CommandLine.Option(
        names = ["-p", "--profile"],
        description = ["Name of the client profile to be used"],
        paramLabel = "PROFILE",
        required = true
    )
    var profileName : String? = null

    val configuration : GbcsClient.Configuration by lazy {
        GbcsClient.Configuration.parse(configurationFile)
    }

    override fun run() {
        println("Available profiles:")
        configuration.profiles.forEach { (profileName, _) ->
            println(profileName)
        }
    }
}