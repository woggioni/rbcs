package net.woggioni.rbcs.cli.impl.commands

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.client.Configuration
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.rbcs.common.createLogger
import picocli.CommandLine

@CommandLine.Command(
    name = "get",
    description = ["Fetch a value from the cache with the specified key"],
    showDefaultValues = true
)
class GetCommand : RbcsCommand() {
    companion object {
        private val log = createLogger<GetCommand>()

        fun execute(profile : Configuration.Profile, key : String, outputStream: OutputStream) {
            RemoteBuildCacheClient(profile).use { client ->
                client.get(key).thenApply { value ->
                    value?.let {
                        outputStream.use {
                            it.write(value)
                        }
                    } ?: throw NoSuchElementException("No value found for key $key")
                }.get()
            }
        }
    }

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
        execute(profile, key, (output?.let(Files::newOutputStream) ?: System.out))
    }
}