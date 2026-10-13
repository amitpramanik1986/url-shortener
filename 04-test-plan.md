# 04 – Test Plan: URL Shortener

| | |
|---|---|
| **Status** | Draft |
| **Date** | 2026-10-13 |
| **Covers** | Functional requirements ([01-scope-green-field.md](01-scope-green-field.md)), bug fixes ([02-scope-brown-field.md](02-scope-brown-field.md)), resolved decisions ([03-scope-ambiguous.md](03-scope-ambiguous.md)) and non-functional targets ([01-architecture.md](01-architecture.md)) |

---

## 1. Scope

The plan covers two stages of the system:

| Stage | System under test | Purpose |
|-------|------------------|---------|
| **Stage A – Current service** | Single Spring Boot app, H2, in-process `@Async` events (`POST /create`, `GET /{key}`, `GET /analytics/hit-count/{id}`) | Prove the MVP behaviour and catch regressions while bugs are fixed |
| **Stage B – Target architecture** | API Gateway + IdP + Redis rate limiter, Link Service pods, Cassandra, Kafka, Analytics Processor (`/api/v1/...`) | Prove authentication, rate limiting, TTL clean-up, scaling and no event loss |

Test cases marked **A** apply to Stage A, **B** to Stage B, and **A/B** to both (with the path changed to the target API).

**Expected behaviour comes from these resolved decisions:**

| Decision | Source |
|----------|--------|
| Redirect returns `302 Found` with a `Location` header | AMB-08 |
| Expired links return `404 Not Found`, the same as unknown keys | AMB-07 |
| Keys match `[0-9A-Za-z_~-]{1,10}` | AMB-02, AMB-03 |
| `ttlInSeconds` is optional; no TTL means the link never expires | AMB-04, AMB-06 (assumption) |
| Analytics are asynchronous; no event loss | AMB-14, AMB-25 (assumption) |

> **Note:** C6 in the green-field doc still says `410` and EN-01 in the brown-field doc proposes it. This plan follows AMB-07 (`404`). Those two documents should be updated to match.

**Out of scope:** UI testing of the client and admin consoles, IdP internals, and post-MVP features (custom aliases, QR codes, bulk create).

---

## 2. Test levels and tools

| Level | What it proves | Tools | Runs |
|-------|----------------|-------|------|
| **Unit** | Encoder, entity and service logic in isolation | JUnit 5, AssertJ, Mockito, `@ParameterizedTest` | Every build |
| **Slice / integration** | Controller + service + repository against a real DB | `@SpringBootTest` + MockMvc / `RestTestClient`, H2 (A), **Testcontainers** Cassandra / Kafka / Redis (B), **Awaitility** for async results | Every build |
| **Contract** | API responses match the published OpenAPI spec | springdoc `/v3/api-docs` + schema validation (e.g. `swagger-request-validator`) | Every build |
| **End-to-end** | The full path through the gateway with real tokens | REST Assured / Karate against a staging environment | Every deployment to staging |
| **Performance** | Throughput, latency, scaling | **k6** (or Gatling) + Prometheus/Grafana | Nightly (smoke), before each release (full) |
| **Resilience / chaos** | Behaviour when dependencies fail | Toxiproxy (local), Chaos Mesh / AWS FIS (staging) | Before each release |
| **Security** | Authentication, authorization, input abuse | OWASP ZAP, custom negative tests, dependency scanning (OWASP Dependency-Check / Trivy) | Weekly + before each release |

---

## 3. Environments and test data

| Environment | Composition | Used for |
|-------------|-------------|----------|
| **Local / CI** | App + H2 (A); Testcontainers Cassandra 5, Kafka (KRaft), Redis (B) | Unit, integration, contract, small resilience tests |
| **Staging (perf)** | Production-like: 3 AZs, 3+ Link Service pods, 6-node Cassandra (RF=3), 3 Kafka brokers, gateway + Redis | E2E, load, chaos, security |

**Test data**

- **Seed links:** 1 M links for load tests (staging), generated with a mix of TTLs: 60 % no TTL, 30 % 1–30 days, 10 % 60 s (so some expire during the test).
- **Key popularity:** Zipf distribution (s ≈ 1.1), so a few "hot" keys get most of the traffic. This is realistic for viral links and exercises Cassandra partition hot spots.
- **Users:** Test IdP clients for each tier: `client-free`, `client-pro`, `admin`, plus an expired token and a token with a forged signature.
- **URLs:** Valid `http`/`https`; boundary lengths (2048, 2049); disallowed schemes (`javascript:`, `data:`, `file:`); URLs containing `{}`, `%`, Unicode and query strings.

