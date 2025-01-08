import net.woggioni.gbcs.api.CacheProvider;

module net.woggioni.gbcs.memcached {
    requires net.woggioni.gbcs.base;
    requires net.woggioni.gbcs.api;
    requires com.googlecode.xmemcached;
    requires net.woggioni.jwo;
    requires java.xml;
    requires kotlin.stdlib;

    provides CacheProvider with net.woggioni.gbcs.memcached.MemcachedCacheProvider;

    opens net.woggioni.gbcs.memcached.schema;
}