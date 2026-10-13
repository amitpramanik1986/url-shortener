# 01 – Scope: Building the URL Shortener from Scratch

## Goal

Build a URL shortener service with three groups of features:

- **Core APIs:** create short links and redirect from them
- **Analytics:** count and report how links are used
- **Reliability:** behave correctly under load, on failures and on bad input

This document lists the **minimum must-have features** (MVP). Anything not listed here is out of scope for the first version (see [Out of scope](#out-of-scope-for-mvp)).

Each feature has a **Status** showing where the current code stands: ✅ done, ⚠️ partial or buggy, ❌ missing.

---

## 1. Core APIs

| # | Feature | Must-have behaviour | Status |
|---|---------|---------------------|--------|
| C1 | **Create short link** | `POST /api/links` takes `{ "url": "..." }` and returns `201` with `{ shortKey, shortUrl, createdAt, expiresAt }` | ⚠️ Now mapped to `POST /create` and returns `201`. It still takes form data (`inputUrl`, optional `timeToLiveInSeconds`) and returns only the key as plain text |
| C2 | **Unique short key generation** | Every URL gets a short, collision-free key. Use a DB sequence or ID encoded in Base62. Keys should be at most 7 characters and URL-safe | ⚠️ The alphabet now uses only RFC 3986 unreserved characters (`0-9A-Za-z-_.~`). It still includes `.` and `~`, which can cause problems (e.g. a key of `.` or a trailing dot). Base62 is safer |
| C3 | **Redirect** | `GET /{shortKey}` returns `302` with a `Location` header pointing to the original URL | ✅ |
| C4 | **Input validation** | Accept only well-formed `http`/`https` URLs, with a maximum length (e.g. 2048). Otherwise return `400` | ❌ |
| C5 | **Not found / invalid key** | An unknown key, or one with invalid characters, returns `404` with an RFC 7807 `ProblemDetail` | ⚠️ Unknown keys now return `404` with a `ProblemDetail`. A key with characters outside the alphabet makes `BaseConverter.toDecimal` throw an NPE, so it returns `503` instead of `404`. Analytics endpoints aren't covered by the handler |
| C6 | **Link expiry** | An optional `expiresAt` when creating a link. Expired links return `410 Gone` | ⚠️ `timeToLiveInSeconds` sets `expiredDate`, and expired links are no longer redirected (`LinkEntity.isNotExpired`). They return `404` instead of `410 Gone` |
| C7 | **Get link metadata** | `GET /api/links/{shortKey}` returns the original URL, created time and expiry, without redirecting | ❌ |
| C8 | **Delete / disable link** | `DELETE /api/links/{shortKey}` disables a link (soft delete) | ❌ |

## 2. Analytics

| # | Feature | Must-have behaviour | Status |
|---|---------|---------------------|--------|
| A1 | **Click counting** | Every successful redirect increments the link's total hit count | ⚠️ Concurrent hits fail the optimistic-lock check and are lost |
| A2 | **Last accessed time** | Store when the link was last visited | ✅ |
| A3 | **Non-blocking capture** | Recording analytics must never slow down or break a redirect. Do it asynchronously through events | ✅ `@Async` event listener |
| A4 | **Accurate under concurrency** | Use an atomic `UPDATE ... SET hit_count = hit_count + 1` or batched counters instead of read-modify-write | ❌ |
| A5 | **Stats API** | `GET /api/links/{shortKey}/stats` returns `{ totalHits, lastAccessed, createdAt }` | ⚠️ It exists at `/analytics/hit-count/{id}` and returns `{ linkId, totalHits, lastAccessed }`. It's missing `createdAt`, and an unknown ID isn't mapped to `404` |
| A6 | **Basic click details** | For each click, store the timestamp, referrer, user agent and country/IP (anonymised). This enables daily counts and top referrers | ❌ |
| A7 | **Time-series view** | Hits per day for a link (e.g. last 30 days) | ❌ |

## 3. Reliability

| # | Feature | Must-have behaviour | Status |
|---|---------|---------------------|--------|
| R1 | **Persistent storage** | Store data in a durable database (e.g. PostgreSQL) with schema migrations (Flyway/Liquibase) | ❌ Uses in-memory H2 |
| R2 | **Correct HTTP status codes** | Return `201`, `302`, `400`, `404`, `410`, `429` and `5xx` correctly. Never return `200` for an error | ⚠️ `201`, `302`, `400` (bad TTL), `404` and `503` are now correct. `410` and `429` are missing, and an invalid key gives `503` |
| R3 | **Global error handling** | Consistent `ProblemDetail` responses that don't leak stack traces | ⚠️ Status codes are fixed. The advice is limited to `LinkApiEndpoints`, so analytics errors fall back to Spring's default response. The `400` for a bad TTL is plain text, not a `ProblemDetail` |
| R4 | **Read caching** | Cache `shortKey → url` (Caffeine, or Redis for multiple instances) so redirects don't hit the DB every time | ❌ |
| R5 | **Rate limiting** | Limit link creation per client/IP (e.g. Bucket4j) and return `429` with `Retry-After` | ❌ |
| R6 | **Idempotent create (optional dedupe)** | Shortening the same URL again can return the existing key, or accept an `Idempotency-Key` header | ❌ |
| R7 | **Retry / resilience** | Retry transient DB failures. Analytics failures are logged and never fail the redirect | ⚠️ |
| R8 | **Health & readiness** | `/actuator/health` with liveness and readiness probes that include the DB | ✅ Basic health |
| R9 | **Observability** | Structured logs, plus metrics for request rate, latency, error rate and redirect count (Micrometer/Prometheus) | ❌ |
| R10 | **Graceful shutdown** | Finish in-flight requests and async events before stopping | ⚠️ A timeout is configured, but `server.shutdown=graceful` is not set |
| R11 | **Security basics** | Block `javascript:`/`data:` schemes and optionally check against a malicious-URL blocklist. Add CORS and secure headers | ❌ |
| R12 | **Automated tests** | Unit tests (encoder, service), integration tests (APIs + DB) and a concurrency test for hit counting | ⚠️ Only the encoder test exists |

---

## Progress summary

| Area | ✅ Done | ⚠️ Partial | ❌ Missing |
|------|--------|-----------|-----------|
| Core APIs (C1–C8) | 1 | 4 | 3 |
| Analytics (A1–A7) | 2 | 2 | 3 |
| Reliability (R1–R12) | 1 | 5 | 6 |

**Changed since the first assessment:** C1, C2, C5, C6, A5 notes updated; R2 moved from ❌ to ⚠️.

**Next highest-value items:**
1. Return `410 Gone` for expired links (C6) and `404` for invalid keys (C5).
2. Use an atomic hit-count update (A1, A4).
3. Validate input URLs (C4, R11).
4. Move to PostgreSQL + Flyway (R1).
5. Add integration tests for create, redirect, expiry and stats (R12).

## Out of scope for MVP

- User accounts, authentication and per-user dashboards
- Custom aliases / vanity URLs
- QR code generation
- Bulk link creation
- A full analytics pipeline (Kafka, ClickHouse, etc.)
- Multi-region deployment

## Definition of done (MVP)

1. All **C1–C6**, **A1–A5** and **R1–R5, R8, R12** are implemented and pass their tests.
2. Redirects stay fast (p95 < 50 ms with the cache warm) and correct under concurrent load.
3. Hit counts are exact when 1,000 concurrent requests hit the same link.
4. The service survives a restart without losing links or statistics.
