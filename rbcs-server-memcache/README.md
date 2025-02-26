# RBCS Memcache plugins

This plugins allows RBCs to store and retrieve data from a memcache cluster.
The memcache server selection is simply based on the hash of the key, 
deflate compression is also supported and performed by the RBCS server

## Quickstart
The plugin can be built with
```bash
./gradlew rbcs-server-memcache:bundle
```
which creates a `.tar` archive in the `build/distributions` folder. 
The archive is supposed to be extracted inside the RBCS server's `plugins` directory.

## Configuration

The plugin can be enabled setting the `xs:type` attribute of the `cache` element
to `memcacheCacheType`.

The plugins currently supports the following configuration attributes:
- `max-age`: the amount of time cache entries will be retained on memcache
- `digest`: digest algorithm to use on the key before submission 
  to memcache (optional, no digest is applied if omitted)
- `compression`: compression algorithm to apply to cache values before, 
  currently only `deflate` is supported (optionla, if omitted compression is disabled)
- `compression-level`: compression level to use, deflate supports compression levels from 1 to 9, 
  where 1 is for fast compression at the expense of speed (optional, 6 is used if omitted)
```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<rbcs:server xmlns:xs="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:rbcs="urn:net.woggioni.rbcs.server"
             xmlns:rbcs-memcache="urn:net.woggioni.rbcs.server.memcache"
             xs:schemaLocation="urn:net.woggioni.rbcs.server.memcache jpms://net.woggioni.rbcs.server.memcache/net/woggioni/rbcs/server/memcache/schema/rbcs-memcache.xsd urn:net.woggioni.rbcs.server jpms://net.woggioni.rbcs.server/net/woggioni/rbcs/server/schema/rbcs-server.xsd"
>
    ...
    <cache xs:type="rbcs-memcache:memcacheCacheType" 
           max-age="P7D"
           digest="SHA-256"
           compression-mode="deflate"
           compression-level="6"
           chunk-size="0x10000">
        <server host="127.0.0.1" port="11211" max-connections="256"/>
        <server host="127.0.0.1" port="11212" max-connections="256"/>
    </cache>
    ...
```