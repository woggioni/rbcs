import net.woggioni.gbcs.api.CacheProvider;

module net.woggioni.gbcs.server.memcached {
    requires net.woggioni.gbcs.common;
    requires net.woggioni.gbcs.api;
    requires com.googlecode.xmemcached;
    requires net.woggioni.jwo;
    requires java.xml;
    requires kotlin.stdlib;

    provides CacheProvider with net.woggioni.gbcs.server.memcached.MemcachedCacheProvider;

    opens net.woggioni.gbcs.server.memcached.schema;
}