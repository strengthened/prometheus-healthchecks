package com.github.strengthened.prometheus.healthchecks;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A Collector for health checks.
 */
public class HealthChecksCollector extends Collector {
  final String gaugeMetricHelp;
  final String gaugeMetricName;
  final List<String> labelNames;

  private final ConcurrentMap<String, HealthCheck> healthChecks;
  private final ScheduledExecutorService asyncExecutorService;
  private final Object lock;

  private HealthChecksCollector(String gaugeMetricHelp, String gaugeMetricName, String labelName,
      ScheduledExecutorService asyncExecutorService) {
    super();
    this.gaugeMetricHelp = gaugeMetricHelp;
    this.gaugeMetricName = gaugeMetricName;
    this.asyncExecutorService = asyncExecutorService;
    this.healthChecks = new ConcurrentHashMap<String, HealthCheck>();
    this.labelNames = Collections.singletonList(labelName);
    this.lock = new Object();
  }

  /**
   * Builds a new instance of {@code HealthChecksCollector} with default values, except for the
   * custom ScheduledExecutorService.
   *
   * @param asyncExecutorService the custom {@code ScheduledExecutorService}
   * @see Builder#setAsyncExecutorService(ScheduledExecutorService)
   * @return a new instance of {@code HealthChecksCollector}.
   */
  public static HealthChecksCollector newInstance(ScheduledExecutorService asyncExecutorService) {
    return Builder.of().setAsyncExecutorService(asyncExecutorService).build();
  }

  /**
   * Builds a new instance of {@code HealthChecksCollector} with default values, except for the
   * custom Pool Size value.
   *
   * @param asyncExecutorPoolSize the custom number of threads to keep in the pool, even if they are
   *        idle.
   * @see Builder#setAsyncExecutorPoolSize(int)
   * @return a new instance of {@code HealthChecksCollector}.
   */
  public static HealthChecksCollector newInstance(int asyncExecutorPoolSize) {
    return Builder.of().setAsyncExecutorPoolSize(asyncExecutorPoolSize).build();
  }

  /**
   * Builds a new instance of {@code HealthChecksCollector} with default values.
   *
   * @see Builder#of()
   * @return a new instance of {@code HealthChecksCollector}.
   */
  public static HealthChecksCollector newInstance() {
    return Builder.of().build();
  }

  @Override
  public List<MetricFamilySamples> collect() {
    if (healthChecks.isEmpty()) {
      return Collections.emptyList();
    } else {
      GaugeMetricFamily checks =
          new GaugeMetricFamily(gaugeMetricName, gaugeMetricHelp, labelNames);
      for (Entry<String, HealthCheck> entry : healthChecks.entrySet()) {
        final String name = entry.getKey();
        final HealthCheck healthCheck = entry.getValue();
        final HealthStatus result = healthCheck.execute();
        checks.addMetric(Collections.singletonList(name), result.value);
      }
      return Collections.<MetricFamilySamples>singletonList(checks);
    }
  }

  /**
   * Link an application {@link HealthCheck}.
   *
   * @param name the name of the health check
   * @param healthCheck the {@link HealthCheck} instance
   * @return a modified instance of this {@code HealthChecksCollector}.
   * @throws IllegalArgumentException if the {@link HealthCheck} instance is already linked
   */
  public HealthChecksCollector addHealthCheck(String name, HealthCheck healthCheck) {
    synchronized (lock) {
      if (healthChecks.containsKey(requireNonNull(name, "name null"))) {
        throw new IllegalArgumentException("A health check named " + name + " already exists");
      }
      HealthCheck linked = requireNonNull(healthCheck, "healthCheck null");
      if (healthCheck.getClass().isAnnotationPresent(Async.class)) {
        linked = new AsyncHealthCheckDecorator(healthCheck, asyncExecutorService);
      }
      healthChecks.put(name, linked);
    }
    return this;
  }

