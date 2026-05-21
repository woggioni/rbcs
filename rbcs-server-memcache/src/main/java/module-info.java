import net.woggioni.rbcs.api.CacheProvider;
import net.woggioni.rbcs.api.TelemetryController;

module net.woggioni.rbcs.server.memcache {
    requires net.woggioni.rbcs.common;
    requires net.woggioni.rbcs.api;
    requires net.woggioni.jwo;
    requires java.xml;
    requires kotlin.stdlib;
    requires io.netty.transport;
    requires io.netty.codec;
    requires io.netty.codec.memcache;
    requires io.netty.common;
    requires io.netty.buffer;
    requires io.netty.handler;
    requires org.slf4j;

    provides CacheProvider with net.woggioni.rbcs.server.memcache.MemcacheCacheProvider;

    uses TelemetryController;

    opens net.woggioni.rbcs.server.memcache.schema;
}