package net.woggioni.gbcs.cli.impl.commands

import net.woggioni.gbcs.base.contextLogger
import net.woggioni.gbcs.base.error
import net.woggioni.gbcs.base.info
import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.gbcs.client.GbcsClient
import picocli.CommandLine
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

@CommandLine.Command(
    name = "benchmark",
    description = ["Run a load test against the server"],
    showDefaultValues = true
)
class BenchmarkCommand : GbcsCommand() {
    private val log = contextLogger()

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    @CommandLine.Option(
        names = ["-e", "--entries"],
        description = ["Total number of elements to be added to the cache"],
        paramLabel = "NUMBER_OF_ENTRIES"
    )
    private var numberOfEntries = 1000

    override fun run() {
        val clientCommand = spec.parent().userObject() as ClientCommand
        val profile = clientCommand.profileName.let { profileName ->
            clientCommand.configuration.profiles[profileName]
                ?: throw IllegalArgumentException("Profile $profileName does not exist in configuration")
        }
        val client = GbcsClient(profile)

        val entryGenerator = sequence {
            val random = Random(SecureRandom.getInstance("NativePRNGNonBlocking").nextLong())
            while (true) {
                val key = Base64.getUrlEncoder().encode(random.nextBytes(16)).toString(Charsets.UTF_8)
                val value = random.nextBytes(0x1000)
                yield(key to value)
            }
        }

        val entries = let {
            val completionQueue = LinkedBlockingQueue<Future<Pair<String, ByteArray>>>(numberOfEntries)
            val start = Instant.now()
            entryGenerator.take(numberOfEntries).forEach { entry ->
                val future = client.put(entry.first, entry.second).thenApply { entry }
                future.whenComplete { _, _ ->
                    completionQueue.put(future)
                }
            }

            val inserted = sequence<Pair<String, ByteArray>> {
                var completionCounter = 0
                while (completionCounter < numberOfEntries) {
                    val future = completionQueue.take()
                    try {
                        yield(future.get())
                    } catch (ee: ExecutionException) {
                        val cause = ee.cause ?: ee
                        log.error(cause.message, cause)
                    }
                    completionCounter += 1
                }
            }.toList()
            val end = Instant.now()
            log.info {
                val elapsed = Duration.between(start, end).toMillis()
                "Insertion rate: ${numberOfEntries.toDouble() / elapsed * 1000} ops/s"
            }
            inserted
        }
        log.info {
            "Inserted ${entries.size} entries"
        }
        if (entries.isNotEmpty()) {
            val completionQueue = LinkedBlockingQueue<Future<Unit>>(entries.size)
            val start = Instant.now()
            entries.forEach { entry ->
                val future = client.get(entry.first).thenApply {
                    if (it == null) {
                        log.error {
                            "Missing entry for key '${entry.first}'"
                        }
                    } else if (!entry.second.contentEquals(it)) {
                        log.error {
                            "Retrieved a value different from what was inserted for key '${entry.first}'"
                        }
                    }
                }
                future.whenComplete { _, _ ->
                    completionQueue.put(future)
                }
            }
            var completionCounter = 0
            while (completionCounter < entries.size) {
                completionQueue.take()
                completionCounter += 1
            }
            val end = Instant.now()
            log.info {
                val elapsed = Duration.between(start, end).toMillis()
                "Retrieval rate: ${entries.size.toDouble() / elapsed * 1000} ops/s"
            }
        } else {
            log.error("Skipping retrieval benchmark as it was not possible to insert any entry in the cache")
        }
    }
}