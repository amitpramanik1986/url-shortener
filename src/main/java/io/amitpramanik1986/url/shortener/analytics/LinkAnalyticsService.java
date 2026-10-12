package io.amitpramanik1986.url.shortener.analytics;

import io.amitpramanik1986.url.shortener.core.LinkNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class LinkAnalyticsService {

    private final LinkHitsRepository linkHitsRepository;

    @Transactional
    public void auditLinkCreatedEvent(String link, String customerId) {
        var linkHitsEntity = new LinkHitsEntity(link);
        linkHitsRepository.save(linkHitsEntity);
    }

    @Transactional
    public void auditLinkAccessedEvent(String linkId) {
        var linkHitsEntity = linkHitsRepository.findById(linkId).orElseThrow(() -> new IllegalArgumentException("Link not found"));
        linkHitsEntity.hasHit();
    }

    public @Nullable LinkStatistics getLinkStatistics(String linkId) throws LinkNotFoundException {
        return linkHitsRepository.findLinkStatisticsByLinkId(linkId).orElseThrow(() -> new LinkNotFoundException(linkId));
    }
}
