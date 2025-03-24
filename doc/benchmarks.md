# RBCS performance benchmarks

All test were executed under the following conditions:
- CPU: Intel Celeron J3455 (4 physical cores)
- memory: 8GB DDR3L 1600 MHz
- disk: SATA3 120GB SSD
- HTTP compression: disabled
- cache compression: disabled
- digest: none
- authentication: disabled
- TLS: disabled
- network RTT: 14ms
- network bandwidth: 112 MiB/s
### In memory cache backend


| Cache backend  | CPU                 | CPU quota | Memory quota (GB) | Request size (b) | Client connections | PUT (req/s) | GET (req/s) |
|----------------|---------------------|-----------|-------------------|------------------|--------------------|-------------|-------------|
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 128              | 10                 | 7867        | 13762       |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 128              | 100                | 7728        | 14180       |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 512              | 10                 | 7964        | 10992       |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 512              | 100                | 8415        | 12478       |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 4096             | 10                 | 4268        | 5395        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 4096             | 100                | 5585        | 8259        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 65536            | 10                 | 1063        | 1185        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 65536            | 100                | 1522        | 1366        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 128              | 10                 | 11271       | 14092       |             
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 128              | 100                | 16064       | 24201       |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 512              | 10                 | 11504       | 13077       |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 512              | 100                | 17379       | 22094       |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 4096             | 10                 | 9151        | 9489        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 4096             | 100                | 13194       | 18268       |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 65536            | 10                 | 1590        | 1174        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 65536            | 100                | 1539        | 1561        |

### Filesystem cache backend

compression: disabled
digest: none
authentication: disabled
TLS: disabled

| Cache backend | CPU                 | CPU quota | Memory quota (GB) | Request size (b) | Client connections | PUT (req/s) | GET (req/s) |
|---------------|---------------------|-----------|-------------------|------------------|--------------------|-------------|-------------|
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 128              | 10                 | 1478        | 5771        |             
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 128              | 100                | 3166        | 8070        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 512              | 10                 | 1717        | 5895        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 512              | 100                | 1125        | 6564        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 4096             | 10                 | 819         | 2509        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 4096             | 100                | 1136        | 2365        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 65536            | 10                 | 584         | 632         |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.5               | 65536            | 100                | 529         | 635         |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 128              | 10                 | 1227        | 3342        |             
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 128              | 100                | 1156        | 4035        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 512              | 10                 | 979         | 3294        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 512              | 100                | 1217        | 3888        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 4096             | 10                 | 535         | 1805        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 4096             | 100                | 555         | 1910        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 65536            | 10                 | 301         | 494         |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.5               | 65536            | 100                | 353         | 595         |

### Memcache cache backend

compression: disabled
digest: MD5
authentication: disabled
TLS: disabled

| Cache backend | CPU                 | CPU quota | Memory quota (GB) | Request size (b) | Client connections | PUT (req/s) | GET (req/s) |
|---------------|---------------------|-----------|-------------------|------------------|--------------------|-------------|-------------|
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 128              | 10                 | 3380        | 6083        |             
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 128              | 100                | 3323        | 4998        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 512              | 10                 | 3924        | 6086        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 512              | 100                | 3440        | 5049        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 4096             | 10                 | 3347        | 5255        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 4096             | 100                | 3685        | 4693        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 65536            | 10                 | 1304        | 1343        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 65536            | 100                | 1481        | 1541        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 128              | 10                 | 4667        | 7984        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 128              | 100                | 4044        | 8358        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 512              | 10                 | 4177        | 7828        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 512              | 100                | 4079        | 8794        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 4096             | 10                 | 4588        | 6869        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 4096             | 100                | 5343        | 7797        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 65536            | 10                 | 1624        | 1317        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 65536            | 100                | 1633        | 1317        |
