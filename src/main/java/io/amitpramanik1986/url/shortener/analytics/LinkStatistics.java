package io.amitpramanik1986.url.shortener.analytics;

import java.time.LocalDateTime;

public record LinkStatistics(String linkId,
                             long totalHits,
                             LocalDateTime lastAccessed) {
}