  /**
   * Link a collection of application {@link HealthCheck}.
   *
   * @param healthChecks the {@code name} - {@link HealthCheck} map
   * @return a modified instance of this {@code HealthChecksCollector}.
   * @throws IllegalArgumentException if a {@link HealthCheck} instance is already linked
   */
  public <T extends HealthCheck> HealthChecksCollector addHealthChecks(
      Map<String, T> healthChecks) {
    for (Entry<String, T> healthCheck : requireNonNull(healthChecks, "healthChecks null")
        .entrySet()) {
      addHealthCheck(healthCheck.getKey(), healthCheck.getValue());
    }
    return this;
  }

  /**
   * Unlink an application {@link HealthCheck}.
   *
   * @param name the name of the health check
   * @return a modified instance of this {@code HealthChecksCollector}.
   */
  public HealthChecksCollector removeHealthCheck(String name) {
    synchronized (lock) {
      final HealthCheck healthCheck = healthChecks.remove(name);
      if (healthCheck instanceof AsyncHealthCheckDecorator) {
        ((AsyncHealthCheckDecorator) healthCheck).tearDown();
      }
    }
    return this;
  }

  /**
   * Unlink all the linked {@link HealthCheck}.
   *
   * @return a modified instance of this {@code HealthChecksCollector}.
   */
  public HealthChecksCollector clear() {
    for (String key : healthChecks.keySet()) {
      removeHealthCheck(key);
    }
    shutdown();
    return this;
  }

  /**
   * Returns a set of the names of all linked health checks.
   *
   * @return the names of all linked health checks
   */
  public SortedSet<String> getNames() {
    return Collections.unmodifiableSortedSet(new TreeSet<String>(healthChecks.keySet()));
  }

  /**
   * Returns an {@link HealthCheck} of a given name, or {@code null} if this Collector contains no
   * mapping for the name.
   *
   * @param name the name of the health check
   * @return the {@link HealthCheck} instance, or {@code null}
   */
  public HealthCheck getHealthCheck(String name) {
    return healthChecks.get(requireNonNull(name, "name null"));
  }

  /**
   * Runs the health check with the given name.
   *
   * @param name the health check's name
   * @return the result of the health check
   * @throws NoSuchElementException if there is no health check with the given name
   */
  public HealthStatus runHealthCheck(String name) {
    final HealthCheck healthCheck = healthChecks.get(name);
    if (healthCheck == null) {
      throw new NoSuchElementException("No health check named " + name + " exists");
    }
    return healthCheck.execute();
  }

  /**
   * Shuts down the scheduled executor for async health checks
   *
   * @return a modified instance of this {@code HealthChecksCollector}.
   */
  public HealthChecksCollector shutdown() {
    asyncExecutorService.shutdown();
    try {
      if (!asyncExecutorService.awaitTermination(1, TimeUnit.SECONDS)) {
        asyncExecutorService.shutdownNow();
      }
    } catch (InterruptedException ie) {
      asyncExecutorService.shutdownNow();
      Thread.currentThread().interrupt();
    }
    return this;
  }

  private static <T> T requireNonNull(T obj, String string) {
    if (obj == null) {
      throw new NullPointerException(string);
    }
    return obj;
  }

  /**
   * Builds instances of type {@link HealthChecksCollector HealthChecksCollector}. Initialize
   * attributes and then invoke the {@link #build()} method to create an immutable instance.
   *
   * <p><em>{@code Builder} is not thread-safe and generally should not be stored in a field or
   * collection, but instead used immediately to create instances.</em>
   */
  public static final class Builder {
    private static final int ASYNC_EXECUTOR_POOL_SIZE = 2;
    private String gaugeMetricHelp = "Health check status results";
    private String gaugeMetricName = "health_check_status";
    private String gaugeMetricLabelName = "system";
    private ScheduledExecutorService asyncExecutorService =
        createExecutorService(ASYNC_EXECUTOR_POOL_SIZE);

    /**
     * Creates a new {@code Builder} instance with default values.
     * <ul>
     * <li>Metric Help = {@code Health check status results}</li>
     * <li>Metric Name = {@code health_check_status}</li>
     * <li>Metric Label = {@code system}</li>
     * <li>ExecutorService = {@code ScheduledExecutorService} with thread pool size of
     * {@code 2}</li>
     * </ul>
     *
     * @return {@code this} builder for use in a chained invocation
     */
    public static Builder of() {
      return new Builder();
    }

