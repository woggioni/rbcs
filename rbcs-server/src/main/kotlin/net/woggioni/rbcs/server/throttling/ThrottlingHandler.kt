package net.woggioni.rbcs.server.throttling

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.server.RemoteBuildCacheServer
import net.woggioni.jwo.Bucket
import net.woggioni.jwo.LongMath
import java.net.InetSocketAddress
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit


class ThrottlingHandler(cfg: Configuration) : ChannelInboundHandlerAdapter() {

    private companion object {
        @JvmStatic
        private val log = contextLogger()
    }

    private val bucketManager = BucketManager.from(cfg)

    private val connectionConfiguration = cfg.connection

    private var queuedContent : MutableList<HttpContent>? = null

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
        if(msg is HttpRequest) {
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
            if (buckets.isEmpty()) {
                super.channelRead(ctx, msg)
            } else {
                handleBuckets(buckets, ctx, msg, true)
            }
            ctx.channel().id()
        } else if(msg is HttpContent) {
            queuedContent?.add(msg) ?: super.channelRead(ctx, msg)
        } else {
            super.channelRead(ctx, msg)
        }
    }

    private fun handleBuckets(buckets: List<Bucket>, ctx: ChannelHandlerContext, msg: Any, delayResponse: Boolean) {
        var nextAttempt = -1L
        for (bucket in buckets) {
            val bucketNextAttempt = bucket.removeTokensWithEstimate(1)
            if (bucketNextAttempt > nextAttempt) {
                nextAttempt = bucketNextAttempt
            }
        }
        if (nextAttempt < 0) {
            super.channelRead(ctx, msg)
            queuedContent?.let {
                for(content in it) {
                    super.channelRead(ctx, content)
                }
                queuedContent = null
            }
        } else {
            val waitDuration = Duration.of(LongMath.ceilDiv(nextAttempt, 100_000_000L) * 100L, ChronoUnit.MILLIS)
            if (delayResponse && waitDuration < waitThreshold) {
                this.queuedContent = mutableListOf()
                ctx.executor().schedule({
                    handleBuckets(buckets, ctx, msg, false)
                }, waitDuration.toMillis(), TimeUnit.MILLISECONDS)
            } else {
                sendThrottledResponse(ctx, waitDuration)
            }
        }
    }

    private fun sendThrottledResponse(ctx: ChannelHandlerContext, retryAfter: Duration) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.TOO_MANY_REQUESTS
        )
        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
        retryAfter.seconds.takeIf {
            it > 0
        }?.let {
            response.headers()[HttpHeaderNames.RETRY_AFTER] = retryAfter.seconds
        }

        ctx.writeAndFlush(response)
    }
}