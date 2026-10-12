package io.amitpramanik1986.url.shortener.core;

import lombok.Getter;

@Getter
public class LinkNotFoundException extends Exception {

    private final String linkId;
    public LinkNotFoundException(String linkId){
        super("Link id not found - "+linkId);
        this.linkId = linkId;
    }

}
