# XML Schema Documentation: RBCS Client Configuration

This document provides detailed information about the XML schema for RBCS client configuration, which defines profiles for connecting to RBCS servers.

## Root Element

### `profiles`
The root element that contains a collection of server profiles.
- **Type**: `profilesType`
- **Contains**: Zero or more `profile` elements

## Complex Types

### `profilesType`
Defines the structure for the profiles collection.
- **Elements**:
    - `profile`: Server connection profile (0 to unbounded)

### `profileType`
Defines a server connection profile with authentication, connection settings, and retry policies.

- **Attributes**:
    - `name` (required): Name of the server profile, referenced with the '-p' parameter in rbcs-cli
    - `base-url` (required): RBCs server URL
    - `max-connections`: Maximum number of concurrent TCP connections (default: 50)
    - `connection-timeout`: Timeout for establishing connections
    - `enable-compression`: Whether to enable HTTP compression (default: true)

- **Elements** (in sequence):
    - **Authentication** (choice of one):
        - `no-auth`: Disable authentication
        - `basic-auth`: Enable HTTP basic authentication
        - `tls-client-auth`: Enable TLS certificate authentication
    - `connection` (optional): Connection timeout settings
    - `retry-policy` (optional): Retry policy for failed requests
    - `tls-trust-store` (optional): Custom truststore for server certificate validation

### `connectionType`
Defines connection timeout settings.

- **Attributes**:
    - `idle-timeout`: Close connection after inactivity period (default: PT30S - 30 seconds)
    - `read-idle-timeout`: Close connection when no read occurs (default: PT60S - 60 seconds)
    - `write-idle-timeout`: Close connection when no write occurs (default: PT60S - 60 seconds)

### `noAuthType`
Indicates no authentication should be used.
- No attributes or elements

### `basicAuthType`
Configures HTTP Basic Authentication.

- **Attributes**:
    - `user` (required): Username for authentication
    - `password` (required): Password for authentication

### `tlsClientAuthType`
Configures TLS client certificate authentication.

- **Attributes**:
    - `key-store-file` (required): Path to the keystore file
    - `key-store-password` (required): Password to open the keystore
    - `key-alias` (required): Alias of the keystore entry with the private key
    - `key-password` (optional): Private key entry's encryption password

### `retryType`
Defines retry policy using exponential backoff.

- **Attributes**:
    - `max-attempts` (required): Maximum number of retry attempts
    - `initial-delay`: Delay before first retry (default: PT1S - 1 second)
    - `exp`: Exponent for computing next delay (default: 2.0)

### `trustStoreType`
Configures custom truststore for server certificate validation.

- **Attributes**:
    - `file` (required): Path to the truststore file
    - `password`: Truststore file password
    - `check-certificate-status`: Whether to check certificate validity using CRL/OCSP
    - `verify-server-certificate`: Whether to validate server certificates (default: true)

## Sample XML Document

```xml
<?xml version="1.0" encoding="UTF-8"?>
<profiles xmlns="urn:net.woggioni.rbcs.client">
  <!-- Profile with basic authentication -->
  <profile name="production-server" 
           base-url="https://rbcs.example.com/api" 
           max-connections="100" 
           enable-compression="true">
    <basic-auth user="admin" password="secure_password123"/>
    <connection idle-timeout="PT45S" 
                read-idle-timeout="PT90S" 
                write-idle-timeout="PT90S"/>
    <retry-policy max-attempts="5" 
                 initial-delay="PT2S" 
                 exp="1.5"/>
    <tls-trust-store file="/path/to/truststore.jks" 
                    password="truststore_password" 
                    check-certificate-status="true"/>
  </profile>
  
  <!-- Profile with TLS client authentication -->
  <profile name="secure-server" 
           base-url="https://secure.example.com/api" 
           max-connections="25">
    <tls-client-auth key-store-file="/path/to/keystore.p12" 
                    key-store-password="keystore_password" 
                    key-alias="client-cert" 
                    key-password="key_password"/>
    <retry-policy max-attempts="3"/>
  </profile>
  
  <!-- Profile with no authentication -->
  <profile name="development" 
           base-url="http://localhost:8080/api" 
           enable-compression="false">
    <no-auth/>
  </profile>
</profiles>
```

This sample XML document demonstrates three different profiles with various authentication methods and configuration options as defined in the schema.