---

## 4. Functional tests

Each case lists its requirement or bug reference. **Today** shows the expected result against the current code (commit `31071d1`): ✅ should pass, ❌ expected to fail because of a known bug, ⏳ the feature doesn't exist yet. Cases expected to fail are kept so they become regression tests once the bug is fixed.

### 4.1 Create short link

| ID | Ref | Scenario | Input | Expected result | Stage | Pri | Today |
|----|-----|----------|-------|-----------------|-------|-----|-------|
| FT-C-01 | C1 | Create without TTL | `{"url":"https://example.com/a"}` | `201`; body has `shortKey` matching the key regex, `shortUrl = host + "/" + shortKey`, `createdAt` set, `expiresAt = null` | A/B | High | ✅ |
| FT-C-02 | C1, AMB-04 | Create with TTL | `{"url":"https://example.com","ttlInSeconds":3600}` | `201`; `expiresAt = createdAt + 3600 s` (±1 s) | A/B | High | ✅ |
| FT-C-03 | C2 | Keys are unique | Create 10,000 links (also in parallel from 50 threads) | 10,000 distinct `shortKey`s | A/B | High | ✅ |
| FT-C-04 | C2, AMB-03 | Key length | Create links until the ID crosses a length boundary (seed the sequence near 66ⁿ) | Every key is ≤ 10 characters and decodes back to its ID | A | Med | ✅ |
| FT-C-05 | C4, BUG-03 | Missing `url` | `{}` | `400` `ProblemDetail` | A/B | High | ❌ returns `503` |
| FT-C-06 | C4 | Blank / malformed URL | `{"url":""}`, `{"url":"not a url"}` | `400` | A/B | High | ❌ accepted, or `503` |
| FT-C-07 | C4, R11 | Disallowed scheme | `javascript:alert(1)`, `data:text/html,…`, `file:///etc/passwd` | `400` | A/B | High | ❌ accepted |
| FT-C-08 | C4, BUG-09 | URL length boundary | 2048 characters / 2049 characters | `201` / `400` | A/B | Med | ❌ fails above 1000 with `503` |
| FT-C-09 | C6, BUG-08 | Invalid TTL | `ttlInSeconds` = `0`, `-1`, `"abc"` | `400` | A/B | High | ❌ `0`/`-1` silently mean "never expires" |
| FT-C-10 | BUG-03 | Malformed JSON | `{"url":` | `400` | A/B | High | ❌ `503` |
| FT-C-11 | — | Wrong content type | `text/plain` body | `415` | A/B | Low | ✅ |
| FT-C-12 | R11 | Self-referencing link | `url = http://<own-host>/abc` | `400` (prevents redirect loops) | B | Low | ⏳ |
| FT-C-13 | A3 | Create publishes an event | Create a link | Within 5 s, the stats endpoint returns `totalHits = 0` for the new key | A/B | Med | ✅ |

### 4.2 Redirect

| ID | Ref | Scenario | Input | Expected result | Stage | Pri | Today |
|----|-----|----------|-------|-----------------|-------|-----|-------|
| FT-R-01 | C3, AMB-08 | Redirect an active link | `GET /{key}` | `302`; `Location` header equals the original URL **exactly** | A/B | High | ❌ `?attribute=redirectWithRedirectPrefix` is appended (BUG-01) |
| FT-R-02 | C3 | Original URL has a query string and fragment | `https://e.com/p?q=1&r=2#top` | `Location` is unchanged, with no extra parameters | A/B | High | ❌ BUG-01 |
| FT-R-03 | C3, BUG-02 | Original URL contains `{…}` | `https://e.com/p?json={"a":1}` | `302` with the exact URL | A/B | Med | ❌ `503` |
| FT-R-04 | C3 | Unicode / percent-encoded URL | `https://e.com/caf%C3%A9` | `Location` is correctly encoded | A/B | Med | Verify |
| FT-R-05 | C5 | Unknown key (valid format) | `GET /zzzzzz` | `404` `ProblemDetail` | A/B | High | ✅ |
| FT-R-06 | C5, AMB-02 | Key with invalid characters | `GET /abc*`, `GET /a%20b`, `GET /ab.c` | `404` | A/B | High | ✅ (Spring default body, not `ProblemDetail`) |
| FT-R-07 | C5, AMB-03 | Key longer than 10 characters | `GET /abcdefghijk` | `404` | A/B | Med | ✅ |
| FT-R-08 | C2, BUG-07 | Leading-zero alias | Create a link with key `K`; `GET /0K` | `404` (aliases are not valid) | A/B | Med | ❌ redirects to the same link |
| FT-R-09 | — | Reserved paths aren't treated as keys | `GET /actuator/health`, `/swagger-ui.html`, `/create` (GET) | Served by their own handlers, or `405` for `GET /create`. Never a link lookup | A | Low | ✅ |
| FT-R-10 | C8 | Disabled link | Disable the link, then `GET /{key}` | `404` | B | Med | ⏳ |

