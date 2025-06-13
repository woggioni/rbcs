package net.woggioni.rbcs.cli.impl.commands

import net.woggioni.jwo.JWO
import net.woggioni.jwo.LongMath
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.cli.impl.RbcsCommand
import net.woggioni.rbcs.cli.impl.converters.ByteSizeConverter
import net.woggioni.rbcs.client.Configuration
import net.woggioni.rbcs.client.RemoteBuildCacheClient
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.error
import net.woggioni.rbcs.common.info
import picocli.CommandLine
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    companion object {
        private val log = createLogger<BenchmarkCommand>()

        fun execute(profile : Configuration.Profile,
                    numberOfEntries : Int,
                    entrySize : Int,
                    useRandomValue : Boolean,
        ) {
            val progressThreshold = LongMath.ceilDiv(numberOfEntries.toLong(), 20)
            RemoteBuildCacheClient(profile).use { client ->
                val entryGenerator = sequence {
                    val random = Random(SecureRandom.getInstance("NativePRNGNonBlocking").nextLong())
                    while (true) {
                        val key = JWO.bytesToHex(random.nextBytes(16))
                        val value = if (useRandomValue) {
                            random.nextBytes(entrySize)
                        } else {
                            val byteValue = random.nextInt().toByte()
                            ByteArray(entrySize) { _ -> byteValue }
                        }
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
                    val semaphore = Semaphore(profile.maxConnections * 5)
                    val iterator = entryGenerator.take(numberOfEntries).iterator()
                    while (completionCounter.get() < numberOfEntries) {
                        if (iterator.hasNext()) {
                            val entry = iterator.next()
                            semaphore.acquire()
                            val future =
                                client.put(entry.first, entry.second, CacheValueMetadata(null, null)).thenApply { entry }
                            future.whenComplete { result, ex ->
                                if (ex != null) {
                                    log.error(ex.message, ex)
                                } else {
                                    completionQueue.put(result)
                                }
                                semaphore.release()
                                val completed = completionCounter.incrementAndGet()
                                if (completed.mod(progressThreshold) == 0L) {
                                    log.debug {
                                        "Inserted $completed / $numberOfEntries"
                                    }
                                }
                            }
                        } else {
                            Thread.sleep(Duration.of(500, ChronoUnit.MILLIS))
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
                    val errorCounter = AtomicLong(0)
                    val completionCounter = AtomicLong(0)
                    val semaphore = Semaphore(profile.maxConnections * 5)
                    val start = Instant.now()
                    val it = entries.iterator()
                    while (completionCounter.get() < entries.size) {
                        if (it.hasNext()) {
                            val entry = it.next()
                            semaphore.acquire()
                            val future = client.get(entry.first).handle { response, ex ->
                                if(ex != null) {
                                    errorCounter.incrementAndGet()
                                    log.error(ex.message, ex)
                                } else if (response == null) {
                                    errorCounter.incrementAndGet()
                                    log.error {
                                        "Missing entry for key '${entry.first}'"
                                    }
                                } else if (!entry.second.contentEquals(response)) {
                                    errorCounter.incrementAndGet()
                                    log.error {
                                        "Retrieved a value different from what was inserted for key '${entry.first}': " +
                                        "expected '${JWO.bytesToHex(entry.second)}', got '${JWO.bytesToHex(response)}' instead"
                                    }
                                }
                            }
                            future.whenComplete { _, _ ->
                                val completed = completionCounter.incrementAndGet()
                                if (completed.mod(progressThreshold) == 0L) {
                                    log.debug {
                                        "Retrieved $completed / ${entries.size}"
                                    }
                                }
                                semaphore.release()
                            }
                        } else {
                            Thread.sleep(Duration.of(500, ChronoUnit.MILLIS))
                        }
                    }
                    val end = Instant.now()
                    val errors = errorCounter.get()
                    val successfulRetrievals = entries.size - errors
                    val successRate = successfulRetrievals.toDouble() / entries.size
                    log.info {
                        "Successfully retrieved ${entries.size - errors}/${entries.size} (${String.format("%.1f", successRate * 100)}%)"
                    }
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
        paramLabel = "SIZE",
        converter = [ByteSizeConverter::class]
    )
    private var size = 0x1000

    @CommandLine.Option(
        names = ["-r", "--random"],
        description = ["Insert completely random byte values"]
    )
    private var randomValues = false

    override fun run() {
        val clientCommand = spec.parent().userObject() as ClientCommand
        val profile = clientCommand.profileName.let { profileName ->
            clientCommand.configuration.profiles[profileName]
                ?: throw IllegalArgumentException("Profile $profileName does not exist in configuration")
        }
        execute(
            profile,
            numberOfEntries,
            size,
            randomValues
        )
    }
}