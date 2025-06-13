package net.woggioni.rbcs.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@Getter
@RequiredArgsConstructor
public class CacheValueMetadata implements Serializable {
    private final String contentDisposition;
    private final String mimeType;
}

