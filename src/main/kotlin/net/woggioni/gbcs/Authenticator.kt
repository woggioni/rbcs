package net.woggioni.gbcs

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec


abstract class AbstractNettyHttpAuthenticator(private val authorizer : Authorizer)
        : ChannelInboundHandlerAdapter() {

    companion object {
        private const val KEY_LENGTH = 256
        private val AUTHENTICATION_FAILED: FullHttpResponse = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED, Unpooled.EMPTY_BUFFER).apply {
            headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
        }

        private val NOT_AUTHORIZED: FullHttpResponse = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.EMPTY_BUFFER).apply {
            headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
        }

        private fun concat(arr1: ByteArray, arr2: ByteArray): ByteArray {
            val result = ByteArray(arr1.size + arr2.size)
            var j = 0
            for(element in arr1) {
                result[j] = element
                j += 1
            }
            for(element in arr2) {
                result[j] = element
                j += 1
            }
            return result
        }


        fun hashPassword(password : String, salt : String? = null) : String {
            val actualSalt = salt?.let(Base64.getDecoder()::decode) ?: SecureRandom().run {
                val result = ByteArray(16)
                nextBytes(result)
                result
            }
            val spec: KeySpec = PBEKeySpec(password.toCharArray(), actualSalt, 10, KEY_LENGTH)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val hash = factory.generateSecret(spec).encoded
            return String(Base64.getEncoder().encode(concat(hash, actualSalt)))
        }

//        fun decodePasswordHash(passwordHash : String) : Pair<String, String> {
//            return passwordHash.indexOf(':')
//                .takeIf { it > 0 }
//                ?.let { sep ->
//                    passwordHash.substring(0, sep) to passwordHash.substring(sep)
//                } ?: throw IllegalArgumentException("Failed to decode password hash")
//        }

        fun decodePasswordHash(passwordHash : String) : Pair<ByteArray, ByteArray> {
            val decoded = Base64.getDecoder().decode(passwordHash)
            val hash = ByteArray(KEY_LENGTH / 8)
            val salt = ByteArray(decoded.size - KEY_LENGTH / 8)
            System.arraycopy(decoded, 0, hash, 0, hash.size)
            System.arraycopy(decoded, hash.size, salt, 0, salt.size)
            return hash to salt
        }
    }


    abstract fun authenticate(ctx : ChannelHandlerContext, req : HttpRequest) : Set<Role>?

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if(msg is HttpRequest) {
            val roles = authenticate(ctx, msg) ?: return authenticationFailure(ctx, msg)
            val authorized = authorizer.authorize(roles, msg)
            if(authorized) {
                super.channelRead(ctx, msg)
            } else {
                authorizationFailure(ctx, msg)
            }
        }
    }

    private fun authenticationFailure(ctx: ChannelHandlerContext, msg: Any) {
        ReferenceCountUtil.release(msg)
        ctx.writeAndFlush(AUTHENTICATION_FAILED.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
    }

    private fun authorizationFailure(ctx: ChannelHandlerContext, msg: Any) {
        ReferenceCountUtil.release(msg)
        ctx.writeAndFlush(NOT_AUTHORIZED.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
    }

}