    /**
     * Fill a builder with attribute values from the provided {@code HealthChecksCollector}
     * instance.
     * 
     * @param instance The instance from which to copy values
     * @return {@code this} builder for use in a chained invocation
     */
    public Builder from(HealthChecksCollector instance) {
      requireNonNull(instance, "instance");
      setGaugeMetricHelp(instance.gaugeMetricHelp);
      setGaugeMetricName(instance.gaugeMetricName);
      setGaugeMetricLabelName(instance.labelNames.get(0));
      setAsyncExecutorService(instance.asyncExecutorService);
      return this;
    }

    /**
     * Builds a new {@link HealthChecksCollector HealthChecksCollector}.
     *
     * @return An instance of HealthChecksCollector
     */
    public HealthChecksCollector build() {
      return new HealthChecksCollector(gaugeMetricHelp, gaugeMetricName, gaugeMetricLabelName,
          asyncExecutorService);
    }

    /**
     * Initializes the value for the gauge metric's help attribute.
     *
     * @param gaugeMetricHelp The value for gaugeMetricHelp
     * @return {@code this} builder for use in a chained invocation
     */
    public Builder setGaugeMetricHelp(String gaugeMetricHelp) {
      this.gaugeMetricHelp = requireNonNull(gaugeMetricHelp, "gaugeMetricHelp null");
      return this;
    }

    /**
     * Initializes the value for the gauge metric's name attribute.
     *
     * @param gaugeMetricName The value for gaugeMetricName
     * @return {@code this} builder for use in a chained invocation
     */
    public Builder setGaugeMetricName(String gaugeMetricName) {
      this.gaugeMetricName = requireNonNull(gaugeMetricName, "gaugeMetricName null");
      return this;
    }

    /**
     * Initializes the value for the gauge metric's label attribute.
     *
     * @param gaugeMetricLabelName The value for labelName
     * @return {@code this} builder for use in a chained invocation
     */
    public Builder setGaugeMetricLabelName(String gaugeMetricLabelName) {
      this.gaugeMetricLabelName = requireNonNull(gaugeMetricLabelName, "gaugeMetricLabelName null");
      return this;
    }

    /**
     * Builds and initialize an {@code ScheduledExecutorService} with the specified pool size value.
     *
     * @param asyncExecutorPoolSize The value for asyncExecutorPoolSize
     * @return {@code this} builder for use in a chained invocation
     */
    public Builder setAsyncExecutorPoolSize(int asyncExecutorPoolSize) {
      this.asyncExecutorService = createExecutorService(asyncExecutorPoolSize);
      return this;
    }

    /**
     * Initializes the value for the {@code ScheduledExecutorService}.
     *
     * @param asyncExecutorService The value for asyncExecutorService
     * @return {@code this} builder for use in a chained invocation
     */
    public Builder setAsyncExecutorService(ScheduledExecutorService asyncExecutorService) {
      this.asyncExecutorService = requireNonNull(asyncExecutorService, "asyncExecutorService null");
      return this;
    }

    private static ScheduledExecutorService createExecutorService(int corePoolSize) {
      ScheduledExecutorService asyncExecutorService =
          Executors.newScheduledThreadPool(corePoolSize, Executors.defaultThreadFactory());
      try {
        Method method =
            asyncExecutorService.getClass().getMethod("setRemoveOnCancelPolicy", Boolean.TYPE);
        method.invoke(asyncExecutorService, true);
      } catch (NoSuchMethodException ex) {
        logSetExecutorCancellationPolicyFailure(ex);
      } catch (IllegalAccessException ex) {
        logSetExecutorCancellationPolicyFailure(ex);
      } catch (InvocationTargetException ex) {
        logSetExecutorCancellationPolicyFailure(ex);
      }
      return asyncExecutorService;
    }

    private static void logSetExecutorCancellationPolicyFailure(Exception ex) {
      System.err.println(String.format(
          "Tried but failed to set executor cancellation policy to remove on cancel "
              + "which has been introduced in Java 7. This could result in a memory leak "
              + "if many asynchronous health checks are registered and removed because "
              + "cancellation does not actually remove them from the executor. %s",
          ex.getMessage()));
    }
  }

}
