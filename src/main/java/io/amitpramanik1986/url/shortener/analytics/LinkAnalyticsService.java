package io.amitpramanik1986.url.shortener.analytics;

import io.amitpramanik1986.url.shortener.core.LinkNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
class LinkAnalyticsService {

    private final LinkHitsRepository linkHitsRepository;

    @Transactional
    public void auditLinkCreatedEvent(String link, String customerId) {
        var linkHitsEntity = new LinkHitsEntity(link);
        linkHitsRepository.save(linkHitsEntity);
    }

    @Transactional
    @Retryable
    public void auditLinkAccessedEvent(String linkId) {
        log.info("This should go to Kafka/EventBus for async processing");
        linkHitsRepository.incrementHitCount(linkId);
    }

    public @Nullable LinkStatistics getLinkStatistics(String linkId) throws LinkNotFoundException {
        return linkHitsRepository.findLinkStatisticsByLinkId(linkId).orElseThrow(() -> new LinkNotFoundException(linkId));
    }
}
