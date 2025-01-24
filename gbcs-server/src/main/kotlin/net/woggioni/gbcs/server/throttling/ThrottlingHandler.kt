package net.woggioni.gbcs.server.throttling

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.server.GradleBuildCacheServer
import net.woggioni.jwo.Bucket
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit


@Sharable
class ThrottlingHandler(cfg: Configuration) :
    ChannelInboundHandlerAdapter() {

    private val log = contextLogger()
    private val bucketManager = BucketManager.from(cfg)

    private val connectionConfiguration = cfg.connection

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
        val buckets = mutableListOf<Bucket>()
        val user = ctx.channel().attr(GradleBuildCacheServer.userAttribute).get()
        if (user != null) {
            bucketManager.getBucketByUser(user)?.let(buckets::add)
        }
        val groups = ctx.channel().attr(GradleBuildCacheServer.groupAttribute).get() ?: emptySet()
        if (groups.isNotEmpty()) {
            groups.forEach { group ->
                bucketManager.getBucketByGroup(group)?.let(buckets::add)
            }
        }
        if (user == null && groups.isEmpty()) {
            bucketManager.getBucketByAddress(ctx.channel().remoteAddress() as InetSocketAddress)?.let(buckets::add)
        }
        if (buckets.isEmpty()) {
            return super.channelRead(ctx, msg)
        } else {
            var nextAttempt = Long.MAX_VALUE
            for (bucket in buckets) {
                val bucketNextAttempt = bucket.removeTokensWithEstimate(1)
                if (bucketNextAttempt < 0) {
                    return super.channelRead(ctx, msg)
                } else if (bucketNextAttempt < nextAttempt) {
                    nextAttempt = bucketNextAttempt
                }
            }
            val waitDuration = Duration.ofNanos(nextAttempt)
            if (waitDuration < waitThreshold) {
                ctx.executor().schedule({
                    ctx.fireChannelRead(msg)
                }, waitDuration.toNanos(), TimeUnit.NANOSECONDS)
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
        response.headers()[HttpHeaderNames.RETRY_AFTER] = retryAfter.seconds
        ctx.writeAndFlush(response)
    }
}