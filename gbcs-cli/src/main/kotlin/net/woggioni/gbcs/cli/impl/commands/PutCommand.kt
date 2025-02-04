package net.woggioni.gbcs.cli.impl.commands

import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.gbcs.cli.impl.converters.InputStreamConverter
import net.woggioni.gbcs.client.GradleBuildCacheClient
import net.woggioni.gbcs.common.contextLogger
import picocli.CommandLine
import java.io.InputStream

@CommandLine.Command(
    name = "put",
    description = ["Add or replace a value to the cache with the specified key"],
    showDefaultValues = true
)
class PutCommand : GbcsCommand() {
    private val log = contextLogger()

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    @CommandLine.Option(
        names = ["-k", "--key"],
        description = ["The key for the new value"],
        paramLabel = "KEY"
    )
    private var key : String = ""

    @CommandLine.Option(
        names = ["-v", "--value"],
        description = ["Path to a file containing the value to be added (defaults to stdin)"],
        paramLabel = "VALUE_FILE",
        converter = [InputStreamConverter::class]
    )
    private var value : InputStream = System.`in`

    override fun run() {
        val clientCommand = spec.parent().userObject() as ClientCommand
        val profile = clientCommand.profileName.let { profileName ->
            clientCommand.configuration.profiles[profileName]
                ?: throw IllegalArgumentException("Profile $profileName does not exist in configuration")
        }
        GradleBuildCacheClient(profile).use { client ->
            value.use {
                client.put(key, it.readAllBytes())
            }.get()
        }
    }
}