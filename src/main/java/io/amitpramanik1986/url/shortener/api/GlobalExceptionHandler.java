package io.amitpramanik1986.url.shortener.api;

import io.amitpramanik1986.url.shortener.core.LinkNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice(assignableTypes = {LinkApiEndpoints.class})
@Slf4j
@RequiredArgsConstructor
class GlobalExceptionHandler {

    private final ApplicationEventPublisher eventPublisher;

    @ExceptionHandler(LinkNotFoundException.class)
    ResponseEntity<ProblemDetail> handleResourceNotFound(@NonNull LinkNotFoundException ex) {

        eventPublisher.publishEvent(ex);
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND.value());
        problemDetail.setTitle("Link Not Found or Expired - "+ex.getLinkId());
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleGlobalError(@NonNull Exception ex) {
        log.error("Global Service error [{}]", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problemDetail.setTitle("Server Error");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problemDetail);
    }
}
