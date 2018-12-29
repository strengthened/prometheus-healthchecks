package com.github.strengthened.prometheus.healthchecks;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.awaitility.Duration.*;

import com.github.strengthened.prometheus.healthchecks.Async.ScheduleType;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HealthChecksCollectorTest {

  CollectorRegistry registry;
  HealthChecksCollector underTest;

  @Before
  public void before() {
    registry = new CollectorRegistry();
    underTest = HealthChecksCollector.newInstance().register(registry);
  }

  @After
  public void after() {
    underTest.clear();
  }

  @Test
  public void shouldBuildNewInstance() throws Exception {
    assertThat(HealthChecksCollector.newInstance()).isNotNull();
    assertThat(HealthChecksCollector.newInstance(2)).isNotNull();
    assertThat(HealthChecksCollector.newInstance(Executors.newScheduledThreadPool(2))).isNotNull();
  }

  @Test
  public void shouldBeEmpty() throws Exception {
    assertNoMetric(registry, "name");
    assertThat(underTest.collect()).isEmpty();
    assertThat(underTest.getNames()).isEmpty();
  }

  @Test
  public void shouldRegisterAndCollect() throws Exception {
    String nameHealthy = "nameHealthy";
    TestHealthCheck healthCheckHealthy = new TestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(nameHealthy, healthCheckHealthy);
    String nameUnhealthy = "nameUnhealthy";
    TestHealthCheck healthCheckUnhealthy = new TestHealthCheck(HealthStatus.UNHEALTHY);
    underTest.addHealthCheck(nameUnhealthy, healthCheckUnhealthy);
    assertMetric(registry, nameHealthy, HealthStatus.HEALTHY.value);
    assertMetric(registry, nameUnhealthy, HealthStatus.UNHEALTHY.value);
    assertThat(healthCheckHealthy.getCalled()).isGreaterThan(0);
    assertThat(healthCheckUnhealthy.getCalled()).isGreaterThan(0);
  }

  @Test
  public void shouldExceptionBeUnhealthy() throws Exception {
    String nameUnhealthy = "nameUnhealthy";
    ExceptionalHealthCheck healthCheckUnhealthy = new ExceptionalHealthCheck();
    underTest.addHealthCheck(nameUnhealthy, healthCheckUnhealthy);
    assertMetric(registry, nameUnhealthy, HealthStatus.UNHEALTHY.value);
  }

  @Test
  public void shouldChangeValue() throws Exception {
    String name = "name";
    TestHealthCheck healthCheck = new TestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    assertMetric(registry, name, HealthStatus.HEALTHY.value);
    healthCheck.setResult(HealthStatus.UNHEALTHY);
    assertMetric(registry, name, HealthStatus.UNHEALTHY.value);
  }

  @Test
  public void shouldChangeValueAsync() throws Exception {
    String name = "name";
    final AsyncTestHealthCheck healthCheck = new AsyncTestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    assertMetric(registry, name, HealthStatus.UNHEALTHY.value);
    await().atLeast(ONE_HUNDRED_MILLISECONDS).until(new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        return healthCheck.getCalled() > 0;
      }});
    assertMetric(registry, name, HealthStatus.HEALTHY.value);
  }

  @Test
  public void shouldShutDown() throws Exception {
    String name = "name";
    AsyncTestHealthCheck healthCheck = new AsyncTestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    underTest.shutdown();
    assertMetric(registry, name, HealthStatus.UNHEALTHY.value);
    await().forever().timeout(ONE_SECOND);
    assertMetric(registry, name, HealthStatus.UNHEALTHY.value);
  }

  @Test
  public void shouldNotAddEmptyValues() throws Exception {
    String name = "name";
    AsyncTestHealthCheck healthCheck = new AsyncTestHealthCheck(HealthStatus.HEALTHY);
    try {
      underTest.addHealthCheck(null, healthCheck);
      fail("Missing NullPointerException");
    } catch (Exception ex) {
      assertThat(ex).isOfAnyClassIn(NullPointerException.class);
    }
    try {
      underTest.addHealthCheck(name, null);
      fail("Missing NullPointerException");
    } catch (Exception ex) {
      assertThat(ex).isOfAnyClassIn(NullPointerException.class);
    }
  }

  @Test
  public void shouldNotAddSameName() throws Exception {
    String name = "name";
    AsyncTestHealthCheck healthCheck = new AsyncTestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    try {
      underTest.addHealthCheck(name, healthCheck);
      fail("Missing IllegalArgumentException");
    } catch (Exception ex) {
      assertThat(ex).isOfAnyClassIn(IllegalArgumentException.class);
    }
  }

  @Test
  public void shouldClear() throws Exception {
    String name = "name";
    TestHealthCheck healthCheck = new TestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    assertThat(underTest.getHealthCheck(name)).isEqualTo(healthCheck);
    underTest.clear();
    assertThat(underTest.getHealthCheck(name)).isNull();
    assertThat(healthCheck.getCalled()).isEqualTo(0);
  }

  @Test
  public void shouldRemove() throws Exception {
    String name = "name";
    TestHealthCheck healthCheck = new TestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    assertThat(underTest.getHealthCheck(name)).isEqualTo(healthCheck);
    underTest.removeHealthCheck(name);
    assertThat(underTest.getHealthCheck(name)).isNull();
    assertThat(healthCheck.getCalled()).isEqualTo(0);
  }

  @Test
  public void shouldGetNames() throws Exception {
    String name = "name";
    TestHealthCheck healthCheck = new TestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    assertThat(underTest.getNames()).containsExactly(name);
    assertThat(healthCheck.getCalled()).isEqualTo(0);
  }

  @Test
  public void shouldBeCalled() throws Exception {
    String name = "name";
    TestHealthCheck healthCheck = new TestHealthCheck(HealthStatus.HEALTHY);
    underTest.addHealthCheck(name, healthCheck);
    underTest.runHealthCheck(name);
    assertThat(healthCheck.getCalled()).isGreaterThan(0);
  }

  @Test
  public void shouldNotRunCheck() throws Exception {
    String name = "name";
    try {
      underTest.runHealthCheck(name);
      fail("Missing NoSuchElementException");
    } catch (Exception ex) {
      assertThat(ex).isOfAnyClassIn(NoSuchElementException.class);
    }
  }

  @Test
  public void shouldRegisterMultiple() throws Exception {
    Map<String, TestHealthCheck> healthChecks = new HashMap<String, TestHealthCheck>();
    String nameHealthy = "nameHealthy";
    TestHealthCheck healthCheckHealthy = new TestHealthCheck(HealthStatus.HEALTHY);
    healthChecks.put(nameHealthy, healthCheckHealthy);
    String nameUnhealthy = "nameUnhealthy";
    TestHealthCheck healthCheckUnhealthy = new TestHealthCheck(HealthStatus.UNHEALTHY);
    healthChecks.put(nameUnhealthy, healthCheckUnhealthy);
    underTest.addHealthChecks(healthChecks);
    assertThat(underTest.getHealthCheck(nameHealthy)).isEqualTo(healthCheckHealthy);
    assertThat(underTest.getHealthCheck(nameUnhealthy)).isEqualTo(healthCheckUnhealthy);
  }

  @Test
  @Ignore // Because is not a test
  public void shouldProduceTextOutput() throws Exception {
    shouldRegisterAndCollect();
    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out));
    TextFormat.write004(writer, registry.metricFamilySamples());
    writer.close();
  }

  private void assertMetric(CollectorRegistry registry, String name, double value) {
    assertThat(registry.getSampleValue(underTest.gaugeMetricName,
        new String[] {underTest.labelNames.get(0)}, new String[] {name})).isEqualTo(value);
  }

  private void assertNoMetric(CollectorRegistry registry, String name) {
    assertThat(registry.getSampleValue(underTest.gaugeMetricName,
        new String[] {underTest.labelNames.get(0)}, new String[] {name})).isNull();
  }

  private static class TestHealthCheck extends HealthCheck {

    HealthStatus result;
    long called;

    public TestHealthCheck(HealthStatus result) {
      this.result = result;
      this.called = 0;
    }

    public void setResult(HealthStatus result) {
      this.result = result;
    }

    public long getCalled() {
      return called;
    }

    @Override
    public HealthStatus check() throws Exception {
      called++;
      return result;
    }

  }

  @Async(initialState = HealthStatus.UNHEALTHY, period = 100, unit = TimeUnit.MILLISECONDS,
      scheduleType = ScheduleType.FIXED_DELAY)
  private static class AsyncTestHealthCheck extends TestHealthCheck {
    public AsyncTestHealthCheck(HealthStatus result) {
      super(result);
    }
  }

  private static class ExceptionalHealthCheck extends HealthCheck {

    @Override
    protected HealthStatus check() throws Exception {
      throw new IllegalStateException();
    }

  }

}
