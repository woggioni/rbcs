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
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 128              | 10                 | 3691        | 4037        |             
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 128              | 100                | 6881        | 7483        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 512              | 10                 | 3790        | 4069        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 512              | 100                | 6716        | 7408        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 4096             | 10                 | 3399        | 1974        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 4096             | 100                | 5341        | 6402        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 65536            | 10                 | 1099        | 1116        |
| in-memory      | Intel Celeron J3455 | 1.00      | 4                 | 65536            | 100                | 1379        | 1703        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 128              | 10                 | 4443        | 5170        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 128              | 100                | 12813       | 13568       |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 512              | 10                 | 4450        | 4383        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 512              | 100                | 12212       | 13586       |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 4096             | 10                 | 3441        | 3012        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 4096             | 100                | 8982        | 10452       |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 65536            | 10                 | 1391        | 1167        |
| in-memory      | Intel Celeron J3455 | 3.50      | 4                 | 65536            | 100                | 1303        | 1151        |

### Filesystem cache backend

compression: disabled
digest: none
authentication: disabled
TLS: disabled

| Cache backend | CPU                 | CPU quota | Memory quota (GB) | Request size (b) | Client connections | PUT (req/s) | GET (req/s) |
|---------------|---------------------|-----------|-------------------|------------------|--------------------|-------------|-------------|
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 128              | 10                 | 1208        | 2048        |             
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 128              | 100                | 1304        | 2394        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 512              | 10                 | 1408        | 2157        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 512              | 100                | 1282        | 1888        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 4096             | 10                 | 1291        | 1256        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 4096             | 100                | 1170        | 1423        |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 65536            | 10                 | 313         | 606         |
| filesystem    | Intel Celeron J3455 | 1.00      | 0.25              | 65536            | 100                | 298         | 609         |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 128              | 10                 | 2195        | 3477        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 128              | 100                | 2480        | 6207        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 512              | 10                 | 2164        | 3413        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 512              | 100                | 2842        | 6218        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 4096             | 10                 | 1302        | 2591        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 4096             | 100                | 2270        | 3045        |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 65536            | 10                 | 375         | 394         |
| filesystem    | Intel Celeron J3455 | 3.50      | 0.25              | 65536            | 100                | 364         | 462         |


### Memcache cache backend

compression: disabled
digest: MD5
authentication: disabled
TLS: disabled

| Cache backend | CPU                 | CPU quota | Memory quota (GB) | Request size (b) | Client connections | PUT (req/s) | GET (req/s) |
|---------------|---------------------|-----------|-------------------|------------------|--------------------|-------------|-------------|
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 128              | 10                 | 2505        | 2578        |             
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 128              | 100                | 3582        | 3935        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 512              | 10                 | 2495        | 2784        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 512              | 100                | 3565        | 3883        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 4096             | 10                 | 2174        | 2505        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 4096             | 100                | 2937        | 3563        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 65536            | 10                 | 648         | 1074        |
| memcache      | Intel Celeron J3455 | 1.00      | 0.25              | 65536            | 100                | 724         | 1548        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 128              | 10                 | 2362        | 2927        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 128              | 100                | 5491        | 6531        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 512              | 10                 | 2125        | 2807        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 512              | 100                | 5173        | 6242        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 4096             | 10                 | 1720        | 2397        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 4096             | 100                | 3871        | 5859        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 65536            | 10                 | 616         | 1016        |
| memcache      | Intel Celeron J3455 | 3.50      | 0.25              | 65536            | 100                | 820         | 1677        |
