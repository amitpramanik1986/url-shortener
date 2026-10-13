package io.amitpramanik1986.url.shortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

class LinkApiPayload {

    record LinkCreateInput(
            @NotBlank @Size(max = 2048) @NonNull String url,
            @PositiveOrZero @Nullable Long ttlInSeconds
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
