# 03 – Scope: Ambiguities Tracker

## Purpose

The original requirement was one sentence:

> _"Build a URL shortener service from scratch with core APIs, analytics, and reliability features."_

It doesn't say **which** APIs, **what** analytics, **how reliable**, or **at what scale**. This tracker records every point where the requirement (and the later architecture inputs) could be read more than one way, together with the decision once one is made.

Related documents: [01-scope-green-field.md](01-scope-green-field.md) (MVP features), [02-scope-brown-field.md](02-scope-brown-field.md) (work on the current code), [01-architecture.md](01-architecture.md) (target architecture).

**How to use it**

- **State:** `RESOLVED` (a decision has been made and recorded) or `UNRESOLVED` (needs an owner's decision).
- **When Resolved:** date of the decision, or `—` if still open.
- **Resolution / current assumption:** for `RESOLVED` items, the decision. For `UNRESOLVED` items, the assumption the team is working with until someone decides. Assumptions can change.
- When an item is resolved, update the State and date, and record the decision in the relevant scope or architecture document.

---

## Summary

| Area | Total | RESOLVED | UNRESOLVED |
|------|------:|---------:|-----------:|
| Core APIs | 12 | 7 | 5 |
| Analytics | 8 | 2 | 6 |
| Reliability and non-functional | 10 | 2 | 8 |
| Security and access | 6 | 3 | 3 |
| Data and infrastructure | 6 | 4 | 2 |
| **Total** | **42** | **18** | **24** |

---

## 1. Core APIs

| ID | Ambiguous point | Why it matters | Resolution / current assumption | State | When Resolved |
|----|-----------------|----------------|--------------------------------|-------|---------------|
| AMB-01 | What does "core APIs" include? Create and redirect only, or also read, list, update and delete? | Sets the MVP scope and size of the work | MVP = create + redirect + get metadata + disable (C1–C8 in 01-scope-green-field) | RESOLVED | 2026-10-12 |
| AMB-02 | Which characters may a short key contain? | URL safety, how many keys are possible, how easy keys are to read | RFC 3986 unreserved characters **without `.`**: `[0-9A-Za-z-_~]` (65 characters), in both `BaseConverter` and the redirect route regex. `.` was removed because a key of `.` or a trailing dot causes problems. Base62 is still recommended (02 / C2) | RESOLVED | 2026-10-13 |
| AMB-03 | Maximum short-key length | Overflow when decoding, DB column size, total number of keys | Maximum 10 characters in the route regex and `T_LINK_HIT_COUNT.SHORT_LINK_ID` (66¹⁰ ≈ 1.6 × 10¹⁸ keys, fits in `long`) | RESOLVED | 2026-10-12 |
| AMB-04 | Does a link expire, and how is expiry given? (date, duration, default?) | API contract and storage model | Optional `ttlInSeconds` (a number of seconds) on create; stored as `expiredDate` / Cassandra `USING TTL` | RESOLVED | 2026-10-12 |
| AMB-05 | Request and response format for create (form vs JSON, plain key vs full object) | Clients integrate against it | JSON in `{ url, ttlInSeconds }`, JSON out `{ shortKey, shortUrl, createdAt, expiresAt }` | RESOLVED | 2026-10-12 |
| AMB-06 | Maximum TTL, and the default for links created without one (never expire, or e.g. 1 year?) | Storage growth; abuse with permanent links | Assumption: no TTL = never expires; no maximum | UNRESOLVED | — |
| AMB-07 | What should an **expired** link return: `404 Not Found` or `410 Gone`? | `410` needs a marker row once Cassandra's TTL removes the record | `404 Not Found`, the same as an unknown key. No marker row is needed after Cassandra's TTL removes the record (architecture §7.4) | RESOLVED | 2026-10-13 |
| AMB-08 | Redirect type: `301` (permanent, cached by browsers) or `302`/`307` (temporary)? | `301` gets cached by browsers and CDNs, so later hits are never counted | `302 Found`, so browsers and CDNs don't cache the redirect and every hit reaches the service and is counted (ADR-006) | RESOLVED | 2026-10-13 |
| AMB-09 | Shortening the **same URL twice**: return the existing key, or create a new one? | Deduplication needs a lookup index; links can have different TTLs or owners | Assumption: always create a new key | UNRESOLVED | — |
| AMB-10 | Are **custom aliases** (`/my-sale`) in scope? | Needs a uniqueness check (`IF NOT EXISTS`) and a reserved-word list | Assumption: out of scope for MVP | UNRESOLVED | — |
| AMB-11 | Is the destination URL **editable** after creation? | Changes the cache and analytics semantics | Assumption: not editable (`FULL_LINK` is `updatable = false`) | UNRESOLVED | — |
| AMB-12 | What does "delete" mean: hard delete, soft delete (disable), and can a key be reused afterwards? | Reusing a key would send old links to new targets | Assumption: soft delete (`status = DISABLED`); keys are never reused | UNRESOLVED | — |

## 2. Analytics

| ID | Ambiguous point | Why it matters | Resolution / current assumption | State | When Resolved |
|----|-----------------|----------------|--------------------------------|-------|---------------|
| AMB-13 | Which events are tracked? | Topic and schema design | Link **created** and link **accessed (hit)**; plus `link.disabled` in the architecture | RESOLVED | 2026-10-13 |
| AMB-14 | Is tracking synchronous or asynchronous, and over what channel? | Redirect latency and durability | Asynchronous through Kafka, with no event loss (architecture §8) | RESOLVED | 2026-10-13 |
| AMB-15 | What exactly is a "hit"? Every request, unique visitors, excluding bots and link-preview crawlers (Slack, WhatsApp)? | Accuracy of the numbers shown to clients | Assumption: every successful redirect counts; no bot filtering | UNRESOLVED | — |
| AMB-16 | Which dimensions are captured per hit (referrer, user agent, country, device, IP)? | Privacy obligations and storage size | Assumption: timestamp, referrer, user agent, hashed IP, country (A6) | UNRESOLVED | — |
| AMB-17 | How fresh must stats be: real time, or minutes behind? | Kafka processing design and cost | Assumption: eventually consistent, behind by under 1 minute | UNRESOLVED | — |
| AMB-18 | How long is analytics data kept? | Storage cost, GDPR | Assumption: daily aggregates for ~400 days, raw events 7 days in Kafka | UNRESOLVED | — |
| AMB-19 | Who can see analytics: the link owner only, or also admins across all links? | Authorization rules on the stats API | Assumption: owner sees their own links; admin sees all | UNRESOLVED | — |
| AMB-20 | Do failed lookups (unknown, expired, disabled keys) count as analytics events? | Abuse detection versus noise in the numbers | Assumption: not counted as hits; exposed only as an operational metric | UNRESOLVED | — |

## 3. Reliability and non-functional

| ID | Ambiguous point | Why it matters | Resolution / current assumption | State | When Resolved |
|----|-----------------|----------------|--------------------------------|-------|---------------|
| AMB-21 | What "reliability features" means: durability, availability, correctness under concurrency, fault tolerance? | Defines the R* features | R1–R12 in 01-scope-green-field (durable storage, correct errors, retries, health, graceful shutdown, tests, …) | RESOLVED | 2026-10-12 |
| AMB-22 | Is a cache used on the redirect path? | Latency versus consistency and how big Cassandra must be | No application-tier cache. Redis only at the API Gateway for rate limiting; redirects read Cassandra directly | RESOLVED | 2026-10-13 |
| AMB-23 | Expected **scale**: links per day, redirects per second, total links | Sizing of every component | Assumption: 10 M creates/day, 100 k redirects/s peak (architecture §1.1). Needs confirmation | UNRESOLVED | — |
| AMB-24 | Availability target and latency budget | Number of replicas and regions | Assumption: 99.95 % for redirects; p99 < 50 ms end to end | UNRESOLVED | — |
| AMB-25 | "No events lost": **at-least-once** or **exactly-once**, and does that include a pod crashing before the Kafka ack? | A synchronous send per redirect adds latency | Assumption: at-least-once delivery + idempotent consumers; outbox on create, disk spool on redirect | UNRESOLVED | — |
| AMB-26 | What should happen to redirects when Kafka is down? Keep redirecting without analytics, or fail? | Availability versus analytics completeness | Assumption: redirects keep working; events are buffered and replayed later | UNRESOLVED | — |
| AMB-27 | Single region or multi-region? Active-active or active-passive? | Cassandra replication, Kafka mirroring, cost | Assumption: 2 DCs, active-active for Cassandra | UNRESOLVED | — |
| AMB-28 | Recovery targets (RPO / RTO) | Backup and failover design | Assumption: RPO ≈ 0 for links, RTO < 15 min | UNRESOLVED | — |
| AMB-29 | Rate-limit values per tier, and whether redirects are rate-limited too | Usability versus abuse protection | Assumption: Free 10/min, Pro 600/min on create; 100/s per IP on redirect | UNRESOLVED | — |
| AMB-30 | How much consistency is required? ("AP, with C tunable") | Whether a just-created link can be visited immediately | Assumption: `LOCAL_QUORUM` writes; redirect reads at `LOCAL_ONE` with a `LOCAL_QUORUM` retry if not found | UNRESOLVED | — |

## 4. Security and access

| ID | Ambiguous point | Why it matters | Resolution / current assumption | State | When Resolved |
|----|-----------------|----------------|--------------------------------|-------|---------------|
| AMB-31 | Who are the users? | Roles and authorization model | Two roles: **Client** and **Admin**; plus anonymous **Visitors** for redirects | RESOLVED | 2026-10-13 |
| AMB-32 | Where are authentication and authorization enforced? | Gateway versus service responsibilities | API Gateway authenticates (JWT/OIDC) and authorizes the create API; services check ownership | RESOLVED | 2026-10-13 |
| AMB-33 | Where is rate limiting enforced? | Component choice | At the API Gateway, on the create API | RESOLVED | 2026-10-13 |
| AMB-34 | Does the redirect need authentication too? ("All requests pass through the gateway") | A login wall would break public short links | Assumption: redirect passes through the gateway but is public | UNRESOLVED | — |
| AMB-35 | Exactly what can an Admin do that a Client can't (block domains, manage any link, change limits, view global stats)? | Admin API scope | Assumption: as listed in architecture §2 | UNRESOLVED | — |
| AMB-36 | Is it acceptable that sequential IDs make keys **guessable**? Is malicious-URL screening required? | Privacy of "unlisted" links; phishing abuse | Assumption: guessable keys are acceptable; only scheme checks (`http`/`https`); no Safe Browsing check | UNRESOLVED | — |

## 5. Data and infrastructure

| ID | Ambiguous point | Why it matters | Resolution / current assumption | State | When Resolved |
|----|-----------------|----------------|--------------------------------|-------|---------------|
| AMB-37 | Which database? | Data model, TTL, consistency | NoSQL: **Apache Cassandra** preferred, DynamoDB as the alternative | RESOLVED | 2026-10-13 |
| AMB-38 | Are expired records cleaned up automatically or by a job? | Operations overhead | Automatically through the database TTL (Cassandra `USING TTL`) | RESOLVED | 2026-10-13 |
| AMB-39 | Must the backend scale horizontally? | Stateless design, key generation | Yes: stateless Link Service; IDs from range leasing instead of a DB sequence | RESOLVED | 2026-10-13 |
| AMB-40 | Messaging technology for analytics | Durability guarantees | Apache Kafka | RESOLVED | 2026-10-13 |
| AMB-41 | Self-managed Cassandra or a managed service (Astra, Amazon Keyspaces)? | Keyspaces limits lightweight transactions and batches, which affects ID leasing and the outbox | Assumption: self-managed on Kubernetes | UNRESOLVED | — |
| AMB-42 | Cloud provider and deployment platform | Choice of gateway (Spring Cloud Gateway / Kong / AWS API Gateway) and IdP | Assumption: Kubernetes on AWS, cloud-neutral components | UNRESOLVED | — |

---

## Decisions needed first

These unresolved items block design or implementation work the most:

1. **AMB-23 / AMB-24:** scale and availability targets. Every sizing decision depends on them.
2. **AMB-06:** default and maximum TTL. This affects the API contract and storage growth.
3. **AMB-25 / AMB-26:** the exact meaning of "no events lost", and what redirects do when Kafka is down.
4. **AMB-41:** managed or self-managed Cassandra. This affects key generation (§6.2) and the outbox design.

## Change log

| Date | Change |
|------|--------|
| 2026-10-13 | Tracker created: 42 points identified (16 resolved, 26 unresolved) |
| 2026-10-13 | AMB-08 resolved: redirects use `302` |
| 2026-10-13 | AMB-07 resolved: expired links return `404` |
| 2026-10-13 | AMB-02 updated: `.` removed from the key alphabet and the redirect regex |
