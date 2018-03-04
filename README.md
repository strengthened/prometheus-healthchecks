# Prometheus health checks collector

This library includes a custom collector for the [Prometheus JVM Client](https://github.com/prometheus/client_java).

The `HealthChecksCollector` creates a `GaugeMetricFamily` of name `health_check_status`
and a label `system` which holds the health-checker's name.

Possible values are:

 * `1.0`: means the system is healthy.
 * `0.0`: means the system is unhealthy.

To link the `HealthCheck`s add them to the registered `HealthChecksCollector`.

```java
class DbHealthCheck extends HealthCheck {
  @Override
  public HealthStatus check() throws Exception {
    return checkDbConnection() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;
  }
  boolean checkDbConnection() {}
}

HealthChecksCollector healthchecksMetrics = HealthChecksCollector.newInstance().register();

DbHealthCheck dbHealthCheck = new DbHealthCheck();
FsHealthCheck fsHealthCheck = // ...

healthchecksMetrics.addHealthCheck("database", dbHealthCheck)
    .addHealthCheck("filesystem", fsHealthCheck);
```

Resulting samples:

```
# HELP health_check_status Health check status results
# TYPE health_check_status gauge
health_check_status{system="database",} 1.0
health_check_status{system="filesystem",} 0.0
```

To customize the gauge data (name, label and help string) you can use the `Builder` class:

```java
import com.github.strengthened.prometheus.healthchecks.HealthChecksCollector.Builder;

HealthChecksCollector collector = Builder.of().setGaugeMetricName("my_metric_name")
    .setGaugeMetricLabelName("my_label").build();
```

It also possible to make an asynchronous `HealthCheck` annotating it with `@Async`

```java
@Async(initialState=HealthStatus.UNHEALTHY, unit=TimeUnit.SECONDS, period=1)
class TestHealthCheck extends HealthCheck {
  // Executed every second starting with an unhealthy status.
}
```

For further details see the [API Document](https://strengthened.github.io/prometheus-healthchecks/apidocs/).

### Bonus facts

#### Alertmanager

An example rules file with an alert would be:

```
groups:
- name: example
  rules:

  # Alert for any system that is unreachable for >2 minutes.
  - alert: HealthCheck
    expr: health_check_status < 1
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "System {{ $labels.system }} down"
      description: "System {{ $labels.system }} of instance {{ $labels.instance }} of job {{ $labels.job }} has been down for more than 2 minutes."
```

#### Grafana

You can use the Grafana's [Discrete Panel](https://grafana.com/plugins/natel-discrete-panel)
to show health's state transitions in an horizontal graph!