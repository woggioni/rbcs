# RBCS servlet

This is a minimal implementation of RBCs using Jakarta servlet API, it relies
on the servlet container (Tomcat in this example) for authentication, authorization,
throttling, encryption and compression. It only supports in-memory caching.
The main purpose is to provide a performance comparison for RBCS Netty implementation.

## How to run

```bash
gradlew dockerBuildImage
```
 then in this directory run
```bash
docker run --rm -p 127.0.0.1:8080:8080 -m 1G --name tomcat -v $(pwd)/conf/server.xml:/usr/local/tomcat/conf/server.xml gitea.woggioni.net/woggioni/rbcs/servlet:latest
```

you can call the servlet cache with this RBCS client profile
```xml
    <profile name="servlet" base-url="http://127.0.0.1:8080/rbcs-servlet/cache/" max-connections="100" enable-compression="false">
    	<no-auth/>
    	<connection
	        idle-timeout="PT5S"
	        read-idle-timeout="PT10S"
	        write-idle-timeout="PT10S"
	        read-timeout="PT5S"
	        write-timeout="PT5S"/>
        <retry-policy max-attempts="10" initial-delay="PT1S" exp="1.2"/>
    </profile>
```

## Notes

The servlet implementation has an in memory cache whose maximum
size is hardcoded to 0x8000000 bytes (around 134 MB)
