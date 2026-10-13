# 02 – Scope: Improving the Existing URL Shortener

## Purpose

[01-scope-green-field.md](01-scope-green-field.md) defines **what** the MVP must do. This document tracks the **work on the existing code** needed to get there and beyond it:

1. **Bug fixes:** code that behaves incorrectly today
2. **Refactor proposals:** code that works but is hard to maintain, misleading or fragile
3. **Enhancement proposals:** new capabilities

Each item links back to the green-field feature ID (C*, A*, R*) it moves forward.

_Baseline: commit `c2da8a8`, assessed 2026-10-12._

**Legend**

- **Severity / priority:** 🔴 High · 🟠 Medium · 🟢 Low
- **Status:** `Open` · `In progress` · `Done` · `Won't do`

---

## 1. Bug fixes

| ID | Sev | Location | Problem | Proposed fix | Scope ref | Status |
|----|-----|----------|---------|--------------|-----------|--------|
| BUG-01 | 🔴 | [LinkApiEndpoints.java:61](src/main/java/io/amitpramanik1986/url/shortener/api/LinkApiEndpoints.java:61) | `model.addAttribute("attribute", …)` combined with a `redirect:` view adds the model to the target URL as a query parameter. Users land on `https://target?attribute=redirectWithRedirectPrefix` | Remove the model. Return `ResponseEntity.status(FOUND).location(URI.create(fullLink)).build()` | C3 | Open |
| BUG-02 | 🔴 | [LinkApiEndpoints.java:62](src/main/java/io/amitpramanik1986/url/shortener/api/LinkApiEndpoints.java:62) | `"redirect:" + fullLink` is treated as a URI template. A target URL containing `{…}` throws and returns `503` | Fixed by the BUG-01 change (no `RedirectView`) | C3 | Open |
| BUG-03 | 🔴 | [GlobalExceptionHandler.java:33](src/main/java/io/amitpramanik1986/url/shortener/api/GlobalExceptionHandler.java:33) | The `@ExceptionHandler(Exception.class)` catch-all turns client errors (malformed JSON, missing `url`, DB constraint violations) into `503 Service Unavailable` | Extend `ResponseEntityExceptionHandler` so Spring MVC exceptions keep their `4xx`. Map validation errors to `400` and leave `500` for real server faults | R2, R3 | Open |
| BUG-04 | 🟠 | [AnalyticsApiEndpoint.java:22](src/main/java/io/amitpramanik1986/url/shortener/analytics/AnalyticsApiEndpoint.java:22) | `@SneakyThrows` rethrows `LinkNotFoundException`, but the exception advice is limited to `LinkApiEndpoints`, so an unknown ID returns `500` | Remove `assignableTypes` (make the handler truly global) and drop `@SneakyThrows` | A5, R3 | Open |
| BUG-05 | 🟠 | [LinkEventConsumer.java](src/main/java/io/amitpramanik1986/url/shortener/analytics/LinkEventConsumer.java), [LinkHitsRepository.java](src/main/java/io/amitpramanik1986/url/shortener/analytics/LinkHitsRepository.java) | The hit-count row is inserted by an **async** "created" event. A visit processed before that insert updates 0 rows, and the hit is silently lost | Use an upsert (`MERGE INTO … KEY(SHORT_LINK_ID)`), or check the update count and insert if it's 0 | A1 | Open |
| BUG-06 | 🟠 | [LinkAnalyticsService.java:25](src/main/java/io/amitpramanik1986/url/shortener/analytics/LinkAnalyticsService.java:25) | `@Retryable` does nothing because `@EnableResilientMethods` is missing. Even when enabled, it would retry every exception type | Add `@EnableResilientMethods`. Restrict retries to `TransientDataAccessException` with a delay and backoff | R7 | Open |
| BUG-07 | 🟠 | [BaseConverter.java:36](src/main/java/io/amitpramanik1986/url/shortener/core/BaseConverter.java:36) | Leading `0` characters are ignored, so `abc`, `0abc` and `00abc` all resolve to the same link | Reject keys that start with `0` (unless the key is exactly `"0"`), or check `toBase(toDecimal(key)).equals(key)` | C2 | Open |
| BUG-08 | 🟠 | [LinkEntity.java:20](src/main/java/io/amitpramanik1986/url/shortener/api/LinkEntity.java:20) | A zero or negative `ttlInSeconds` is silently treated as "never expires". The earlier `400` check was removed | Validate with `@Positive` and return `400` | C6, C4 | Open |
| BUG-09 | 🟠 | [schema.sql:4](src/main/resources/schema.sql:4) | `FULL_LINK VARCHAR(1000)` with no input check: longer URLs fail on insert and return `503` | Validate `@Size(max = 2048)` and widen the column to match | C4 | Open |
| BUG-10 | 🟢 | [BaseConverter.java:42](src/main/java/io/amitpramanik1986/url/shortener/core/BaseConverter.java:42) | `toDecimal` throws an NPE for characters outside the alphabet. The route regex protects the redirect, but the method is still public and unsafe | Throw `IllegalArgumentException` with a clear message | C5 | Open |
| BUG-11 | 🟢 | [GlobalExceptionHandler.java:26](src/main/java/io/amitpramanik1986/url/shortener/api/GlobalExceptionHandler.java:26) | Every 404 publishes the exception as an application event that no listener handles | Remove it, or add a "not found" metric listener on purpose | R9 | Open |
| BUG-12 | 🟢 | [LinkHitsRepository.java](src/main/java/io/amitpramanik1986/url/shortener/analytics/LinkHitsRepository.java) | `lastAccessed` returns the creation time for a link that was never visited | Add a nullable `LAST_ACCESSED` column, or return `null` when `HIT_COUNT = 0` | A2 | Open |

