package net.woggioni.gbcs.cli.impl.commands

import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.common.error
import net.woggioni.gbcs.common.info
import net.woggioni.gbcs.cli.impl.GbcsCommand
import net.woggioni.gbcs.client.GradleBuildCacheClient
import net.woggioni.gbcs.client.RequestEventListener
import net.woggioni.jwo.JWO
import picocli.CommandLine
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
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
        val client = GradleBuildCacheClient(profile)

        val entryGenerator = sequence {
            val random = Random(SecureRandom.getInstance("NativePRNGNonBlocking").nextLong())
            while (true) {
                val key = JWO.bytesToHex(random.nextBytes(16))
                val content = random.nextInt().toByte()
                val value = ByteArray(0x1000, { _ -> content })
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
            val totalElapsedTime = AtomicLong(0)
            val iterator = entryGenerator.take(numberOfEntries).iterator()
            while(completionCounter.get() < numberOfEntries) {
                if(iterator.hasNext()) {
                    val entry = iterator.next()
                    semaphore.acquire()
                    val eventListener = object : RequestEventListener {
                        var start: Long? = null
                        override fun requestSent(req: FullHttpRequest) {
                            this.start = System.nanoTime()
                        }

                        override fun responseReceived(res: FullHttpResponse) {
                            this.start?.let { requestStart ->
                                totalElapsedTime.addAndGet((System.nanoTime() - requestStart))
                            }
                            this.start = null
                        }
                    }
                    val future = client.put(entry.first, entry.second, eventListener).thenApply { entry }
                    future.whenComplete { result, ex ->
                        if (ex != null) {
                            log.error(ex.message, ex)
                        } else {
                            completionQueue.put(result)
                        }
                        semaphore.release()
                        completionCounter.incrementAndGet()
                    }
                }
            }

            val inserted = completionQueue.toList()
            val end = Instant.now()
            log.info {
                val elapsed = Duration.between(start, end).toMillis()
                val opsPerSecond = String.format("%.2f", numberOfEntries.toDouble() / elapsed * 1000)
                "Insertion rate: $opsPerSecond ops/s"
            }
            log.info {
                val avgTxTime = String.format("%.0f", totalElapsedTime.get() / numberOfEntries.toDouble() / 1e6)
                "Average time per insertion: $avgTxTime ms"
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
            val totalElapsedTime = AtomicLong(0)
            entries.forEach { entry ->
                semaphore.acquire()
                val eventListener = object : RequestEventListener {
                    var start : Long? = null
                    override fun requestSent(req: FullHttpRequest) {
                        this.start = System.nanoTime()
                    }

                    override fun responseReceived(res: FullHttpResponse) {
                        this.start?.let { requestStart ->
                            totalElapsedTime.addAndGet((System.nanoTime() - requestStart))
                        }
                        this.start = null
                    }
                }

                val future = client.get(entry.first, eventListener).thenApply {
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
                    completionCounter.incrementAndGet()
                    semaphore.release()
                }
            }
            val end = Instant.now()
            log.info {
                val elapsed = Duration.between(start, end).toMillis()
                val opsPerSecond = String.format("%.2f", entries.size.toDouble() / elapsed * 1000)
                "Retrieval rate: $opsPerSecond ops/s"
            }
            log.info {
                val avgTxTime = String.format("%.0f", totalElapsedTime.get() / completionCounter.toDouble() / 1e6)
                "Average time per retrieval: $avgTxTime ms"
            }
        } else {
            log.error("Skipping retrieval benchmark as it was not possible to insert any entry in the cache")
        }
    }
}