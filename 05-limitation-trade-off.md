# 05 – Limitations and Trade-offs

This document records **what the current demo set-up deliberately leaves out** (limitations) and **which design choices give up one quality to gain another** (trade-offs).

Related documents: [01-architecture.md](01-architecture.md) (target architecture), [01-scope-green-field.md](01-scope-green-field.md) (MVP features), [02-scope-brown-field.md](02-scope-brown-field.md) (work on the current code), [03-scope-ambiguous.md](03-scope-ambiguous.md) (decisions), [04-test-plan.md](04-test-plan.md) (tests).

---

## 1. Limitation

These are gaps between the **current demo** (a single Spring Boot application) and the **target architecture**. They're intentional, to keep the demo easy to run on one machine.

### L-01 · H2 is used instead of Cassandra / DynamoDB for the demo

| | |
|---|---|
| **Current state** | Links and hit counts are stored in **in-memory H2** (`jdbc:h2:mem:url-shortener-db`) through JPA, with an SQL sequence (`SEQ_LINK_ID`) generating IDs. Expiry is stored in `EXPIRED_DATE` and checked in Java (`LinkEntity.isNotExpired`). |
| **Target** | Apache Cassandra (preferred) or DynamoDB, with per-record TTL and replication (architecture §7). |
| **Impact** | • **All data is lost on restart.** <br>• Runs on a single node, so it can't scale horizontally or survive a node failure. <br>• Expired rows are **never deleted**. They're only hidden by the Java check, so the table keeps growing. <br>• ID generation depends on one DB sequence, which doesn't exist in Cassandra, so it will need to change (range leasing, architecture §6.2). <br>• Performance results from the demo don't predict production behaviour. |
| **Mitigation / path forward** | Move to Cassandra (EN-03, R1) with `USING TTL` for automatic clean-up and range-leased IDs. Run integration tests against Cassandra in Testcontainers (test plan FT-E-04/05). |

### L-02 · No Kafka (or similar) for asynchronous event processing

| | |
|---|---|
| **Current state** | Create and hit events use Spring's in-process `ApplicationEventPublisher` with an `@Async` listener (`LinkEventConsumer`). Events exist **only in the application's memory**. |
| **Target** | Apache Kafka with `acks=all`, RF=3, a transactional outbox and exactly-once Kafka Streams aggregation (architecture §8). |
| **Impact** | • **Events are lost** if the application crashes or restarts while they're queued. This breaks the "no event loss" requirement. <br>• Events can't be replayed or reprocessed. <br>• Analytics can't scale separately from the Link Service, because they share one process and its threads. <br>• No other system can consume the events. <br>• A hit can be lost if it's processed before the async insert of its counter row (BUG-05). |
| **Mitigation / path forward** | Introduce Kafka with the outbox pattern first, while still on H2 (architecture §10, step 1). Verify with the no-event-loss check (test plan §5.4, NFT-R-06 – R-09). |

### L-03 · No API Gateway, rate limiting or authentication

| | |
|---|---|
| **Current state** | Clients call the Spring Boot app **directly**. Every endpoint is public, the creator is hard-coded as `"Anonymus"`, and nothing limits how many links a caller can create. |
| **Target** | API Gateway with OIDC/JWT authentication, scope- and role-based authorization, and Redis-backed rate limiting on the create API (architecture §5). |
| **Impact** | • **Anyone can create unlimited links**: open to spam, abuse and running out of storage. <br>• Links have no owner, so there's no "my links" view and no per-user stats, and the Client and Admin roles can't be told apart. <br>• Nothing protects against floods of redirect requests or scanning for keys in sequence. <br>• The H2 console and Swagger UI are reachable by anyone who can reach the app. |
| **Mitigation / path forward** | Put the gateway and IdP in front (architecture §10, step 2), then cover them with test plan FT-G-01 – 12 and NFT-S-01 – 06. Until then, run the demo only on a trusted network. |

---

## 2. Trade Off

These are deliberate choices in the **target architecture**. Each one gives up something to gain something more important for this workload.

### T-01 · CAP over ACID: Cassandra / DynamoDB instead of a relational database

