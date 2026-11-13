package com.groww.infra.spring.redis.observability;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.BaseGenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

/**
 * A {@link JedisConnectionFactory} that registers live Micrometer metrics for the underlying Jedis
 * connection pool.
 *
 * <p>spring-data-redis creates the pool internally and exposes it only through the protected
 * {@link #createRedisPool()} template method. By overriding that hook we capture the real
 * {@link Pool} instance (which extends commons-pool2's {@code GenericObjectPool}) and bind gauges
 * to its live state — no reflection required.
 *
 * <p>Gauges are read lazily at scrape/export time (Prometheus pull for web profiles, OTLP push
 * for job profiles), so they always reflect current pool state without any background updates.
 *
 * <p>Metrics (tagged {@code pool=<poolName>}):
 * <ul>
 *   <li>{@code redis_pool_connections_active} — connections currently borrowed (gauge)</li>
 *   <li>{@code redis_pool_connections_idle} — connections sitting idle (gauge)</li>
 *   <li>{@code redis_pool_connections_waiters} — threads blocked waiting for a connection;
 *       the primary pool-saturation signal (gauge)</li>
 *   <li>{@code redis_pool_borrow_wait_mean_ms} / {@code redis_pool_borrow_wait_max_ms} — borrow
 *       latency (gauges)</li>
 *   <li>{@code redis_pool_connections_opened_total} / {@code redis_pool_connections_destroyed_total}
 *       — connection churn (function counters)</li>
 * </ul>
 *
 * <p><b>Assumption:</b> the pool lives for the application lifetime, which holds in practice —
 * connection factories are never restarted mid-JVM in production (pods restart as processes). A
 * theoretical in-place SmartLifecycle {@code stop()}/{@code start()} would leave meters bound to
 * the replaced pool; that trade-off is accepted deliberately to keep this class trivial.
 *
 * <p>Requires spring-data-redis 3.x and jedis 4+ on the consumer's classpath (both are
 * {@code provided} scope in this SDK).
 */
@Slf4j
public class ObservableJedisConnectionFactory extends JedisConnectionFactory {

  public static final String POOL_TAG = "pool";

  private final MeterRegistry meterRegistry;
  private final String poolName;

  private ObservableJedisConnectionFactory(RedisStandaloneConfiguration standaloneConfig,
      JedisClientConfiguration clientConfig, MeterRegistry meterRegistry, String poolName) {
    super(standaloneConfig, clientConfig);
    this.meterRegistry = meterRegistry;
    this.poolName = poolName;
  }

  /**
   * Creates an observable replacement for an existing {@link JedisConnectionFactory}, carrying
   * over its standalone and client configuration plus the behavior/lifecycle settings a client
   * may have changed from their defaults ({@code convertPipelineAndTxResults},
   * {@code earlyStartup}, {@code autoStartup}, {@code phase}).
   *
   * <p>Callers must ensure the source is a pooled, standalone factory
   * (see {@link JedisPoolMetricsPostProcessor} for the guard conditions).
   *
   * @param source the factory to upgrade; discarded without ever opening a connection
   * @param meterRegistry registry the pool meters are bound to
   * @param poolName value of the {@code pool} tag on every emitted meter
   */
  public static ObservableJedisConnectionFactory from(JedisConnectionFactory source,
      MeterRegistry meterRegistry, String poolName) {
    ObservableJedisConnectionFactory factory = new ObservableJedisConnectionFactory(
        source.getStandaloneConfiguration(), source.getClientConfiguration(), meterRegistry,
        poolName);
    factory.setConvertPipelineAndTxResults(source.getConvertPipelineAndTxResults());
    factory.setEarlyStartup(source.isEarlyStartup());
    factory.setAutoStartup(source.isAutoStartup());
    factory.setPhase(source.getPhase());
    return factory;
  }

  @Override
  protected Pool<Jedis> createRedisPool() {
    Pool<Jedis> pool = super.createRedisPool();
    bindPoolMetrics(pool);
    return pool;
  }

  private void bindPoolMetrics(Pool<Jedis> pool) {
    Tags tags = Tags.of(POOL_TAG, poolName);

    Gauge.builder("redis_pool_connections_active", pool, GenericObjectPool::getNumActive)
        .description("Connections currently borrowed from the Jedis pool")
        .tags(tags).register(meterRegistry);
    Gauge.builder("redis_pool_connections_idle", pool, GenericObjectPool::getNumIdle)
        .description("Connections sitting idle in the Jedis pool")
        .tags(tags).register(meterRegistry);
    Gauge.builder("redis_pool_connections_waiters", pool, GenericObjectPool::getNumWaiters)
        .description("Threads blocked waiting for a connection (pool saturation signal)")
        .tags(tags).register(meterRegistry);
    Gauge.builder("redis_pool_borrow_wait_mean_ms", pool, BaseGenericObjectPool::getMeanBorrowWaitTimeMillis)
        .description("Mean time threads waited to borrow a connection from the pool")
        .tags(tags).register(meterRegistry);
    Gauge.builder("redis_pool_borrow_wait_max_ms", pool, BaseGenericObjectPool::getMaxBorrowWaitTimeMillis)
        .description("Longest borrow wait since pool creation")
        .tags(tags).register(meterRegistry);
    // NOTE: do not name this "..._created_total" — the Prometheus client reserves the
    // "_created" suffix (counter birth-timestamp metadata) and silently renames the series.
    FunctionCounter.builder("redis_pool_connections_opened_total", pool, BaseGenericObjectPool::getCreatedCount)
        .description("Connections created by the pool since startup")
        .tags(tags).register(meterRegistry);
    FunctionCounter.builder("redis_pool_connections_destroyed_total", pool,
            BaseGenericObjectPool::getDestroyedCount)
        .description("Connections destroyed by the pool since startup")
        .tags(tags).register(meterRegistry);

    log.info("Registered live Jedis pool metrics for pool '{}'", poolName);
  }
}
