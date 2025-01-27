package net.woggioni.gbcs.client

import io.netty.util.concurrent.EventExecutorGroup
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

sealed class OperationOutcome<T> {
    class Success<T>(val result: T) : OperationOutcome<T>()
    class Failure<T>(val ex: Throwable) : OperationOutcome<T>()
}

sealed class OutcomeHandlerResult {
    class Retry(val suggestedDelayMillis: Long? = null) : OutcomeHandlerResult()
    class DoNotRetry : OutcomeHandlerResult()
}

fun interface OutcomeHandler<T> {
    fun shouldRetry(result: OperationOutcome<T>): OutcomeHandlerResult
}

fun <T> executeWithRetry(
    eventExecutorGroup: EventExecutorGroup,
    maxAttempts: Int,
    initialDelay: Double,
    exp: Double,
    outcomeHandler: OutcomeHandler<T>,
    cb: () -> CompletableFuture<T>
): CompletableFuture<T> {
    val finalResult = cb()
    var future = finalResult
    var shortCircuit = false
    for (i in 1 until maxAttempts) {
        future = future.handle { result, ex ->
            val operationOutcome = if (ex == null) {
                OperationOutcome.Success(result)
            } else {
                OperationOutcome.Failure(ex.cause ?: ex)
            }
            if (shortCircuit) {
                when(operationOutcome) {
                    is OperationOutcome.Failure -> throw operationOutcome.ex
                    is OperationOutcome.Success -> CompletableFuture.completedFuture(operationOutcome.result)
                }
            } else {
                when(val outcomeHandlerResult = outcomeHandler.shouldRetry(operationOutcome)) {
                    is OutcomeHandlerResult.Retry -> {
                        val res = CompletableFuture<T>()
                        val delay = run {
                            val scheduledDelay = (initialDelay * Math.pow(exp, i.toDouble())).toLong()
                            outcomeHandlerResult.suggestedDelayMillis?.coerceAtMost(scheduledDelay) ?: scheduledDelay
                        }
                        eventExecutorGroup.schedule({
                            cb().handle { result, ex ->
                                if (ex == null) {
                                    res.complete(result)
                                } else {
                                    res.completeExceptionally(ex)
                                }
                            }
                        }, delay, TimeUnit.MILLISECONDS)
                        res
                    }
                    is OutcomeHandlerResult.DoNotRetry -> {
                        shortCircuit = true
                        when(operationOutcome) {
                            is OperationOutcome.Failure -> throw operationOutcome.ex
                            is OperationOutcome.Success -> CompletableFuture.completedFuture(operationOutcome.result)
                        }
                    }
                }
            }
        }.thenCompose { it }
    }
    return future
}