| | |
|---|---|
| **Decision** | Store links in a distributed NoSQL database (Cassandra preferred, DynamoDB as the alternative) that favours **Availability and Partition tolerance (AP)** with **tunable Consistency**, instead of an ACID relational database (PostgreSQL, MySQL, Oracle). |
| **What we gain** | • **Availability:** redirects keep working when nodes, an AZ, or the link between data centres fail. <br>• **Linear horizontal scaling:** add nodes to handle more reads and writes, without a single primary database as the bottleneck. <br>• **Native per-record TTL:** expired links disappear on their own (AMB-38). <br>• **Multi-DC replication** built in. <br>• **Consistency chosen per query:** `LOCAL_QUORUM` for writes, `LOCAL_ONE` for fast redirect reads. |
| **What we give up** | • **Multi-row / multi-table ACID transactions.** Writing `links_by_key` and `links_by_owner` together needs a logged batch (atomic, but not isolated). Writing a link and an event together needs the outbox pattern. <br>• **Strong consistency by default:** a redirect read at `LOCAL_ONE` may briefly miss a link that was *just* created (handled with a `LOCAL_QUORUM` retry when the key isn't found). <br>• **Auto-increment sequences:** ID generation needs range leasing with lightweight transactions (architecture §6.2). <br>• **Ad-hoc queries and joins:** tables must be designed for each query (`links_by_key`, `links_by_owner`). New access patterns mean new tables. <br>• **Constraints** (foreign keys, unique keys other than the primary key) must be enforced in the application. <br>• Operations are more complex: repairs, tombstones, compaction tuning. |
| **Why it's acceptable** | The workload is almost entirely **lookups by a single key** (100:1 reads to writes). Short links don't need cross-record transactions, and a brief delay before a new link is visible everywhere is harmless. Availability of redirects matters far more than strict consistency. |
| **When to revisit** | If the product adds features that need transactions or relational queries (billing, team workspaces, complex reporting), keep those in a separate relational store rather than changing the link store. |

### T-02 · Double-digit ms latency instead of single-digit: no Redis cache, to save CAPEX and keep the design minimal

| | |
|---|---|
| **Decision** | **No application-tier Redis cache** on the redirect path. Every redirect reads Cassandra directly (`LOCAL_ONE`). Redis is used **only** at the API Gateway for rate limiting (ADR-005, AMB-22). |
| **What we gain** | • **Lower CAPEX/OPEX:** no Redis cluster to buy, size, secure, monitor and patch for caching. <br>• **Simpler design:** one fewer component on the hot path, fewer failure modes, and no cache-invalidation logic. <br>• **Always-fresh data:** disabling a link or reaching its TTL takes effect immediately, with no stale cache entries. <br>• No risk of a cache serving a link after its TTL. |
| **What we give up** | • **Latency:** redirect latency in the service is about **10–20 ms** (p99, Cassandra read) instead of about **1–5 ms** with an in-memory cache. End-to-end p99 stays within the **< 50 ms** target. <br>• **Cassandra must handle the full read load:** all ~100 k redirects/s at peak reach the database, so the cluster needs more nodes or capacity than it would behind a cache. This partly offsets the cost saved on Redis. <br>• **Less protection against hot keys:** a viral link concentrates load on its 3 replicas (tested by NFT-P-07). <br>• Repeated lookups of unknown keys (e.g. scanners) reach the database, with no negative cache to absorb them. |
| **Why it's acceptable** | Users don't notice the difference between 5 ms and 20 ms of server time next to normal internet round trips (50–200 ms). The latency SLO is still met. Cassandra is built for high-volume reads by key. |
| **When to revisit** | Add a cache if any of these happen: <br>• the redirect p99 SLO is missed in load tests (NFT-P-03), <br>• Cassandra read capacity costs more than a cache would, <br>• hot keys overload their replicas (NFT-P-07), <br>• a stricter latency SLO is set. <br>The cheapest first step is a small **in-process cache** (e.g. Caffeine) with entries kept no longer than the link's remaining TTL. It needs no new infrastructure. Redis comes next only if a shared cache is needed. |

---

## Change log

| Date | Change |
|------|--------|
| 2026-10-13 | Created with 3 limitations (L-01 – L-03) and 2 trade-offs (T-01, T-02) |
