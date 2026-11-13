package com.groww.infra.spring.redis.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

/**
 * Auto-configuration for Redis connection pool observability. Client services only need the
 * dependency plus:
 *
 * <pre>{@code
 * redis.observability.enabled: true
 * }</pre>
 *
 * <p>A {@link JedisPoolMetricsPostProcessor} then upgrades every pooled standalone
 * {@link JedisConnectionFactory} bean (including Spring Boot's own auto-configured one from
 * {@code spring.data.redis.*}) to emit live pool gauges — active/idle/waiter counts, borrow
 * latency, and connection churn, tagged {@code pool=<beanName>}.
 *
 * <p>Scope note: this SDK intentionally covers only the client-side (service → Redis) view.
 * Server-side Redis health (memory, evictions, total clients) is expected from infra-level
 * Redis monitoring, not from application SDKs.
 *
 * <p>Backs off entirely when {@code redis.observability.enabled} is not {@code true}, when
 * Micrometer or spring-data-redis are absent, or when no {@link MeterRegistry} bean exists.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"})
@ConditionalOnClass({JedisConnectionFactory.class, MeterRegistry.class})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "redis.observability", name = "enabled", havingValue = "true")
public class RedisObservabilityAutoConfiguration {

  /**
   * Static so the post-processor registers as early as possible, before any
   * JedisConnectionFactory beans are initialized.
   */
  @Bean
  static JedisPoolMetricsPostProcessor jedisPoolMetricsPostProcessor(MeterRegistry meterRegistry) {
    return new JedisPoolMetricsPostProcessor(meterRegistry);
  }
}
