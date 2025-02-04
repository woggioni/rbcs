import net.woggioni.gbcs.api.CacheProvider;

module net.woggioni.gbcs.server.memcache {
    requires net.woggioni.gbcs.common;
    requires net.woggioni.gbcs.api;
    requires net.woggioni.jwo;
    requires java.xml;
    requires kotlin.stdlib;
    requires io.netty.transport;
    requires io.netty.codec;
    requires io.netty.codec.memcache;
    requires io.netty.common;
    requires io.netty.buffer;
    requires org.slf4j;

    provides CacheProvider with net.woggioni.gbcs.server.memcache.MemcacheCacheProvider;

    opens net.woggioni.gbcs.server.memcache.schema;
}