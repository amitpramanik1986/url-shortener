package io.amitpramanik1986.url.shortener.api;

import io.amitpramanik1986.url.shortener.core.BaseConverter;
import io.amitpramanik1986.url.shortener.core.LinkNotFoundException;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShortService {

    private final LinkRepository linkRepository;

    public String createShortLinkID(@NotNull String fullLink){

        log.info("Shortening URL [{}]", fullLink);
        var savedEntity = linkRepository.save(new LinkEntity(fullLink));
        var shortLinkId = BaseConverter.toBase(savedEntity.getId());
        log.info("Short LinkID [{}] for actual ID [{}]", shortLinkId, savedEntity.getId());
        return shortLinkId;
    }

    public String getFullLink(@NotNull String key) throws LinkNotFoundException {
        var id = BaseConverter.toDecimal(key);
        return linkRepository.findById(id)
                .map(link-> link.getFullLink())
                .orElseThrow(()-> new LinkNotFoundException(key));
    }
}