## 2. Refactor proposals

| ID | Pri | Area | Current state | Proposal | Benefit | Status |
|----|-----|------|---------------|----------|---------|--------|
| RF-01 | 🔴 | Redirect | `ModelAndView` + `ModelMap` for a REST endpoint | `ResponseEntity<Void>` with a `Location` header (see BUG-01) | Correct, simpler and easy to test | Open |
| RF-02 | 🔴 | Validation | `jakarta.validation.@NotNull` is imported but never enforced (no starter, no `@Valid`) | Add `spring-boot-starter-validation`, then use `@Valid @RequestBody` and annotate `LinkCreateInput` with `@NotBlank @URL @Size` and `@Positive` | Gives `400` instead of `503` | Open |
| RF-03 | 🟠 | Error handling | `GlobalExceptionHandler` is scoped to one controller and hand-builds `ProblemDetail` | One global `@RestControllerAdvice extends ResponseEntityExceptionHandler`. Add `LinkExpiredException` → `410`. Enable `spring.mvc.problemdetails.enabled` | Consistent errors across all endpoints | Open |
| RF-04 | 🟠 | Naming | `LinkAccessedEvet` typo, `"Anonymus"`, `devisor`, `reminder` | Rename to `LinkAccessedEvent`, `"anonymous"`, `divisor`, `remainder` | Readability | Open |
| RF-05 | 🟠 | Misleading text | `[Kafka]Publishing…` log lines, "This should go to Kafka" and the OpenAPI description "Demo on Spring boot with Kafka Consumer". There is no Kafka | Use accurate log messages and a correct API description | Avoids confusion while debugging | Open |
| RF-06 | 🟠 | `BaseConverter` | `ConcurrentHashMap<Character,Integer>` for a fixed table, and a complicated `toDecimal` loop with a special case for one character | Use an `int[128]` lookup array and `result = result * BASE + digit`. Make the class `final` with a private constructor | Simpler, faster, no boxing | Open |
| RF-07 | 🟠 | Configuration | `@Value("${url.shortener.host}")` field injection next to `@RequiredArgsConstructor` | `@ConfigurationProperties("url.shortener")` record (`host`, `maxKeyLength`, `defaultTtl`) | Type-safe and easy to test | Open |
| RF-08 | 🟠 | Time handling | `LocalDateTime.now()` (server time zone) stored in `DATE` columns | Use `Instant` / `TIMESTAMP WITH TIME ZONE` and inject a `Clock` | Correct expiry across time zones; testable | Open |
| RF-09 | 🟢 | Transactions | `jakarta.transaction.Transactional` | Use Spring's `@Transactional` (with `readOnly` for queries) | Full Spring semantics | Open |
| RF-10 | 🟢 | Dead code | `CustomerActivityEntity` (empty), the unused `LinkEntity.expiredDate` setter, the `LinkEvent.linkId()` default returning `null`, the unused `@Version` | Remove them, or implement them as part of EN-05 | Less noise | Open |
| RF-11 | 🟢 | Visibility | `UrlShortService` is `public` while the rest of the package is package-private. `getLinkStatistics` is marked `@Nullable` but never returns null | Make it package-private and fix the nullability | Clear module boundaries | Open |
| RF-12 | 🟢 | API docs | `@ApiResponse` lists only the success code | Document `400`/`404`/`410`/`429` and the schemas | Accurate Swagger | Open |
| RF-13 | 🟢 | Tests | `BaseConverterTest` uses `MockitoExtension` with no mocks, and a loop instead of a parameterized test | `@ParameterizedTest` with `@ValueSource`, plus edge cases (0, `Long.MAX_VALUE`, invalid characters, leading zero) | Clearer failures | Open |
| RF-14 | 🟢 | Package structure | The `api` package holds the controller, service, entity and repository | Split into `link` (domain + persistence), `api` (web) and `analytics`. Optionally enforce with Spring Modulith | Clear layering | Open |