### 4.3 Expiry (TTL)

| ID | Ref | Scenario | Input | Expected result | Stage | Pri | Today |
|----|-----|----------|-------|-----------------|-------|-----|-------|
| FT-E-01 | C6 | Link before expiry | TTL 5 s; `GET` at t = 1 s | `302` | A/B | High | ✅ |
| FT-E-02 | C6, AMB-07 | Link after expiry | TTL 2 s; `GET` at t = 3 s | **`404`** | A/B | High | ✅ |
| FT-E-03 | C6 | Expiry boundary | TTL 2 s; `GET` at exactly `expiresAt` | `404` (expiry is exclusive: `expiredDate.isAfter(now)`) | A | Low | ✅ |
| FT-E-04 | AMB-38 | Automatic DB clean-up | Insert with `USING TTL 2`; wait 3 s; `SELECT` at `LOCAL_QUORUM` | Row isn't returned. After compaction + `gc_grace_seconds`, the SSTable no longer holds the row | B | High | ⏳ |
| FT-E-05 | AMB-38 | TTL applied to every table | Create with TTL; check `TTL(long_url)` on `links_by_key` **and** `links_by_owner` | Both are within 2 s of the requested TTL | B | Med | ⏳ |
| FT-E-06 | C6 | Expired link isn't counted | Hit an expired key | `404`; hit count unchanged | A/B | Med | ✅ |
| FT-E-07 | RF-08 | Time-zone independence | Run FT-E-02 with the JVM in `UTC` and `Asia/Kolkata` | Same result | A | Low | Verify |

### 4.4 Analytics

| ID | Ref | Scenario | Input | Expected result | Stage | Pri | Today |
|----|-----|----------|-------|-----------------|-------|-----|-------|
| FT-A-01 | A1 | Hit counting | Create, then redirect 5 times | Eventually `totalHits = 5` (Awaitility, ≤ 5 s) | A/B | High | ✅ |
| FT-A-02 | A2, BUG-12 | Last accessed time | Redirect at time T | `lastAccessed ≈ T`. Before any hit: `null` | A/B | Med | ❌ returns the creation time before the first hit |
| FT-A-03 | A4 | **Concurrent hits are exact** | 1,000 parallel redirects to one key (100 threads) | `totalHits = 1000` exactly | A/B | High | ✅ (atomic update), needs a test |
| FT-A-04 | A1, BUG-05 | Hit immediately after create | Create and redirect with no delay, ×500 | No lost hits: total = 500 | A/B | High | ❌ hits can arrive before the async insert |
| FT-A-05 | A5, BUG-04 | Stats for an unknown key | `GET /analytics/hit-count/nope` | `404` `ProblemDetail` | A/B | Med | ❌ `500` |
| FT-A-06 | A5 | Stats response shape | Stats for an existing key | `{ linkId, totalHits, lastAccessed, createdAt }` | A/B | Med | ❌ `createdAt` missing |
| FT-A-07 | A3 | Analytics failures don't affect redirects | Make the analytics repository throw (test double / Toxiproxy) | Redirect still returns `302` within its latency budget | A/B | High | ✅ |
| FT-A-08 | AMB-20 | Failed lookups aren't counted | `GET` unknown / expired / invalid keys | No hit events recorded | A/B | Low | ✅ |
| FT-A-09 | A7 | Daily time series | Hits spread across 3 days (fake `Clock`) | Correct count per day | B | Med | ⏳ |
| FT-A-10 | AMB-14 | Event schema | Consume `link.created.v1` / `link.accessed.v1` | Valid Avro against the registry; `eventId` is unique; the key is `short_key` | B | Med | ⏳ |

