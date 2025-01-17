package net.woggioni.gbcs.cli.impl.commands

import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.gbcs.client.GbcsClient
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

@CommandLine.Command(
    name = "get",
    description = ["Fetch a value from the cache with the specified key"],
    showDefaultValues = true
)
class GetCommand : GbcsCommand() {
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
        description = ["Path to a file where the retrieved value will be written (defaults to stdout)"],
        paramLabel = "VALUE_FILE",
    )
    private var output : Path? = null

    override fun run() {
        val clientCommand = spec.parent().userObject() as ClientCommand
        val profile = clientCommand.profileName.let { profileName ->
            clientCommand.configuration.profiles[profileName]
                ?: throw IllegalArgumentException("Profile $profileName does not exist in configuration")
        }
        GbcsClient(profile).use { client ->
            client.get(key).thenApply { value ->
                value?.let {
                    (output?.let(Files::newOutputStream) ?: System.out).use {
                        it.write(value)
                    }
                } ?: throw NoSuchElementException("No value found for key $key")
            }.get()
        }
    }
}