package com.github.strengthened.prometheus.healthchecks;

/**
 * Enum representing health states.
 */
public enum HealthStatus {
  /** Healthy system. */
  HEALTHY(1.0),
  /** Unhealthy system. */
  UNHEALTHY(0.0);

  public double value;

  private HealthStatus(double value) {
    this.value = value;
  }
}
