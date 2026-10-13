# URL Shortener

A Spring Boot service that turns long URLs into short link IDs, redirects visitors from a short link to the original URL, and counts how often each link is visited.

## What it does

- **Shorten:** Saves the full URL with an ID taken from a database sequence (starting at 100000). It then encodes that ID into a short key using a 67-character alphabet (`0-9 A-Z a-z - _ ! . $`).
- **Redirect:** Decodes the short key back to the ID, looks up the full URL and responds with an HTTP 302 redirect.
- **Analytics:** Creating or visiting a link publishes an application event. An `@Async` listener handles each event and keeps a hit count and last-accessed time for every short link.

## Core APIs

| Method | Path | Description | Response |
|--------|------|-------------|----------|
| `POST` | `/` | Creates a short link. Send the URL as form field `inputUrl` (`application/x-www-form-urlencoded`). | `201 Created`, with the short link ID as plain text |
| `GET`  | `/{key}` | Redirects to the original URL. | `302 Found`, or a `ProblemDetail` if the key is unknown |
| `GET`  | `/analytics/hit-count/{linkId}` | Returns visit statistics for a link. | `{ "linkId", "totalHits", "lastAccessed" }` |

> Note: the create endpoint is declared as `@PostMapping(name = "/create")`. `name` only labels the mapping, so the endpoint is served at `/`. Change it to `value = "/create"` if you want it at `/create`.

Examples:

```bash
curl -X POST -d "inputUrl=https://example.com/some/very/long/path" http://localhost:8080/
```

```bash
curl -i http://localhost:8080/<shortKey>
```

```bash
curl http://localhost:8080/analytics/hit-count/<shortKey>
```

### Other endpoints

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI spec: `http://localhost:8080/v3/api-docs`
- H2 console: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:url-shortener-db`, user `sa`, password `password`)
- Health check: `http://localhost:8080/actuator/health`

## Tech stack

- **Java 25**, with virtual threads enabled
- **Spring Boot 4.1.1**: Web MVC, Data JPA, Actuator, application events with `@Async`
- **H2** in-memory database, created from `src/main/resources/schema.sql`
- **springdoc-openapi 3.1.0** for Swagger UI
- **Lombok**
- **Maven** to build
- **JUnit / Spring Boot Test** for tests

## Project layout

```
src/main/java/io/amitpramanik1986/url/shortener/
├── api/         # Link endpoints, UrlShortService, LinkEntity/Repository, exception handler
├── analytics/   # Event consumer, hit-count entity/repository, analytics endpoint
└── core/        # BaseConverter, link events, LinkNotFoundException
```

## How to run locally

**Prerequisites:** JDK 25 and Maven 3.9+.

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
