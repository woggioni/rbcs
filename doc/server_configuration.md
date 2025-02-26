### RBCS server configuration file elements and attributes

#### Root Element: `server`
The root element that contains all server configuration.

**Attributes:**
- `path` (optional): URI path prefix for cache requests. Example: if set to "cache", requests would be made to "http://www.example.com/cache/KEY"

#### Child Elements

#### `<bind>`
Configures server socket settings.

**Attributes:**
- `host` (required): Server bind address
- `port` (required): Server port number
- `incoming-connections-backlog-size` (optional, default: 1024): Maximum queue length for incoming connection indications

#### `<connection>`
Configures connection handling parameters.

**Attributes:**
- `idle-timeout` (optional, default: PT30S): Connection timeout when no activity
- `read-idle-timeout` (optional, default: PT60S): Connection timeout when no reads
- `write-idle-timeout` (optional, default: PT60S): Connection timeout when no writes
- `max-request-size` (optional, default: 0x4000000): Maximum allowed request body size

#### `<event-executor>`
Configures event execution settings.

**Attributes:**
- `use-virtual-threads` (optional, default: true): Whether to use virtual threads for the server handler

#### `<cache>`
Defines cache storage implementation. Two types are available:

##### InMemory Cache

A simple storage backend that uses an hash map to store data in memory

**Attributes:**
- `max-age` (default: P1D): Cache entry lifetime
- `max-size` (default: 0x1000000): Maximum cache size in bytes
- `digest` (default: MD5): Key hashing algorithm
- `enable-compression` (default: true): Enable deflate compression
- `compression-level` (default: -1): Compression level (-1 to 9)
- `chunk-size` (default: 0x10000): Maximum socket write size

##### FileSystem Cache

A storage backend that stores data in a folder on the disk

**Attributes:**
- `path`: Storage directory path
- `max-age` (default: P1D): Cache entry lifetime
- `digest` (default: MD5): Key hashing algorithm
- `enable-compression` (default: true): Enable deflate compression
- `compression-level` (default: -1): Compression level
- `chunk-size` (default: 0x10000): Maximum in-memory cache value size

#### `<authorization>`
Configures user and group-based access control.

##### `<users>`
List of registered users.
- Contains `<user>` elements:

  **Attributes:**
    - `name` (required): Username
    - `password` (optional): For basic authentication
- Can contain an `anonymous` element to allow for unauthenticated access

##### `<groups>`
List of user groups.
- Contains `<group>` elements:

    **Attributes:**
    - `name`: Group name
    - Can contain:
        - `users`: List of user references
        - `roles`: List of roles (READER/WRITER)
        - `user-quota`: Per-user quota
        - `group-quota`: Group-wide quota

#### `<authentication>`
Configures authentication mechanism. Options:
- `<basic>`: HTTP basic authentication
- `<client-certificate>`: TLS certificate authentication, it uses attributes of the subject's X.500 name
  to extract the username and group of the client.

  Example:
  ```xml
    <client-certificate>
        <user-extractor attribute-name="CN" pattern="(.*)"/>
        <group-extractor attribute-name="O" pattern="(.*)"/>
    </client-certificate>
  ```
- `<none>`: No authentication

#### `<tls>`
Configures TLS encryption.

**Child Elements:**
- `<keystore>`: Server certificate configuration

    **Attributes:**
    - `file` (required): Keystore file path
    - `password`: Keystore password
    - `key-alias` (required): Private key alias
    - `key-password`: Private key password

- `<truststore>`: Client certificate verification

    **Attributes:**  
    - `file` (required): Truststore file path
    - `password`: Truststore password
    - `check-certificate-status`: Enable CRL/OCSP checking
    - `require-client-certificate` (default: false): Require client certificates


----------------------------

# Complete configuration example

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<rbcs:server xmlns:xs="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:rbcs="urn:net.woggioni.rbcs.server"
             xs:schemaLocation="urn:net.woggioni.rbcs.server jpms://net.woggioni.rbcs.server/net/woggioni/rbcs/server/schema/rbcs-server.xsd"
>
    <bind host="0.0.0.0" port="8080" incoming-connections-backlog-size="1024"/>
    <connection
            max-request-size="67108864"
            idle-timeout="PT10S"
            read-idle-timeout="PT20S"
            write-idle-timeout="PT20S"
            read-timeout="PT5S"
            write-timeout="PT5S"/>
    <event-executor use-virtual-threads="true"/>
  
    <cache xs:type="rbcs:inMemoryCacheType" max-age="P7D" enable-compression="false" max-size="0x10000000" />
  
  <!-- uncomment this to enable the filesystem storage backend, sotring cache data in "${sys:java.io.tmpdir}/rbcs"
    <cache xs:type="rbcs:fileSystemCacheType" max-age="P7D" enable-compression="false" path="${sys:java.io.tmpdir}/rbcs"/>
  -->

  <!-- uncomment this to use memcache as the storage backend, also make sure you have 
       the memcache plugin installed in the `plugins` directory if you are using running
       the jar version of RBCS
  <cache xs:type="rbcs-memcache:memcacheCacheType" max-age="P7D" chunk-size="0x1000" digest="MD5">
    <server host="127.0.0.1" port="11211" max-connections="256"/>
  </cache>
  -->

  <authorization>
        <users>
            <user name="user1" password="II+qeNLft2pZ/JVNo9F7jpjM/BqEcfsJW27NZ6dPVs8tAwHbxrJppKYsbL7J/SMl">
                <quota calls="100" period="PT1S"/>
            </user>
            <user name="user2" password="v6T9+q6/VNpvLknji3ixPiyz2YZCQMXj2FN7hvzbfc2Ig+IzAHO0iiBCH9oWuBDq"/>
            <anonymous>
                <quota calls="10" period="PT60S" initial-available-calls="10" max-available-calls="10"/>
            </anonymous>
        </users>
        <groups>
            <group name="readers">
                <users>
                    <anonymous/>
                </users>
                <roles>
                    <reader/>
                </roles>
            </group>
            <group name="writers">
                <users>
                    <user ref="user1"/>
                    <user ref="user2"/>
                </users>
                <roles>
                    <reader/>
                    <writer/>
                </roles>
            </group>
        </groups>
    </authorization>
    <authentication>
      <basic/>
    </authentication>
</rbcs:server>

```