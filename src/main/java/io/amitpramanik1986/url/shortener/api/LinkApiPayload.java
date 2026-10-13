package io.amitpramanik1986.url.shortener.api;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

class LinkApiPayload {

    record LinkCreateInput(
            @NonNull String url,
            @Nullable Long ttlInSeconds
    ) {
    }

    record LinkCreateOutput(
            @NonNull String shortKey,
            @NonNull String shortUrl,
            @NonNull LocalDateTime createdAt,
            @Nullable LocalDateTime expiresAt
    ) {

    }
}