### 4.5 API Gateway: authentication, authorization and rate limiting (Stage B)

| ID | Ref | Scenario | Expected result | Pri |
|----|-----|----------|-----------------|-----|
| FT-G-01 | AMB-32 | Create without a token | `401` | High |
| FT-G-02 | AMB-32 | Expired token / forged signature / wrong `aud` | `401` | High |
| FT-G-03 | AMB-32 | Valid token without the `links:create` scope | `403` | High |
| FT-G-04 | AMB-31 | Client reads or disables **another client's** link | `403` | High |
| FT-G-05 | AMB-31 | Admin disables any link | `200`/`204` | High |
| FT-G-06 | AMB-34 | Redirect without a token | `302` (public) | High |
| FT-G-07 | §5.1 | Client sends its own `X-User-Id` header | Header is stripped and replaced with the token's identity | High |
| FT-G-08 | AMB-33 | Free tier: 21 creates in 1 min (burst 20) | 21st returns `429` with `Retry-After` and `X-RateLimit-*` headers | High |
| FT-G-09 | AMB-33 | Limit is shared across gateway instances: 21 creates round-robin over 3 gateway pods | Same limit as through one pod (shared Redis buckets) | High |
| FT-G-10 | AMB-29 | Per-IP redirect limit | > 100 redirects/s from one IP gets `429`; other IPs unaffected | Med |
| FT-G-11 | AMB-33 | Bucket refills | After `Retry-After` elapses, creates succeed again | Med |
| FT-G-12 | §5.3 | Admin tier | 1,000 creates/min succeed without `429` | Low |

### 4.6 Kafka event delivery (Stage B)

| ID | Ref | Scenario | Expected result | Pri |
|----|-----|----------|-----------------|-----|
| FT-K-01 | AMB-14 | Every create produces exactly one `link.created` event | Count of events = count of `201` responses | High |
| FT-K-02 | AMB-14 | Every successful redirect produces one `link.accessed` event | Count of events = count of `302` responses | High |
| FT-K-03 | §8.1 | Ordering per key | Events for one key are on the same partition, in order | Med |
| FT-K-04 | §8.3 | Duplicate delivery: replay the same events (reset consumer offsets) | Aggregates don't change (idempotent) | High |
| FT-K-05 | §8.3 | Poison message: publish a malformed event | Event is sent to `link.accessed.v1.dlq`; processing continues | Med |

---

## 5. Non-functional tests

### 5.1 Performance targets (SLOs)

