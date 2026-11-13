package com.groww.infra.spring.redis.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.JedisPoolConfig;

import static org.assertj.core.api.Assertions.assertThat;

class JedisPoolMetricsPostProcessorTest {

  private SimpleMeterRegistry registry;
  private JedisPoolMetricsPostProcessor postProcessor;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    postProcessor = new JedisPoolMetricsPostProcessor(registry);
  }

  @Test
  void shouldUpgradePooledStandaloneFactoryBeforeInitialization() {
    JedisConnectionFactory original = pooledFactory();

    Object result = postProcessor.postProcessBeforeInitialization(original, "mainRedis");

    assertThat(result).isInstanceOf(ObservableJedisConnectionFactory.class);
    assertThat(result).isInstanceOf(JedisConnectionFactory.class);

    // The replacement initializes its own instrumented pool; original is discarded untouched.
    ObservableJedisConnectionFactory upgraded = (ObservableJedisConnectionFactory) result;
    upgraded.afterPropertiesSet();
    upgraded.start();
    try {
      assertThat(registry.find("redis_pool_connections_active")
          .tags(ObservableJedisConnectionFactory.POOL_TAG, "mainRedis").gauge()).isNotNull();
    } finally {
      upgraded.stop();
      upgraded.destroy();
    }
  }

  @Test
  void shouldLeaveNonPooledFactoryUntouched() {
    JedisConnectionFactory nonPooled = new JedisConnectionFactory(
        new RedisStandaloneConfiguration("localhost", 6379),
        JedisClientConfiguration.builder().build());

    Object result = postProcessor.postProcessBeforeInitialization(nonPooled, "plainRedis");

    assertThat(result).isSameAs(nonPooled);
  }

  @Test
  void shouldLeaveAlreadyObservableFactoryUntouched() {
    ObservableJedisConnectionFactory alreadyObservable = ObservableJedisConnectionFactory.from(
        pooledFactory(), registry, "alreadyObservable");

    Object result =
        postProcessor.postProcessBeforeInitialization(alreadyObservable, "alreadyObservable");

    assertThat(result).isSameAs(alreadyObservable);
  }

  @Test
  void shouldSkipClusterAwareFactory() {
    JedisConnectionFactory clusterFactory = new JedisConnectionFactory(
        new RedisClusterConfiguration(List.of("127.0.0.1:7379")));

    Object result = postProcessor.postProcessBeforeInitialization(clusterFactory, "clusterRedis");

    assertThat(result).isSameAs(clusterFactory);
  }

  @Test
  void shouldSkipSentinelAwareFactory() {
    RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
        .master("mymaster")
        .sentinel("127.0.0.1", 26379);
    JedisConnectionFactory sentinelFactory = new JedisConnectionFactory(sentinelConfig);

    Object result = postProcessor.postProcessBeforeInitialization(sentinelFactory, "sentinelRedis");

    assertThat(result).isSameAs(sentinelFactory);
  }

  @Test
  void shouldLeaveNonJedisBeansUntouched() {
    Object bean = new Object();

    assertThat(postProcessor.postProcessBeforeInitialization(bean, "someBean")).isSameAs(bean);
  }

  @Test
  void shouldPreservePoolAndClientConfigurationOnUpgrade() {
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(42);
    JedisConnectionFactory original = new JedisConnectionFactory(
        new RedisStandaloneConfiguration("localhost", 6379),
        JedisClientConfiguration.builder().usePooling().poolConfig(poolConfig).build());
    original.setConvertPipelineAndTxResults(false);

    ObservableJedisConnectionFactory upgraded = (ObservableJedisConnectionFactory)
        postProcessor.postProcessBeforeInitialization(original, "configCheck");

    assertThat(upgraded.getPoolConfig().getMaxTotal()).isEqualTo(42);
    assertThat(upgraded.getConvertPipelineAndTxResults()).isFalse();
    assertThat(upgraded.getStandaloneConfiguration().getHostName()).isEqualTo("localhost");
  }

  @Test
  void shouldPreserveLifecycleSettingsOnUpgrade() {
    JedisConnectionFactory original = pooledFactory();
    original.setPhase(42);
    original.setAutoStartup(false);
    original.setEarlyStartup(true);

    ObservableJedisConnectionFactory upgraded = (ObservableJedisConnectionFactory)
        postProcessor.postProcessBeforeInitialization(original, "lifecycleCheck");

    assertThat(upgraded.getPhase()).isEqualTo(42);
    assertThat(upgraded.isAutoStartup()).isFalse();
    assertThat(upgraded.isEarlyStartup()).isTrue();
  }

  private JedisConnectionFactory pooledFactory() {
    return new JedisConnectionFactory(
        new RedisStandaloneConfiguration("localhost", 6379), pooledClientConfig());
  }

  private JedisClientConfiguration pooledClientConfig() {
    return JedisClientConfiguration.builder().usePooling().poolConfig(new JedisPoolConfig())
        .build();
  }
}
