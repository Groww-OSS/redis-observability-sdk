package com.groww.infra.spring.redis.observability;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.JedisPoolConfig;

import static org.assertj.core.api.Assertions.assertThat;

class ObservableJedisConnectionFactoryTest {

  private static final String POOL_NAME = "test-pool";

  private SimpleMeterRegistry registry;
  private ObservableJedisConnectionFactory factory;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    JedisClientConfiguration clientConfig = JedisClientConfiguration.builder()
        .usePooling().poolConfig(poolConfig).build();
    JedisConnectionFactory source = new JedisConnectionFactory(
        new RedisStandaloneConfiguration("localhost", 6379), clientConfig);
    factory = ObservableJedisConnectionFactory.from(source, registry, POOL_NAME);
    // Pool creation is lazy (no Redis connection is opened), so lifecycle start is safe in tests.
    factory.afterPropertiesSet();
    factory.start();
  }

  @AfterEach
  void tearDown() {
    factory.stop();
    factory.destroy();
  }

  @Test
  void shouldRegisterLivePoolGaugesWithPoolTag() {
    assertGaugeRegistered("redis_pool_connections_active");
    assertGaugeRegistered("redis_pool_connections_idle");
    assertGaugeRegistered("redis_pool_connections_waiters");
    assertGaugeRegistered("redis_pool_borrow_wait_mean_ms");
    assertGaugeRegistered("redis_pool_borrow_wait_max_ms");
  }

  @Test
  void shouldRegisterPoolLifecycleCounters() {
    FunctionCounter created = registry.find("redis_pool_connections_opened_total")
        .tags(ObservableJedisConnectionFactory.POOL_TAG, POOL_NAME).functionCounter();
    FunctionCounter destroyed = registry.find("redis_pool_connections_destroyed_total")
        .tags(ObservableJedisConnectionFactory.POOL_TAG, POOL_NAME).functionCounter();

    assertThat(created).isNotNull();
    assertThat(destroyed).isNotNull();
    assertThat(created.count()).isGreaterThanOrEqualTo(0.0);
    assertThat(destroyed.count()).isGreaterThanOrEqualTo(0.0);
  }

  @Test
  void gaugesShouldReadLivePoolState() {
    Gauge active = registry.find("redis_pool_connections_active")
        .tags(ObservableJedisConnectionFactory.POOL_TAG, POOL_NAME).gauge();
    Gauge waiters = registry.find("redis_pool_connections_waiters")
        .tags(ObservableJedisConnectionFactory.POOL_TAG, POOL_NAME).gauge();

    // Nothing borrowed yet: no active connections, no waiters.
    assertThat(active.value()).isEqualTo(0.0);
    assertThat(waiters.value()).isEqualTo(0.0);
  }

  private void assertGaugeRegistered(String metricName) {
    Gauge gauge = registry.find(metricName)
        .tags(ObservableJedisConnectionFactory.POOL_TAG, POOL_NAME).gauge();
    assertThat(gauge).as("gauge %s should be registered", metricName).isNotNull();
  }
}
