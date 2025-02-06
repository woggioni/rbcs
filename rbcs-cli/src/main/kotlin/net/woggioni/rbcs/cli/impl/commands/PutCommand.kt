package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.cli.impl.converters.InputStreamConverter
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.rbcs.common.contextLogger
import picocli.CommandLine
import java.io.InputStream

@CommandLine.Command(
    name = "put",
    description = ["Add or replace a value to the cache with the specified key"],
    showDefaultValues = true
)
class PutCommand : RbcsCommand() {
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
        RemoteBuildCacheClient(profile).use { client ->
            value.use {
                client.put(key, it.readAllBytes())
            }.get()
        }
    }
}