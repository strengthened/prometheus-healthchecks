package com.github.strengthened.prometheus.healthchecks;

/**
 * A health check for a component of your application.
 */
public abstract class HealthCheck {

  /**
   * Perform a check of the application component.
   *
   * @return if the component is healthy, a healthy {@link HealthStatus}; otherwise, an unhealthy
   *         {@link HealthStatus} with a descriptive error message or exception
   * @throws Exception if there is an unhandled error during the health check; this will result in a
   *         failed health check
   */
  protected abstract HealthStatus check() throws Exception;

  /**
   * Executes the health check, catching and handling any exceptions raised by {@link #check()}.
   *
   * @return if the component is healthy, a healthy {@link HealthStatus}; otherwise, an unhealthy
   *         {@link HealthStatus} with a descriptive error message or exception
   */
  public HealthStatus execute() {
    try {
      return check();
    } catch (Exception ignored) {
      return HealthStatus.UNHEALTHY;
    }
  }

}