Taken from [architecture §1.1](01-architecture.md#11-capacity-assumptions). They're **assumptions** until AMB-23 / AMB-24 are resolved.

| Metric | Target (Stage B, staging) | Target (Stage A, single instance, local) |
|--------|---------------------------|------------------------------------------|
| Redirect throughput | **100,000 req/s** sustained | Baseline only: record the maximum, no fixed target |
| Redirect latency | p95 < 20 ms, **p99 < 50 ms** end to end | p99 < 50 ms at 1,000 req/s |
| Create throughput | 1,000 req/s (peak) | 200 req/s |
| Create latency | p99 < 150 ms (includes Kafka ack) | p99 < 100 ms |
| Error rate | < 0.1 % 5xx (`429` excluded) | < 0.1 % |
| Analytics delay | Event visible in stats within 60 s (p99) | Within 5 s |
| Kafka consumer lag | Returns to < 10,000 messages within 5 min after load stops | n/a |

### 5.2 Workload model

| Parameter | Value |
|-----------|-------|
| Traffic mix | 99 % redirects, 1 % creates (100:1 ratio) |
| Key choice | Zipf over 1 M seeded keys; plus 2 % unknown keys and 1 % expired keys |
| Create payload | 70 % no TTL, 30 % random TTL (60 s – 30 days) |
| Clients | Spread across 50 test client IDs (to stay under per-client limits) and ≥ 1,000 source IPs (to stay under per-IP limits) |
| Think time | None (open model: constant arrival rate) |

### 5.3 Load test scenarios

| ID | Type | Profile | Duration | Pass criteria |
|----|------|---------|----------|---------------|
| NFT-P-01 | **Smoke** | 50 req/s | 2 min | 0 errors; run on every deployment |
| NFT-P-02 | **Baseline** | Step up 10 % → 100 % of target in 10 steps | 2 min per step | Find the knee of the latency curve; record capacity per pod |
| NFT-P-03 | **Load (peak)** | 100 % target (100 k redirects/s + 1 k creates/s) | 30 min | All SLOs in §5.1 met |
| NFT-P-04 | **Stress** | Increase by 20 % every 5 min beyond peak until failure | Until it breaks | Fails gracefully (`429`/`503`, no data corruption); recovers within 5 min once load drops; note the breaking point |
| NFT-P-05 | **Spike** | 10 % → 300 % within 30 s, hold 5 min, back to 10 % | 15 min | HPA scales out within 2 min; error rate < 1 % during the spike; no lost analytics events |
| NFT-P-06 | **Soak / endurance** | 60 % of target | **8 h** | No memory leak (heap and old-gen stable); p99 doesn't drift > 10 %; Cassandra tombstone warnings < threshold; Kafka lag stable |
| NFT-P-07 | **Hot key** | 30 % of all redirects go to **one** key | 15 min | p99 SLO still met; the 3 Cassandra replicas for that key stay < 70 % CPU |
| NFT-P-08 | **Create-heavy** | 1 k creates/s across 50 clients | 15 min | Keys unique; ID range leasing doesn't contend (LWT latency p99 < 50 ms); no `429` below each client's limit |
| NFT-P-09 | **Rate-limit at scale** | 10 clients each sending 5× their tier limit | 10 min | Accepted rate per client is within ±5 % of the configured limit; Redis latency p99 < 5 ms |
| NFT-P-10 | **Scalability** | Repeat NFT-P-03 with 3, 6, 12 Link Service pods | 3 × 15 min | Throughput scales ≥ 80 % linearly with the number of pods |

**Example k6 script (redirect + create mix)**

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const keys = new SharedArray('keys', () => JSON.parse(open('./seed-keys.json')));
const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    redirects: {
      executor: 'constant-arrival-rate', rate: 990, timeUnit: '1s',
      duration: '30m', preAllocatedVUs: 500, exec: 'redirect',
    },
    creates: {
      executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s',
      duration: '30m', preAllocatedVUs: 50, exec: 'create',
    },
  },
  thresholds: {
    'http_req_duration{scenario:redirects}': ['p(95)<20', 'p(99)<50'],
    'http_req_duration{scenario:creates}':   ['p(99)<150'],
    'http_req_failed':                       ['rate<0.001'],
  },
};

// Zipf-like choice: favour the start of the list
function pickKey() {
  const i = Math.floor(Math.pow(Math.random(), 3) * keys.length);
  return keys[i];
}

export function redirect() {
  const res = http.get(`${BASE}/${pickKey()}`, { redirects: 0, tags: { name: 'GET /{key}' } });
  check(res, { 'is 302': (r) => r.status === 302, 'has Location': (r) => !!r.headers['Location'] });
}

