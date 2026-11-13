package com.groww.infra.spring.redis.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

/**
 * Transparently upgrades every pooled, standalone {@link JedisConnectionFactory} bean to an
 * {@link ObservableJedisConnectionFactory}, so live pool metrics are registered without any
 * changes to the client's Redis configuration code.
 *
 * <p>The swap happens in {@link #postProcessBeforeInitialization} — i.e. after the bean is
 * constructed and configured, but before {@code afterPropertiesSet()}/{@code start()} create the
 * actual Jedis pool. The replacement therefore observes the real pool from the moment it exists,
 * and the discarded original instance never opens a single connection.
 *
 * <p>The replacement is a subclass of {@link JedisConnectionFactory}, so concrete-type injection
 * points and qualifiers keep working unchanged.
 *
 * <p>Skipped (left untouched):
 * <ul>
 *   <li>factories already observable (explicit SDK usage)</li>
 *   <li>factories with pooling disabled</li>
 *   <li>sentinel/cluster configurations (no standalone pool to instrument)</li>
 * </ul>
 */
@Slf4j
public class JedisPoolMetricsPostProcessor implements BeanPostProcessor {

  private final MeterRegistry meterRegistry;

  public JedisPoolMetricsPostProcessor(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
    if (!(bean instanceof JedisConnectionFactory factory)
        || bean instanceof ObservableJedisConnectionFactory
        || !factory.getUsePool()
        || factory.isRedisSentinelAware()
        || factory.isRedisClusterAware()
        || factory.getStandaloneConfiguration() == null) {
      return bean;
    }

    ObservableJedisConnectionFactory observable =
        ObservableJedisConnectionFactory.from(factory, meterRegistry, beanName);
    log.info("Upgraded JedisConnectionFactory bean '{}' with live pool metrics", beanName);
    return observable;
  }
}
