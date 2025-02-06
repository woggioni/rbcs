package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.jwo.Application
import picocli.CommandLine
import java.nio.file.Path

@CommandLine.Command(
    name = "client",
    description = ["RBCS client"],
    showDefaultValues = true
)
class ClientCommand(app : Application) : RbcsCommand() {

    @CommandLine.Option(
        names = ["-c", "--configuration"],
        description = ["Path to the client configuration file"],
        paramLabel = "CONFIGURATION_FILE"
    )
    private var configurationFile : Path = findConfigurationFile(app, "rbcs-client.xml")

    @CommandLine.Option(
        names = ["-p", "--profile"],
        description = ["Name of the client profile to be used"],
        paramLabel = "PROFILE",
        required = true
    )
    var profileName : String? = null

    val configuration : RemoteBuildCacheClient.Configuration by lazy {
        RemoteBuildCacheClient.Configuration.parse(configurationFile)
    }

    override fun run() {
        println("Available profiles:")
        configuration.profiles.forEach { (profileName, _) ->
            println(profileName)
        }
    }
}