export function create() {
  const body = JSON.stringify({ url: `https://example.com/${__VU}/${__ITER}`, ttlInSeconds: 86400 });
  const res = http.post(`${BASE}/create`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${__ENV.TOKEN}` },
  });
  check(res, { 'is 201': (r) => r.status === 201 });
}
```

Scale `rate` to the target for each scenario. For Stage B, change the create path to `/api/v1/links` and run through the gateway. Run k6 from **separate load-generator machines**, never on the same hosts as the system under test.

**What to record in every run:** requests/s, p50/p95/p99/max latency per endpoint, error rate by status code, pod CPU and memory, JVM GC pauses, HikariCP / Cassandra driver pool usage, Cassandra read/write latency and tombstones scanned, Kafka producer `record-error-rate` and consumer lag, Redis latency, HPA replica count.

### 5.4 Reliability and resilience (chaos) tests

Each test runs under NFT-P-03 load at 50 %, unless noted otherwise.

| ID | Fault injected | Expected behaviour | Pass criteria |
|----|----------------|--------------------|---------------|
| NFT-R-01 | Kill 1 Link Service pod | Traffic moves to the other pods; a replacement starts | < 0.1 % failed requests; no duplicate keys after restart |
| NFT-R-02 | Rolling deployment | Graceful shutdown drains in-flight requests and flushes the Kafka producer | 0 failed requests; produced events = `302` count |
| NFT-R-03 | Stop 1 Cassandra node (of 3 replicas) | `LOCAL_QUORUM` still succeeds | No errors; p99 rises < 20 % |
| NFT-R-04 | Stop 2 Cassandra nodes holding the same token range | Writes at `LOCAL_QUORUM` fail; reads at `LOCAL_ONE` still work | Creates return `503` (not `500`); redirects keep working; full recovery after the nodes return |
| NFT-R-05 | Lose a whole AZ | Remaining AZs carry the load | Availability ≥ 99.9 % during the event; HPA scales up |
| NFT-R-06 | Stop 1 Kafka broker | Leader election; producers retry | 0 lost events (produced = consumed) |
| NFT-R-07 | **Stop the whole Kafka cluster for 10 min** | Redirects and creates continue; events are buffered in the outbox / disk spool | Redirect SLOs met; **after recovery, all buffered events are delivered** (count matches) |
| NFT-R-08 | Kill the Analytics Processor mid-stream | Consumers rebalance and resume from the committed offsets | Final counts exact (no loss, no double counting) |
| NFT-R-09 | Kill a Link Service pod with `kill -9` (no graceful shutdown) under redirect load | Some in-flight events may be lost from the producer buffer | Measure the loss. Must be 0 if the synchronous-send option is chosen (AMB-25) |
| NFT-R-10 | Redis (rate limiter) unavailable | Redirects unaffected; create falls back to a per-pod limit | No redirect errors; creates still limited |
| NFT-R-11 | IdP unavailable | Existing tokens keep working (cached JWKS) | No `401` for valid unexpired tokens |
| NFT-R-12 | Add 300 ms latency to Cassandra (Toxiproxy) | Timeouts and circuit breaker trip; no thread exhaustion | Service stays responsive (health check OK); errors are `503` not timeouts at the client |
| NFT-R-13 | Full restart of the whole system | Data is durable | All links and stats are still there (DoD #4 in 01-scope-green-field) |

**No-event-loss check (used by NFT-R-06 – R-09):** each test run tags its requests with a run ID. After the run, compare (a) the number of `201` / `302` responses the load generator recorded, (b) the number of events on the Kafka topics for that run ID, and (c) the totals in the analytics store. All three must be equal (except NFT-R-09 when loss is accepted).

### 5.5 Security tests

| ID | Area | Test | Expected result |
|----|------|------|-----------------|
| NFT-S-01 | Authentication | Requests with no token, an expired token, a forged signature, `alg=none`, or a token from another issuer | `401` |
| NFT-S-02 | Authorization | Horizontal access: one client reads, disables or views stats for another client's link (IDOR) | `403` |
| NFT-S-03 | Header spoofing | Client-supplied `X-User-Id` / `X-User-Roles` | Ignored (overwritten by the gateway) |
| NFT-S-04 | Malicious URLs | `javascript:`, `data:`, `vbscript:`, mixed-case `JaVaScRiPt:`, URLs with CR/LF (header injection) | `400`; no `Location` header injection |
| NFT-S-05 | Key enumeration | Scan sequential keys at a high rate from one IP | Per-IP limit triggers (`429`); alert raised |
| NFT-S-06 | Rate-limit bypass | Rotate `X-Forwarded-For`; spread requests across gateway pods | Limits still apply (trust only the edge-supplied client IP) |
| NFT-S-07 | Injection | CQL/SQL meta-characters and very long strings in `url` and the key path | `400`/`404`; no 5xx; no stack traces in responses |
| NFT-S-08 | Exposure | `/h2-console`, `/actuator/**` (other than health), Swagger in production | Not reachable from outside |
| NFT-S-09 | Scanning | OWASP ZAP baseline + API scan against the OpenAPI spec | No High findings |
| NFT-S-10 | Dependencies | OWASP Dependency-Check / Trivy on the build and container image | No unfixed Critical/High CVEs |
| NFT-S-11 | Privacy | Inspect Kafka events and the analytics tables | No raw IP addresses (hashed only) |

### 5.6 Other non-functional checks

| ID | Area | Test | Pass criteria |
|----|------|------|---------------|
| NFT-O-01 | Observability | Generate traffic, then check dashboards and traces | Metrics for created/redirected/not-found/rate-limited exist; one trace spans gateway → service → Cassandra/Kafka |
| NFT-O-02 | Health probes | Stop Cassandra | Readiness probe fails (pod taken out of rotation); liveness probe stays up |
| NFT-O-03 | Storage growth | After the 8 h soak, compare Cassandra disk usage with the inserted volume | Within 20 % of the model in architecture §1.1; expired data is compacted away |
| NFT-O-04 | Disaster recovery | Fail over to the second DC | RTO < 15 min; RPO ≈ 0 for links |

---

## 6. Traceability

| Requirement | Test cases |
|-------------|-----------|
| C1 Create | FT-C-01, 02, 13 |
| C2 Unique keys | FT-C-03, 04, FT-R-08, NFT-P-08 |
| C3 Redirect | FT-R-01 – 04, FT-G-06 |
| C4 Input validation | FT-C-05 – 10, 12, NFT-S-04 |
| C5 Not found / invalid key | FT-R-05 – 07, 09 |
| C6 Expiry | FT-C-09, FT-E-01 – 07 |
| C8 Disable | FT-R-10, FT-G-04, 05 |
| A1 – A5 Analytics | FT-A-01 – 08 |
| A6, A7 Click details / time series | FT-A-09, 10 |
| R1 Persistent storage | NFT-R-13 |
| R2, R3 Status codes / errors | FT-C-05, 10, FT-A-05, NFT-S-07 |
| R5 Rate limiting | FT-G-08 – 12, NFT-P-09, NFT-S-06 |
| R7 Resilience | NFT-R-01 – 12 |
| R8 Health | NFT-O-02 |
| R9 Observability | NFT-O-01 |
| R10 Graceful shutdown | NFT-R-02 |
| R11 Security | FT-C-07, 12, NFT-S-01 – 11 |
| Architecture: auth at the gateway | FT-G-01 – 07 |
| Architecture: TTL clean-up | FT-E-04, 05, NFT-O-03 |
| Architecture: horizontal scaling | NFT-P-05, NFT-P-10, NFT-R-01 |
| Architecture: no event loss | FT-K-01 – 05, NFT-R-06 – 09 |

## 7. Entry and exit criteria

**Entry (before a test cycle starts)**

- The build passes unit tests, and the environment matches the composition in §3.
- Seed data is loaded and the monitoring dashboards are working.
- For load tests: the load generators are verified able to produce 120 % of the target rate.

**Exit (release gate)**

- 100 % of **High**-priority functional cases pass. No open Critical/High defects.
- NFT-P-03 (peak load), NFT-P-06 (soak) and NFT-R-07 (Kafka outage) pass.
- No-event-loss check passes for every resilience test where loss isn't accepted.
- Line coverage ≥ 80 % on `core`, `api` and `analytics` (JaCoCo) as a guide, not a goal in itself.

**Defect severity**

| Severity | Definition | Example |
|----------|-----------|---------|
| Critical | Wrong redirect, data loss or a security breach | Redirect to the wrong URL; lost events; another client's link can be disabled |
| High | Requirement not met; no workaround | `503` for client errors; SLO missed by > 20 % |
| Medium | Requirement partly met, or a workaround exists | Wrong error body format |
| Low | Cosmetic | Swagger documentation mismatch |

## 8. Suggested order of work

1. **Now (Stage A):** unit tests for `BaseConverter` (RF-13) and integration tests FT-C, FT-R, FT-E and FT-A. The ❌ cases document the known bugs and should pass once phase 1 of the brown-field roadmap is done.
2. **Concurrency:** FT-A-03 and FT-A-04 (the DoD requires exact counts under concurrent hits).
3. **Stage A baseline:** NFT-P-01 and NFT-P-02 locally, to get a single-instance capacity figure.
4. **Stage B, as each component is introduced:** FT-G with the gateway, FT-E-04/05 with Cassandra, FT-K and NFT-R-06 – 09 with Kafka.
5. **Before the first production release:** the full NFT-P, NFT-R and NFT-S suites in staging.

## 9. Risks and open points

| Risk / open point | Effect on testing | Link |
|-------------------|-------------------|------|
| Scale and SLO targets are still assumptions | Load-test pass criteria may change | AMB-23, AMB-24 |
| "No event loss" isn't defined precisely | NFT-R-09 has no fixed pass criterion | AMB-25 |
| Staging must be production-sized for meaningful results | Smaller staging means scaling the targets down linearly and treating results as indicative | — |
| C6 / EN-01 still mention `410` | Docs and tests could disagree | AMB-07 |
