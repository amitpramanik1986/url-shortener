package io.amitpramanik1986.url.shortener.core;

public sealed interface LinkEvent permits LinkCreatedEvent, LinkAccessedEvet {

    default String linkId(){
        return null;
    }

}
