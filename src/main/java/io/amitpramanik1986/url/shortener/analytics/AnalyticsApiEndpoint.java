package io.amitpramanik1986.url.shortener.analytics;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="Analytics API")
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/analytics")
class AnalyticsApiEndpoint {

    private final LinkAnalyticsService linkAnalyticsService;

    @SneakyThrows
    @GetMapping(value = "/hit-count/{linkId}", produces = "application/json")
    ResponseEntity<LinkStatistics> getLinkStatistics(@PathVariable String linkId){
        return ResponseEntity.ok(linkAnalyticsService.getLinkStatistics(linkId));
    }

}
