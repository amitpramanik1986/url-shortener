# URL Shortener

A Spring Boot service that turns long URLs into short keys, redirects visitors from a short key to the original URL, and counts how often each link is visited. Links can be given an optional time to live (TTL), after which they stop working.

This repository holds the **demo implementation** (single service, in-memory database) along with the design documents for the **target production architecture** (API Gateway, Cassandra, Kafka). See [Documentation](#documentation).

## What it does

- **Shorten:** Saves the full URL with an ID from a database sequence (starting at 100000), then encodes the ID into a short key. The key alphabet is `0-9 A-Z a-z - _ ~` (65 characters), and keys are at most 10 characters long. If `ttlInSeconds` is given, the link's expiry time is set to creation time + TTL.
- **Redirect:** Decodes the short key back to the ID and looks up the full URL. If the link exists and hasn't expired, the service responds with **`302 Found`**. Unknown and expired keys both return **`404 Not Found`**.
- **Analytics:** Creating or visiting a link publishes an application event. An `@Async` listener records each event, keeping a hit count (updated with an atomic SQL `UPDATE`) and the last-accessed time for every short link.

## Core APIs

| Method | Path | Description | Response |
|--------|------|-------------|----------|
| `POST` | `/create` | Creates a short link. JSON body: `{ "url": "...", "ttlInSeconds": 3600 }` (`ttlInSeconds` is optional; without it the link never expires) | `201 Created` with `{ shortKey, shortUrl, createdAt, expiresAt }` |
| `GET`  | `/{key}` | Redirects to the original URL. `key` must match `[0-9A-Za-z_~-]{1,10}` | `302 Found` with a `Location` header, or `404` (`ProblemDetail`) if the key is unknown or expired |
| `GET`  | `/analytics/hit-count/{linkId}` | Returns visit statistics for a link | `200` with `{ linkId, totalHits, lastAccessed }` |

### Examples

Create a link that expires in one hour:

```bash
curl -i -X POST http://localhost:8080/create -H "Content-Type: application/json" -d '{"url":"https://example.com/some/very/long/path","ttlInSeconds":3600}'
```

```json
{
  "shortKey": "NhU",
  "shortUrl": "http://localhost:8080/NhU",
  "createdAt": "2026-10-13T10:15:30",
  "expiresAt": "2026-10-13T11:15:30"
}
```

Follow the short link (shows the `302` and `Location` header):

```bash
curl -i http://localhost:8080/<shortKey>
```

Get the visit statistics:

```bash
curl http://localhost:8080/analytics/hit-count/<shortKey>
```

### Other endpoints

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI spec: `http://localhost:8080/v3/api-docs`
- H2 console: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:url-shortener-db`, user `sa`, password `password`)
- Health check: `http://localhost:8080/actuator/health`

### Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `url.shortener.host` | `http://localhost:8080` | Base URL used to build `shortUrl` in the create response |
| `spring.threads.virtual.enabled` | `true` | Handle requests and `@Async` events on virtual threads |

## Tech stack

**Demo implementation (this repository)**

- **Java 25**, with virtual threads enabled
- **Spring Boot 4.1.1**: Web MVC, Data JPA, Actuator, application events with `@Async`, `@Retryable` (Spring Framework resilience)
- **H2** in-memory database, created from `src/main/resources/schema.sql`
- **springdoc-openapi 3.1.0** for Swagger UI
- **Lombok**
- **Maven** to build
- **JUnit 5 / Spring Boot Test** for tests

**Target architecture (designed, not yet implemented):** API Gateway with OIDC/JWT authentication and Redis-backed rate limiting, horizontally scaled Link Service, Apache Cassandra with per-record TTL, and Apache Kafka for analytics events. See [01-architecture.md](01-architecture.md).

## Project layout

```
.
├── 01-architecture.md            # Target production architecture
├── 01-scope-green-field.md       # MVP features and progress
├── 02-scope-brown-field.md       # Bugs, refactors and enhancements on the current code
├── 03-scope-ambiguous.md         # Ambiguous requirements and their decisions
├── 04-test-plan.md               # Functional and non-functional (load, chaos, security) tests
├── 05-limitation-trade-off.md    # Demo limitations and design trade-offs
└── src/main/java/io/amitpramanik1986/url/shortener/
    ├── api/         # Link endpoints, request/response payloads, UrlShortService, LinkEntity/Repository, exception handler
    ├── analytics/   # Event consumer, hit-count entity/repository, analytics service and endpoint
    └── core/        # BaseConverter (key encoding), link events, LinkNotFoundException
```

## How to run locally

**Prerequisites:** JDK 25 and Maven 3.9+ (the project has no Maven wrapper).

1. Build and run the tests:
   ```bash
   mvn clean verify
   ```
2. Start the application:
   ```bash
   mvn spring-boot:run
   ```
   Or run the packaged jar:
   ```bash
   java -jar target/url-shortener-0.0.1-SNAPSHOT.jar
   ```
3. The service starts on `http://localhost:8080`. Open the Swagger UI to try the APIs.

The database is in memory, so all links and statistics are lost when the application stops.

## Limitations of the demo

The demo deliberately leaves out parts of the target architecture to keep it runnable on one machine. Details and the path forward are in [05-limitation-trade-off.md](05-limitation-trade-off.md).

- **H2 instead of Cassandra / DynamoDB:** data isn't durable, runs on a single node, and expired rows are hidden but never deleted.
- **No Kafka:** analytics events exist only in memory and can be lost on a crash or restart.
- **No API Gateway, rate limiting or authentication:** every endpoint is public and anyone can create unlimited links. Run it only on a trusted network.

## Known issues

These are tracked with fixes in [02-scope-brown-field.md](02-scope-brown-field.md):

- The redirect adds `?attribute=redirectWithRedirectPrefix` to the target URL, and target URLs containing `{…}` fail (BUG-01, BUG-02).
- Client errors such as malformed JSON or a missing `url` return `503` instead of `400`. URLs aren't validated (BUG-03, C4).
- A zero or negative `ttlInSeconds` is treated as "never expires" instead of being rejected (BUG-08).
- An unknown ID on the analytics endpoint returns `500` instead of `404` (BUG-04).

## Documentation

| Document | Contents |
|----------|----------|
| [01-architecture.md](01-architecture.md) | Multi-tier target architecture: actors, API Gateway, Link Service, Cassandra data model and TTL, Kafka analytics, failure modes, migration plan |
| [01-scope-green-field.md](01-scope-green-field.md) | Must-have features (core APIs, analytics, reliability) with their current status |
| [02-scope-brown-field.md](02-scope-brown-field.md) | Bug fixes, refactor and enhancement proposals, roadmap |
| [03-scope-ambiguous.md](03-scope-ambiguous.md) | Ambiguous points in the requirements, with state (RESOLVED/UNRESOLVED) and resolution date |
| [04-test-plan.md](04-test-plan.md) | Functional test cases, load/stress/soak tests (k6), resilience and security tests |
| [05-limitation-trade-off.md](05-limitation-trade-off.md) | Limitations of the demo and the main architectural trade-offs |
