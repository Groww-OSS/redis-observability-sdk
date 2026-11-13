package com.groww.infra.spring.redis.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RedisObservabilityAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(RedisObservabilityAutoConfiguration.class))
      .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  void shouldBackOffWhenMasterSwitchIsOff() {
    contextRunner.run(context ->
        assertThat(context).doesNotHaveBean(JedisPoolMetricsPostProcessor.class));
  }

  @Test
  void shouldRegisterPostProcessorWhenEnabled() {
    contextRunner
        .withPropertyValues("redis.observability.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(JedisPoolMetricsPostProcessor.class));
  }

  @Test
  void shouldBackOffWhenFlagIsNotTrue() {
    contextRunner
        .withPropertyValues("redis.observability.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(JedisPoolMetricsPostProcessor.class));
  }

  @Test
  void shouldBackOffWithoutMeterRegistry() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisObservabilityAutoConfiguration.class))
        .withPropertyValues("redis.observability.enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(JedisPoolMetricsPostProcessor.class));
  }
}