## 3. Enhancement proposals

| ID | Pri | Feature | Description | Scope ref | Status |
|----|-----|---------|-------------|-----------|--------|
| EN-01 | 🔴 | `410 Gone` for expired links | Separate "expired" from "not found" | C6 | Open |
| EN-02 | 🔴 | Integration and concurrency tests | `@SpringBootTest` + MockMvc for create, redirect, expiry and stats. A test sending 1,000 parallel hits must produce an exact count | R12, A4 | Open |
| EN-03 | 🔴 | PostgreSQL + Flyway | Replace in-memory H2 with Postgres (Testcontainers for tests) and versioned migrations | R1 | Open |
| EN-04 | 🟠 | Link metadata and delete APIs | `GET /api/links/{key}` and `DELETE /api/links/{key}` (soft delete via a `DISABLED` flag) | C7, C8 | Open |
| EN-05 | 🟠 | Click details | Fill in `CustomerActivityEntity`: timestamp, referrer, user agent, hashed IP and country | A6 | Open |
| EN-06 | 🟠 | Time-series stats | `GET /api/links/{key}/stats?from&to` returning daily hits | A7 | Open |
| EN-07 | 🟠 | Redirect cache | Caffeine cache `key → (url, expiresAt)` that is cleared on delete | R4 | Open |
| EN-08 | 🟠 | Rate limiting | Bucket4j per-IP limit on `POST /create`, returning `429` with `Retry-After` | R5 | Open |
| EN-09 | 🟠 | URL safety | Allow only `http`/`https`, block `javascript:`/`data:`/`file:`, and reject links pointing back to this service | R11, C4 | Open |
| EN-10 | 🟠 | Observability | Micrometer counters (created, redirected, not found, expired) and timers. Expose `/actuator/prometheus` and use structured JSON logs | R9 | Open |
| EN-11 | 🟢 | Graceful shutdown | `server.shutdown=graceful`, and a bounded async executor that waits for tasks to finish on shutdown | R10 | Open |
| EN-12 | 🟢 | Idempotent create | `Idempotency-Key` header, or optionally return the existing key for the same URL | R6 | Open |
| EN-13 | 🟢 | Health probes | Enable `management.endpoint.health.probes` (liveness/readiness) including the DB | R8 | Open |
| EN-14 | 🟢 | API versioning | Move endpoints under `/api/v1/links` and keep `/{key}` at the root | C1 | Open |
| EN-15 | 🟢 | Secure defaults | Turn the H2 console off by default (dev profile only) and load DB credentials from env/secrets | R11 | Open |
| EN-16 | 🟢 | Container and CI | Dockerfile / `spring-boot:build-image`, Maven wrapper and a GitHub Actions build-and-test workflow | — | Open |
| EN-17 | 🟢 | Post-MVP | Custom aliases, QR codes, bulk create, user accounts, Kafka-based analytics pipeline | Out of scope (01) | Backlog |

---

## 4. Suggested roadmap

| Phase | Goal | Items |
|-------|------|-------|
| **1 – Correctness** | Make the current endpoints behave correctly | BUG-01, BUG-02, BUG-03, BUG-04, BUG-05, BUG-07, BUG-08, BUG-09, RF-01, RF-02, RF-03, EN-01 |
| **2 – Safety net** | Prove it works and keep it working | EN-02, RF-13, BUG-06, RF-09, EN-09 |
| **3 – Production readiness** | Durable, observable, protected | EN-03, EN-07, EN-08, EN-10, EN-11, EN-13, EN-15, RF-08 |
| **4 – Feature completion** | Close the remaining MVP gaps | EN-04, EN-05, EN-06, EN-12, EN-14 |
| **5 – Clean-up** | Maintainability | RF-04 – RF-07, RF-10 – RF-12, RF-14, BUG-10 – BUG-12, EN-16 |

Completing phases 1–3 meets the MVP definition of done in [01-scope-green-field.md](01-scope-green-field.md#definition-of-done-mvp).

## 5. Change log

| Date | Commit | Change |
|------|--------|--------|
| 2026-10-12 | `c2da8a8` | Initial brown-field assessment: 12 bugs, 14 refactors, 17 enhancements |
