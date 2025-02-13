package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.error
import net.woggioni.rbcs.common.info
import net.woggioni.jwo.JWO
import net.woggioni.jwo.LongMath
import net.woggioni.rbcs.common.debug
import picocli.CommandLine
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@CommandLine.Command(
    name = "benchmark",
    description = ["Run a load test against the server"],
    showDefaultValues = true
)
class BenchmarkCommand : RbcsCommand() {
    private val log = contextLogger()

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    @CommandLine.Option(
        names = ["-e", "--entries"],
        description = ["Total number of elements to be added to the cache"],
        paramLabel = "NUMBER_OF_ENTRIES"
    )
    private var numberOfEntries = 1000

    @CommandLine.Option(
        names = ["-s", "--size"],
        description = ["Size of a cache value in bytes"],
        paramLabel = "SIZE"
    )
    private var size = 0x1000

    override fun run() {
        val clientCommand = spec.parent().userObject() as ClientCommand
        val profile = clientCommand.profileName.let { profileName ->
            clientCommand.configuration.profiles[profileName]
                ?: throw IllegalArgumentException("Profile $profileName does not exist in configuration")
        }
        val progressThreshold = LongMath.ceilDiv(numberOfEntries.toLong(), 20)
        RemoteBuildCacheClient(profile).use { client ->

            val entryGenerator = sequence {
                val random = Random(SecureRandom.getInstance("NativePRNGNonBlocking").nextLong())
                while (true) {
                    val key = JWO.bytesToHex(random.nextBytes(16))
                    val content = random.nextInt().toByte()
                    val value = ByteArray(size, { _ -> content })
                    yield(key to value)
                }
            }

            log.info {
                "Starting insertion"
            }
            val entries = let {
                val completionCounter = AtomicLong(0)
                val completionQueue = LinkedBlockingQueue<Pair<String, ByteArray>>(numberOfEntries)
                val start = Instant.now()
                val semaphore = Semaphore(profile.maxConnections * 3)
                val iterator = entryGenerator.take(numberOfEntries).iterator()
                while (completionCounter.get() < numberOfEntries) {
                    if (iterator.hasNext()) {
                        val entry = iterator.next()
                        semaphore.acquire()
                        val future = client.put(entry.first, entry.second).thenApply { entry }
                        future.whenComplete { result, ex ->
                            if (ex != null) {
                                log.error(ex.message, ex)
                            } else {
                                completionQueue.put(result)
                            }
                            semaphore.release()
                            val completed = completionCounter.incrementAndGet()
                            if(completed.mod(progressThreshold) == 0L) {
                                log.debug {
                                    "Inserted $completed / $numberOfEntries"
                                }
                            }
                        }
                    } else {
                        Thread.sleep(0)
                    }
                }

                val inserted = completionQueue.toList()
                val end = Instant.now()
                log.info {
                    val elapsed = Duration.between(start, end).toMillis()
                    val opsPerSecond = String.format("%.2f", numberOfEntries.toDouble() / elapsed * 1000)
                    "Insertion rate: $opsPerSecond ops/s"
                }
                inserted
            }
            log.info {
                "Inserted ${entries.size} entries"
            }
            log.info {
                "Starting retrieval"
            }
            if (entries.isNotEmpty()) {
                val completionCounter = AtomicLong(0)
                val semaphore = Semaphore(profile.maxConnections * 3)
                val start = Instant.now()
                val it = entries.iterator()
                while (completionCounter.get() < entries.size) {
                    if (it.hasNext()) {
                        val entry = it.next()
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
                            val completed = completionCounter.incrementAndGet()
                            if(completed.mod(progressThreshold) == 0L) {
                                log.debug {
                                    "Retrieved $completed / ${entries.size}"
                                }
                            }
                            semaphore.release()
                        }
                    } else {
                        Thread.sleep(0)
                    }
                }
                val end = Instant.now()
                log.info {
                    val elapsed = Duration.between(start, end).toMillis()
                    val opsPerSecond = String.format("%.2f", entries.size.toDouble() / elapsed * 1000)
                    "Retrieval rate: $opsPerSecond ops/s"
                }
            } else {
                log.error("Skipping retrieval benchmark as it was not possible to insert any entry in the cache")
            }
        }
    }
}