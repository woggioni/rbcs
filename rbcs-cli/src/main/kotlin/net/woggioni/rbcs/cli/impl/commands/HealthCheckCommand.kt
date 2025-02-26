package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.client.Configuration
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.rbcs.common.createLogger
import picocli.CommandLine
import java.security.SecureRandom
import kotlin.random.Random

@CommandLine.Command(
    name = "health",
    description = ["Check server health"],
    showDefaultValues = true
)
class HealthCheckCommand : RbcsCommand() {
    companion object{
        private val log = createLogger<HealthCheckCommand>()

        fun execute(profile : Configuration.Profile) {
            RemoteBuildCacheClient(profile).use { client ->
                val random = Random(SecureRandom.getInstance("NativePRNGNonBlocking").nextLong())
                val nonce = ByteArray(0xa0)
                random.nextBytes(nonce)
                client.healthCheck(nonce).thenApply { value ->
                    if(value == null) {
                        throw IllegalStateException("Empty response from server")
                    }
                    val offset = value.size - nonce.size
                    for(i in 0 until nonce.size) {
                        val a = nonce[i]
                        val b = value[offset + i]
                        if(a != b) {
                            throw IllegalStateException("Server nonce does not match")
                        }
                    }
                }.get()
            }
        }
    }

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    override fun run() {
        val clientCommand = spec.parent().userObject() as ClientCommand
        val profile = clientCommand.profileName.let { profileName ->
            clientCommand.configuration.profiles[profileName]
                ?: throw IllegalArgumentException("Profile $profileName does not exist in configuration")
        }
        execute(profile)
    }
}