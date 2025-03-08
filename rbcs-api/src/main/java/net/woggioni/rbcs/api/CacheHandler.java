package net.woggioni.rbcs.api;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCounted;
import lombok.extern.slf4j.Slf4j;
import net.woggioni.rbcs.api.message.CacheMessage;

@Slf4j
public abstract class CacheHandler extends ChannelInboundHandlerAdapter {
    private boolean requestFinished = false;

    abstract protected void channelRead0(ChannelHandlerContext ctx, CacheMessage msg);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if(!requestFinished && msg instanceof CacheMessage) {
            if(msg instanceof CacheMessage.LastCacheContent || msg instanceof CacheMessage.CacheGetRequest) requestFinished = true;
            try {
                channelRead0(ctx, (CacheMessage) msg);
            } finally {
                if(msg instanceof ReferenceCounted rc) rc.release();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    protected void sendMessageAndFlush(ChannelHandlerContext ctx, Object msg) {
        sendMessage(ctx, msg, true);
    }

    protected void sendMessage(ChannelHandlerContext ctx, Object msg) {
        sendMessage(ctx, msg, false);
    }

    private void sendMessage(ChannelHandlerContext ctx, Object msg, boolean flush) {
        ctx.write(msg);
        if(
                msg instanceof CacheMessage.LastCacheContent ||
                msg instanceof CacheMessage.CachePutResponse ||
                msg instanceof CacheMessage.CacheValueNotFoundResponse ||
                msg instanceof LastHttpContent
        ) {
            ctx.flush();
            ctx.pipeline().remove(this);
        } else if(flush) {
            ctx.flush();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }
}
