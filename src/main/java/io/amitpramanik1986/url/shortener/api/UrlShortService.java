package io.amitpramanik1986.url.shortener.api;

import io.amitpramanik1986.url.shortener.core.BaseConverter;
import io.amitpramanik1986.url.shortener.core.LinkNotFoundException;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShortService {

    private final LinkRepository linkRepository;

    @Value("${url.shortener.host}")
    private String urlHost;

    public LinkApiPayload.LinkCreateOutput createShortLinkID(@NotNull LinkApiPayload.LinkCreateInput input){

        log.info("Shortening URL [{}] with TTL [{}] seconds", input.url(), input.ttlInSeconds());
        var savedEntity = linkRepository.save(new LinkEntity(input.url(), input.ttlInSeconds()));
        var shortLinkId = BaseConverter.toBase(savedEntity.getId());
        log.info("Short LinkID [{}] for actual ID [{}]", shortLinkId, savedEntity.getId());
        return new LinkApiPayload.LinkCreateOutput(shortLinkId, urlHost + "/" + shortLinkId, savedEntity.getCreatedDate(), savedEntity.getExpiredDate());
    }

    public String getFullLink(@NotNull String key) throws LinkNotFoundException {
        var id = BaseConverter.toDecimal(key);
        return linkRepository.findById(id)
                .filter(LinkEntity::isNotExpired)
                .map(LinkEntity::getFullLink)
                .orElseThrow(()-> new LinkNotFoundException(key));
    }
}
