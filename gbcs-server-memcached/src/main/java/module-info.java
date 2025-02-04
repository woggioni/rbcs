import net.woggioni.gbcs.api.CacheProvider;

module net.woggioni.gbcs.server.memcached {
    requires net.woggioni.gbcs.common;
    requires net.woggioni.gbcs.api;
    requires com.googlecode.xmemcached;
    requires net.woggioni.jwo;
    requires java.xml;
    requires kotlin.stdlib;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.codec.memcache;
    requires io.netty.transport;
    requires org.slf4j;
    requires io.netty.buffer;
    requires io.netty.codec;

    provides CacheProvider with net.woggioni.gbcs.server.memcached.MemcachedCacheProvider;

    opens net.woggioni.gbcs.server.memcached.schema;
}