package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.jwo.Application
import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.client.Configuration
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import picocli.CommandLine
import java.lang.IllegalArgumentException
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
        required = false
    )
    var profileName : String? = null
        get() = field ?: throw IllegalArgumentException("A profile name must be specified using the '-p' command line parameter")

    val configuration : Configuration by lazy {
        Configuration.parse(configurationFile)
    }

    override fun run() {
        val log = createLogger<ClientCommand>()
        log.debug {
            "Using configuration file '$configurationFile'"
        }
        println("Available profiles:")
        configuration.profiles.forEach { (profileName, _) ->
            println(profileName)
        }
    }
}