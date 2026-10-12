package io.amitpramanik1986.url.shortener.analytics;

import io.amitpramanik1986.url.shortener.core.LinkAccessedEvet;
import io.amitpramanik1986.url.shortener.core.LinkCreatedEvent;
import io.amitpramanik1986.url.shortener.core.LinkEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
class LinkEventConsumer {

    private final LinkAnalyticsService linkAnalyticsService;

    @EventListener(classes = LinkEvent.class)
    @Async
    void consumeLinkEvent(LinkEvent event){
        switch (event){
            case LinkCreatedEvent(String linkId, String customerId) -> {
                log.info("[Kafka]Publishing Link created event {}", customerId);
                linkAnalyticsService.auditLinkCreatedEvent(linkId, customerId);
            }
            case LinkAccessedEvet(String linkId) -> {
                log.info("[Kafka]Publishing Link accessed event {}", linkId);
                linkAnalyticsService.auditLinkAccessedEvent(linkId);
            }
        }
    }
}
