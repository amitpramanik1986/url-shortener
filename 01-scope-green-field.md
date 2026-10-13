# 01 – Scope: Building the URL Shortener from Scratch

## Goal

Build a URL shortener service with three groups of features:

- **Core APIs:** create short links and redirect from them
- **Analytics:** count and report how links are used
- **Reliability:** behave correctly under load, on failures and on bad input

This document lists the **minimum must-have features** (MVP). Anything not listed here is out of scope for the first version (see [Out of scope](#out-of-scope-for-mvp)). Bugs, refactors and enhancements on the existing code are tracked in [02-scope-brown-field.md](02-scope-brown-field.md).

Each feature has a **Status** showing where the current code stands: ✅ done, ⚠️ partial or buggy, ❌ missing.

_Last assessed against the code: 2026-10-12 (commit `c2da8a8`)._

---

## 1. Core APIs

| # | Feature | Must-have behaviour | Status |
|---|---------|---------------------|--------|
| C1 | **Create short link** | `POST /api/links` takes `{ "url": "..." }` and returns `201` with `{ shortKey, shortUrl, createdAt, expiresAt }` | ✅ `POST /create` takes JSON `{ url, ttlInSeconds }` and returns `201` with `{ shortKey, shortUrl, createdAt, expiresAt }`. The path differs from the target (`/api/links`) |
| C2 | **Unique short key generation** | Every URL gets a short, collision-free key. Use a DB sequence or ID encoded in Base62. Keys should be at most 7 characters and URL-safe | ⚠️ Sequence + 66-character alphabet (`0-9A-Za-z-_.~`), keys capped at 10 characters. `.` and `~` are still in the alphabet. Keys with leading `0` decode to the same ID, so `abc` and `0abc` point to the same link ([BUG-07](02-scope-brown-field.md#1-bug-fixes)) |
| C3 | **Redirect** | `GET /{shortKey}` returns `302` with a `Location` header pointing to the original URL | ⚠️ Returns `302`, but the model attribute is added to the target as `?attribute=redirectWithRedirectPrefix`, and URLs containing `{…}` fail ([BUG-01](02-scope-brown-field.md#1-bug-fixes), [BUG-02](02-scope-brown-field.md#1-bug-fixes)) |
| C4 | **Input validation** | Accept only well-formed `http`/`https` URLs, with a maximum length (e.g. 2048). Otherwise return `400` | ❌ No validation dependency or `@Valid`. A missing `url` or one longer than 1000 characters fails at the DB and returns `503` |
| C5 | **Not found / invalid key** | An unknown key, or one with invalid characters, returns `404` with an RFC 7807 `ProblemDetail` | ⚠️ Unknown keys return a `404` `ProblemDetail`. Keys outside `[0-9A-Za-z_.~-]{1,10}` don't match the route, so Spring returns its own default `404` body instead of a `ProblemDetail` |
| C6 | **Link expiry** | An optional `expiresAt` when creating a link. Expired links return `410 Gone` | ⚠️ `ttlInSeconds` sets `expiredDate` and expired links aren't redirected. They return `404`, not `410`. A zero or negative TTL is silently ignored (the earlier `400` check was removed) |
| C7 | **Get link metadata** | `GET /api/links/{shortKey}` returns the original URL, created time and expiry, without redirecting | ❌ |
| C8 | **Delete / disable link** | `DELETE /api/links/{shortKey}` disables a link (soft delete) | ❌ |

## 2. Analytics

| # | Feature | Must-have behaviour | Status |
|---|---------|---------------------|--------|
| A1 | **Click counting** | Every successful redirect increments the link's total hit count | ✅ Uses an atomic `UPDATE`. A hit can still be lost if it arrives before the async "created" event has inserted the row ([BUG-05](02-scope-brown-field.md#1-bug-fixes)) |
| A2 | **Last accessed time** | Store when the link was last visited | ✅ `UPDATED_DATE` is set on each hit. A link that was never visited reports its creation time as `lastAccessed` |
| A3 | **Non-blocking capture** | Recording analytics must never slow down or break a redirect. Do it asynchronously through events | ✅ `@Async` event listener |
| A4 | **Accurate under concurrency** | Use an atomic `UPDATE ... SET hit_count = hit_count + 1` or batched counters instead of read-modify-write | ✅ `LinkHitsRepository.incrementHitCount` (not yet covered by a concurrency test) |
| A5 | **Stats API** | `GET /api/links/{shortKey}/stats` returns `{ totalHits, lastAccessed, createdAt }` | ⚠️ `GET /analytics/hit-count/{id}` returns `{ linkId, totalHits, lastAccessed }`. It's missing `createdAt`, and an unknown ID returns `500` instead of `404` ([BUG-04](02-scope-brown-field.md#1-bug-fixes)) |
| A6 | **Basic click details** | For each click, store the timestamp, referrer, user agent and country/IP (anonymised). This enables daily counts and top referrers | ❌ `CustomerActivityEntity` exists only as an empty stub |
| A7 | **Time-series view** | Hits per day for a link (e.g. last 30 days) | ❌ |

## 3. Reliability

| # | Feature | Must-have behaviour | Status |
|---|---------|---------------------|--------|
| R1 | **Persistent storage** | Store data in a durable database (e.g. PostgreSQL) with schema migrations (Flyway/Liquibase) | ❌ Uses in-memory H2 |
| R2 | **Correct HTTP status codes** | Return `201`, `302`, `400`, `404`, `410`, `429` and `5xx` correctly. Never return `200` for an error | ⚠️ `201`, `302` and `404` are correct. Client errors such as malformed JSON or a missing `url` return `503` ([BUG-03](02-scope-brown-field.md#1-bug-fixes)). There's no `400` validation, `410` or `429` |
| R3 | **Global error handling** | Consistent `ProblemDetail` responses that don't leak stack traces | ⚠️ `ProblemDetail` is used, but the advice covers only `LinkApiEndpoints` and maps every unexpected exception to `503` |
| R4 | **Read caching** | Cache `shortKey → url` (Caffeine, or Redis for multiple instances) so redirects don't hit the DB every time | ❌ |
| R5 | **Rate limiting** | Limit link creation per client/IP (e.g. Bucket4j) and return `429` with `Retry-After` | ❌ |
| R6 | **Idempotent create (optional dedupe)** | Shortening the same URL again can return the existing key, or accept an `Idempotency-Key` header | ❌ |
| R7 | **Retry / resilience** | Retry transient DB failures. Analytics failures are logged and never fail the redirect | ⚠️ `@Retryable` has been added, but it does nothing because `@EnableResilientMethods` isn't on any configuration class ([BUG-06](02-scope-brown-field.md#1-bug-fixes)). Analytics failures don't affect redirects |
| R8 | **Health & readiness** | `/actuator/health` with liveness and readiness probes that include the DB | ✅ Basic health (liveness/readiness groups are not configured) |
| R9 | **Observability** | Structured logs, plus metrics for request rate, latency, error rate and redirect count (Micrometer/Prometheus) | ❌ |
| R10 | **Graceful shutdown** | Finish in-flight requests and async events before stopping | ⚠️ A timeout is configured, but `server.shutdown=graceful` and waiting for the async executor to finish are not set |
| R11 | **Security basics** | Block `javascript:`/`data:` schemes and optionally check against a malicious-URL blocklist. Add CORS and secure headers | ❌ Any string is accepted as a redirect target. The H2 console is enabled with a password stored in the config file |
| R12 | **Automated tests** | Unit tests (encoder, service), integration tests (APIs + DB) and a concurrency test for hit counting | ⚠️ Only `BaseConverterTest` and a context-load test exist |

---

## Progress summary

| Area | ✅ Done | ⚠️ Partial | ❌ Missing |
|------|--------|-----------|-----------|
| Core APIs (C1–C8) | 1 | 4 | 3 |
| Analytics (A1–A7) | 4 | 1 | 2 |
| Reliability (R1–R12) | 1 | 5 | 6 |
| **Total (27)** | **6** | **10** | **11** |

**Changes since the last assessment:**

- ⬆️ **C1** ⚠️ → ✅: JSON request/response with `shortUrl`, `createdAt` and `expiresAt`.
- ⬆️ **A1, A4** → ✅: atomic `UPDATE` for the hit count.
- ⬆️ **C5:** keys with invalid characters no longer crash. The route regex rejects them.
- ⬇️ **C3** ✅ → ⚠️: the redirect leaks a model attribute into the target URL ([BUG-01](02-scope-brown-field.md#1-bug-fixes)).
- ⬇️ **C6:** the `400` for an invalid TTL was lost when the API moved to JSON.
- **R7:** `@Retryable` was added but isn't enabled.

**Next highest-value items:**

1. Fix the redirect: use `ResponseEntity` with a `Location` header and no model attributes (C3, BUG-01/02).
2. Add `spring-boot-starter-validation` and validate `url`/`ttlInSeconds`, returning `400` (C4, C6, R2).
3. Return `410 Gone` for expired links (C6).
4. Map client errors to `4xx` and widen the exception handler to every controller (R2, R3).
5. Add integration tests for create, redirect, expiry, stats and concurrent hits (R12).
6. Move to PostgreSQL + Flyway (R1).

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
