package net.woggioni.rbcs.server.throttling

import io.netty.buffer.ByteBufHolder
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpMessage
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import java.net.InetSocketAddress
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import net.woggioni.jwo.Bucket
import net.woggioni.jwo.LongMath
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.server.RemoteBuildCacheServer

class ThrottlingHandler(
    private val bucketManager: BucketManager,
    rateLimiterConfiguration: Configuration.RateLimiter,
    connectionConfiguration: Configuration.Connection
) : ChannelInboundHandlerAdapter() {

    private companion object {
        private val log = createLogger<ThrottlingHandler>()

        fun nextAttemptIsWithinThreshold(nextAttemptNanos : Long, waitThreshold : Duration) : Boolean {
            val waitDuration = Duration.of(LongMath.ceilDiv(nextAttemptNanos, 100_000_000L) * 100L, ChronoUnit.MILLIS)
            return waitDuration < waitThreshold
        }
    }

    private class RefusedRequest

    private val maxMessageBufferSize = rateLimiterConfiguration.messageBufferSize
    private val maxQueuedMessages = rateLimiterConfiguration.maxQueuedMessages
    private val delayRequests = rateLimiterConfiguration.isDelayRequest
    private var requestBufferSize : Int = 0
    private var valveClosed = false
    private var queuedContent = ArrayDeque<Any>()

    /**
     * If the suggested waiting time from the bucket is lower than this
     * amount, then the server will simply wait by itself before sending a response
     * instead of replying with 429
     */
    private val waitThreshold = minOf(
        connectionConfiguration.idleTimeout,
        connectionConfiguration.readIdleTimeout,
        connectionConfiguration.writeIdleTimeout
    ).dividedBy(2)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if(valveClosed) {
            if(msg !is HttpRequest && msg is ByteBufHolder) {
                val newBufferSize = requestBufferSize + msg.content().readableBytes()
                if(newBufferSize > maxMessageBufferSize || queuedContent.size + 1 > maxQueuedMessages) {
                    log.debug {
                        if (newBufferSize > maxMessageBufferSize) {
                            "New message part exceeds maxMessageBufferSize, removing previous chunks"
                        } else {
                            "New message part exceeds maxQueuedMessages, removing previous chunks"
                        }
                    }
                    // If this message overflows the maxMessageBufferSize,
                    // then remove the previously enqueued chunks of the request from the deque,
                    // then discard the message
                    while(true) {
                        val tail = queuedContent.last()
                        if(tail is ByteBufHolder) {
                            requestBufferSize -= tail.content().readableBytes()
                            tail.release()
                        }
                        queuedContent.removeLast()
                        if(tail is HttpRequest) {
                            break
                        }
                    }
                    msg.release()
                    //Add a placeholder to remember to return a 429 response corresponding to this request
                    queuedContent.addLast(RefusedRequest())
                } else {
                    //If the message does not overflow maxMessageBufferSize, just add it to the deque
                    queuedContent.addLast(msg)
                    requestBufferSize = newBufferSize
                }
            } else if(msg is HttpRequest && msg is FullHttpMessage){
                val newBufferSize = requestBufferSize + msg.content().readableBytes()

                // If this message overflows the maxMessageBufferSize,
                // discard the message
                if(newBufferSize > maxMessageBufferSize || queuedContent.size + 1 > maxQueuedMessages) {
                    log.debug {
                        if (newBufferSize > maxMessageBufferSize) {
                            "New message exceeds maxMessageBufferSize, discarding it"
                        } else {
                            "New message exceeds maxQueuedMessages, discarding it"
                        }
                    }
                    msg.release()
                    //Add a placeholder to remember to return a 429 response corresponding to this request
                    queuedContent.addLast(RefusedRequest())
                } else {
                    //If the message does not exceed maxMessageBufferSize or maxQueuedMessages, just add it to the deque
                    queuedContent.addLast(msg)
                    requestBufferSize = newBufferSize
                }
            } else {
                queuedContent.addLast(msg)
            }
        } else {
            entryPoint(ctx, msg)
        }
    }

    private fun entryPoint(ctx : ChannelHandlerContext, msg : Any) {
        if(msg is RefusedRequest) {
            sendThrottledResponse(ctx, null)
            if(queuedContent.isEmpty()) {
                valveClosed = false
            } else {
                val head = queuedContent.poll()
                if(head is ByteBufHolder) {
                    requestBufferSize -= head.content().readableBytes()
                }
                entryPoint(ctx, head)
            }
        } else if(msg is HttpRequest) {
            val nextAttempt = getNextAttempt(ctx)
            if (nextAttempt < 0) {
                super.channelRead(ctx, msg)
                if(msg !is LastHttpContent) {
                    while (true) {
                        val head = queuedContent.poll() ?: break
                        if(head is ByteBufHolder) {
                            requestBufferSize -= head.content().readableBytes()
                        }
                        super.channelRead(ctx, head)
                        if (head is LastHttpContent) break
                    }
                }
                log.debug {
                    "Queue size: ${queuedContent.stream().filter { it !is RefusedRequest }.count()}"
                }
                if(queuedContent.isEmpty()) {
                    valveClosed = false
                } else {
                    val head = queuedContent.poll()
                    if(head is ByteBufHolder) {
                        requestBufferSize -= head.content().readableBytes()
                    }
                    entryPoint(ctx, head)
                }
            } else {
                val waitDuration = Duration.of(LongMath.ceilDiv(nextAttempt, 100_000_000L) * 100L, ChronoUnit.MILLIS)
                if (delayRequests && nextAttemptIsWithinThreshold(nextAttempt, waitThreshold)) {
                    valveClosed = true
                    ctx.executor().schedule({
                        entryPoint(ctx, msg)
                    }, waitDuration.toMillis(), TimeUnit.MILLISECONDS)
                } else {
                    sendThrottledResponse(ctx, waitDuration)
                    if(queuedContent.isEmpty()) {
                        valveClosed = false
                    } else {
                        val head = queuedContent.poll()
                        if(head is ByteBufHolder) {
                            requestBufferSize -= head.content().readableBytes()
                        }
                        entryPoint(ctx, head)
                    }
                }
            }
        } else {
            super.channelRead(ctx, msg)
            log.debug {
                "Queue size: ${queuedContent.stream().filter { it !is RefusedRequest }.count()}"
            }
        }
    }

    /**
     * Returns the number amount of milliseconds to wait before the requests can be processed
     * or -1 if the request can be performed immediately
     */
    private fun getNextAttempt(ctx : ChannelHandlerContext) : Long {
        val buckets = mutableListOf<Bucket>()
        val user = ctx.channel().attr(RemoteBuildCacheServer.userAttribute).get()
        if (user != null) {
            bucketManager.getBucketByUser(user)?.let(buckets::addAll)
        }
        val groups = ctx.channel().attr(RemoteBuildCacheServer.groupAttribute).get() ?: emptySet()
        if (groups.isNotEmpty()) {
            groups.forEach { group ->
                bucketManager.getBucketByGroup(group)?.let(buckets::add)
            }
        }
        if (user == null && groups.isEmpty()) {
            bucketManager.getBucketByAddress(ctx.channel().remoteAddress() as InetSocketAddress)?.let(buckets::add)
        }

        var nextAttempt = -1L
        for (bucket in buckets) {
            val bucketNextAttempt = bucket.removeTokensWithEstimate(1)
            if (bucketNextAttempt > nextAttempt) {
                nextAttempt = bucketNextAttempt
            }
        }
        return nextAttempt
    }

    private fun sendThrottledResponse(ctx: ChannelHandlerContext, retryAfter: Duration?) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.TOO_MANY_REQUESTS
        )
        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
        retryAfter?.seconds?.takeIf {
            it > 0
        }?.let {
            response.headers()[HttpHeaderNames.RETRY_AFTER] = it
        }

        ctx.writeAndFlush(response)
    }
}