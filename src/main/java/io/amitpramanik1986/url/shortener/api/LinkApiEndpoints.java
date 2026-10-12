package io.amitpramanik1986.url.shortener.api;

import io.amitpramanik1986.url.shortener.core.LinkAccessedEvet;
import io.amitpramanik1986.url.shortener.core.LinkCreatedEvent;
import io.amitpramanik1986.url.shortener.core.LinkNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Tag(name="Link API")
@RestController
@RequiredArgsConstructor
@Slf4j
class LinkApiEndpoints {

    private final UrlShortService urlShortService;
    private final ApplicationEventPublisher eventPublisher;

    @PostMapping(name = "/create",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(
            summary = "Create short link",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Create link id"
                    )
            }
    )
    ResponseEntity<String> shorten(@RequestParam String inputUrl){
        var shortLinkId = urlShortService.createShortLinkID(inputUrl);
        eventPublisher.publishEvent(new LinkCreatedEvent(shortLinkId, "Anonymus"));
        return ResponseEntity.status(HttpStatus.CREATED).body(shortLinkId) ;
    }


    @GetMapping("/{key}")
    @Operation(
            summary = "Visit short link",
            responses = {
                    @ApiResponse(
                            responseCode = "302",
                            description = "Redirects to actual URL"
                    )
            }
    )
    ModelAndView redirect(@PathVariable String key, ModelMap model) throws LinkNotFoundException {
        try{
            var fullLink = urlShortService.getFullLink(key);
            eventPublisher.publishEvent(new LinkAccessedEvet(key));
            model.addAttribute("attribute", "redirectWithRedirectPrefix");
            return new ModelAndView("redirect:"+fullLink, model);
        } catch (LinkNotFoundException nfe) {
            log.error("Link fetch error for - {}", key);
            throw nfe;
        }
    }

}
