package net.woggioni.rbcs.api;

import java.io.Serializable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class CacheValueMetadata implements Serializable {
    private final String contentDisposition;
    private final String mimeType;
}

