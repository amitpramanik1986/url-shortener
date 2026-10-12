package io.amitpramanik1986.url.shortener.core;

public record LinkCreatedEvent(String linkId, String customerId) implements LinkEvent {

}
