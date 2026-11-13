# redis-observability-sdk

SDK for live Redis connection-pool observability in Spring Boot services (spring-data-redis + Jedis).

## Overview

This library fills the **service → Redis** monitoring gap: per-pod Jedis connection-pool state,
emitted as Micrometer metrics. Server-side Redis health (memory, evictions, total clients) is
intentionally out of scope — that is covered by infra-level Redis monitoring.

Adoption is flag-only: add the dependency, set one property, and every pooled standalone
`JedisConnectionFactory` bean in the service — including Spring Boot's own auto-configured one
from `spring.data.redis.*` — starts emitting live pool metrics. No changes to existing Redis
configuration code.

All meters are local in-memory reads evaluated lazily at scrape/export time. No network calls,
no background threads; works identically under Prometheus pull (web services) and OTLP push
(jobs).

## Key components

[ObservableJedisConnectionFactory](src/main/java/com/groww/infra/spring/redis/observability/ObservableJedisConnectionFactory.java)
— a drop-in `JedisConnectionFactory` subclass that captures the real Jedis pool via the protected
`createRedisPool()` template method (no reflection) and binds meters to its live state. Can also be
used directly when constructing a factory by hand.

[JedisPoolMetricsPostProcessor](src/main/java/com/groww/infra/spring/redis/observability/JedisPoolMetricsPostProcessor.java)
— a `BeanPostProcessor` that swaps plain `JedisConnectionFactory` beans for the observable subclass
in `postProcessBeforeInitialization`, i.e. after the bean is constructed but **before the pool is
created**. The replacement preserves the standalone config, client/pool config,
`convertPipelineAndTxResults`, and lifecycle settings (`earlyStartup` / `autoStartup` / `phase`).
Sentinel and cluster factories are skipped (standalone only).

[RedisObservabilityAutoConfiguration](src/main/java/com/groww/infra/spring/redis/observability/RedisObservabilityAutoConfiguration.java)
— wires the post-processor when `redis.observability.enabled=true`. Backs off entirely when the
flag is off, or when Micrometer / spring-data-redis / a `MeterRegistry` bean are absent.

## Metrics

All tagged `pool=<beanName>` (e.g. `pool="jedisConnectionFactory"`).

| Metric | Type | Description |
|---|---|---|
| `redis_pool_connections_active` | gauge | Connections currently borrowed from the pool |
| `redis_pool_connections_idle` | gauge | Connections sitting idle |
| `redis_pool_connections_waiters` | gauge | Threads blocked waiting for a connection — primary saturation signal |
| `redis_pool_borrow_wait_mean_ms` | gauge | Mean borrow wait; approaching `maxWaitMillis` means trouble |
| `redis_pool_borrow_wait_max_ms` | gauge | Longest borrow wait since pool creation |
| `redis_pool_connections_opened_total` | counter | Connections created since startup |
| `redis_pool_connections_destroyed_total` | counter | Connections destroyed since startup |

> Naming note: the opened counter is deliberately **not** `..._created_total` — the Prometheus
> 1.x client reserves the `_created` suffix (counter birth-timestamp metadata) and silently
> renames such series.

## How to use

Add the maven dependency:

```xml
<dependency>
    <groupId>com.groww.infra</groupId>
    <artifactId>spring-redis-observability-sdk</artifactId>
    <version>1.0.1</version>
</dependency>
```

Enable in `application.yaml`:

```yaml
redis:
  observability:
    enabled: true
```

That is all — existing `JedisConnectionFactory` beans require no changes.

## Requirements

- Java 17
- spring-data-redis 3.x and jedis 4+ (both `provided` scope — supplied by the consuming service)
- A `MeterRegistry` bean (e.g. via `spring-boot-starter-actuator` +
  `micrometer-registry-prometheus`, or an OTLP registry)

## Notes

- **Pod-level view.** Pool meters are per-JVM; aggregate in dashboards
  (`sum by (pool)`, `max(redis_pool_connections_waiters)` etc.).
- **Standalone only.** Sentinel/cluster-configured factories are logged and skipped.
- **Pool lifetime assumption.** Meters bind to the pool instance created at startup; in-place
  SmartLifecycle restarts (which do not occur in production — pods restart as processes) would
  leave meters bound to the replaced pool. Accepted deliberately for simplicity.

## Building

```bash
mvn clean install   # JDK 17
```

Publishing is via the CI `sdk` build profile (see `ci.yaml`) — merging to `master` builds and
publishes `com.groww.infra:spring-redis-observability-sdk` to nexus with version `${revision}`.
