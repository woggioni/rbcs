package net.woggioni.gbcs.client

import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import net.woggioni.gbcs.common.contextLogger
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.random.Random

class RetryTest {

    data class TestArgs(
        val seed: Int,
        val maxAttempt: Int,
        val initialDelay: Double,
        val exp: Double,
    )

    class TestArguments : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext): Stream<out Arguments> {
            return Stream.of(
                TestArgs(
                    seed = 101325,
                    maxAttempt = 5,
                    initialDelay = 50.0,
                    exp = 2.0,
                ),
                TestArgs(
                    seed = 101325,
                    maxAttempt = 20,
                    initialDelay = 100.0,
                    exp = 1.1,
                ),
                TestArgs(
                    seed = 123487,
                    maxAttempt = 20,
                    initialDelay = 100.0,
                    exp = 2.0,
                ),
                TestArgs(
                    seed = 20082024,
                    maxAttempt = 10,
                    initialDelay = 100.0,
                    exp = 2.0,
                )
            ).map {
                object: Arguments {
                    override fun get() = arrayOf(it)
                }
            }
        }
    }

    @ArgumentsSource(TestArguments::class)
    @ParameterizedTest
    fun test(testArgs: TestArgs) {
        val log = contextLogger()
        log.debug("Start")
        val executor: EventExecutorGroup = DefaultEventExecutorGroup(1)
        val attempts = mutableListOf<Pair<Long, OperationOutcome<Int>>>()
        val outcomeHandler = OutcomeHandler<Int> { outcome ->
            when(outcome) {
                is OperationOutcome.Success -> {
                    if(outcome.result % 10 == 0) {
                        OutcomeHandlerResult.DoNotRetry()
                    } else {
                        OutcomeHandlerResult.Retry(null)
                    }
                }
                is OperationOutcome.Failure -> {
                    when(outcome.ex) {
                        is IllegalStateException -> {
                            log.debug(outcome.ex.message, outcome.ex)
                            OutcomeHandlerResult.Retry(null)
                        }
                        else -> {
                            OutcomeHandlerResult.DoNotRetry()
                        }
                    }
                }
            }
        }
        val random = Random(testArgs.seed)

        val future =
            executeWithRetry(executor, testArgs.maxAttempt, testArgs.initialDelay, testArgs.exp, outcomeHandler) {
                val now = System.nanoTime()
                val result = CompletableFuture<Int>()
                executor.submit {
                    val n = random.nextInt(0, Integer.MAX_VALUE)
                    log.debug("Got new number: {}", n)
                    if(n % 3 == 0) {
                        val ex = IllegalStateException("Value $n can be divided by 3")
                        result.completeExceptionally(ex)
                        attempts += now to OperationOutcome.Failure(ex)
                    } else if(n % 7 == 0) {
                        val ex = RuntimeException("Value $n can be divided by 7")
                        result.completeExceptionally(ex)
                        attempts += now to OperationOutcome.Failure(ex)
                    } else {
                        result.complete(n)
                        attempts += now to OperationOutcome.Success(n)
                    }
                }
                result
            }
        Assertions.assertTrue(attempts.size <= testArgs.maxAttempt)
        val result = future.handle { res, ex ->
            if(ex != null) {
                val err = ex.cause ?: ex
                log.debug(err.message, err)
                OperationOutcome.Failure(err)
            } else {
                OperationOutcome.Success(res)
            }
        }.get()
        for ((index, attempt) in attempts.withIndex()) {
            val (timestamp, value) = attempt
            if (index > 0) {
                /* Check the delay for subsequent attempts is correct */
                val previousAttempt = attempts[index - 1]
                val expectedTimestamp =
                    previousAttempt.first + testArgs.initialDelay * Math.pow(testArgs.exp, index.toDouble()) * 1e6
                val actualTimestamp = timestamp
                val err = Math.abs(expectedTimestamp - actualTimestamp) / expectedTimestamp
                Assertions.assertTrue(err < 1e-3)
            }
            if (index == attempts.size - 1 && index < testArgs.maxAttempt - 1) {
                /*
                 * If the last attempt index is lower than the maximum number of attempts, then
                 * check the outcome handler returns DoNotRetry
                 */
                Assertions.assertTrue(outcomeHandler.shouldRetry(value) is OutcomeHandlerResult.DoNotRetry)
            } else if (index < attempts.size - 1) {
                /*
                 * If the attempt is not the last attempt check the outcome handler returns Retry
                 */
                Assertions.assertTrue(outcomeHandler.shouldRetry(value) is OutcomeHandlerResult.Retry)
            }
        }
    }
}