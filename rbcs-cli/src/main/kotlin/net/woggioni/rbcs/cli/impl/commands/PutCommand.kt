package net.woggioni.rbcs.cli.impl.commands

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.woggioni.jwo.Hash
import net.woggioni.jwo.JWO
import net.woggioni.jwo.NullOutputStream
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.client.Configuration
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.rbcs.common.createLogger
import picocli.CommandLine

@CommandLine.Command(
    name = "put",
    description = ["Add or replace a value to the cache with the specified key"],
    showDefaultValues = true
)
class PutCommand : RbcsCommand() {
    companion object {
        private val log = createLogger<PutCommand>()

        fun execute(profile: Configuration.Profile,
                    actualKey : String,
                    inputStream: InputStream,
                    mimeType : String?,
                    contentDisposition: String?
        ) {
            RemoteBuildCacheClient(profile).use { client ->
                inputStream.use {
                    client.put(actualKey, it.readAllBytes(), CacheValueMetadata(contentDisposition, mimeType))
                }.get()
                println(profile.serverURI.resolve(actualKey))
            }
        }
    }


    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    @CommandLine.Option(
        names = ["-k", "--key"],
        description = ["The key for the new value, randomly generated if omitted"],
        paramLabel = "KEY"
    )
    private var key : String? = null

    @CommandLine.Option(
        names = ["-i", "--inline"],
        description = ["File is to be displayed in the browser"],
        paramLabel = "INLINE",
    )
    private var inline : Boolean = false

    @CommandLine.Option(
        names = ["-t", "--type"],
        description = ["File mime type"],
        paramLabel = "MIME_TYPE",
    )
    private var mimeType : String? = null

    @CommandLine.Option(
        names = ["-v", "--value"],
        description = ["Path to a file containing the value to be added (defaults to stdin)"],
        paramLabel = "VALUE_FILE",
    )
    private var value : Path? = null

    override fun run() {
        val clientCommand = spec.parent().userObject() as ClientCommand
        val profile = clientCommand.profileName.let { profileName ->
            clientCommand.configuration.profiles[profileName]
                ?: throw IllegalArgumentException("Profile $profileName does not exist in configuration")
        }
        RemoteBuildCacheClient(profile).use { client ->
            val inputStream : InputStream
            val mimeType : String?
            val contentDisposition : String?
            val valuePath = value
            val actualKey : String?
            if(valuePath != null) {
                inputStream = Files.newInputStream(valuePath)
                mimeType = this.mimeType ?: Files.probeContentType(valuePath)
                contentDisposition = if(inline) {
                    "inline"
                } else {
                    "attachment; filename=\"${valuePath.fileName}\""
                }
                actualKey = key ?: let {
                    val md = Hash.Algorithm.SHA512.newInputStream(Files.newInputStream(valuePath)).use {
                        JWO.copy(it, NullOutputStream())
                        it.messageDigest
                    }
                    UUID.nameUUIDFromBytes(md.digest()).toString()
                }
            } else {
                inputStream = System.`in`
                mimeType = this.mimeType
                contentDisposition = if(inline) {
                    "inline"
                } else {
                    null
                }
                actualKey = key ?: UUID.randomUUID().toString()
            }
            execute(profile, actualKey, inputStream, mimeType, contentDisposition)
        }
    }
}