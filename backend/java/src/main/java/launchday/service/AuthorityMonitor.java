package launchday.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class AuthorityMonitor implements AutoCloseable {
    private final HttpClient client;
    private final String authorityUrl;
    private final int healthyChecksRequired;
    private final ScheduledExecutorService scheduler;
    private String authorityStatus = "healthy";
    private String mode = "live";
    private int consecutiveHealthyChecks;
    private BooleanSupplier recoveryAction = () -> true;
    private boolean recoveryRunning;
    private boolean started;

    public AuthorityMonitor(HttpClient client, String authorityUrl, long pollMillis, int healthyChecksRequired) {
        this.client = client;
        this.authorityUrl = authorityUrl;
        this.healthyChecksRequired = healthyChecksRequired;
        this.consecutiveHealthyChecks = healthyChecksRequired;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("authority-monitor").factory());
        this.pollMillis = pollMillis;
    }

    private final long pollMillis;

    public synchronized void setRecoveryAction(BooleanSupplier recoveryAction) {
        this.recoveryAction = recoveryAction;
    }

    public synchronized void forceStandin() {
        mode = "standin";
        consecutiveHealthyChecks = 0;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        scheduler.scheduleWithFixedDelay(this::poll, 0, pollMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized String authorityStatus() {
        return authorityStatus;
    }

    public synchronized String mode() {
        return mode;
    }

    synchronized boolean isStandin() {
        return "standin".equals(mode);
    }

    public synchronized void recordFailure(Throwable failure) {
        authorityStatus = failure instanceof HttpTimeoutException ? "degraded" : "unreachable";
        mode = "standin";
        consecutiveHealthyChecks = 0;
    }

    synchronized void recordSuccessfulRequest() {
        authorityStatus = "healthy";
        if ("live".equals(mode)) {
            consecutiveHealthyChecks = healthyChecksRequired;
        }
    }

    private void poll() {
        try {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(authorityUrl + "/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                recordHealthyProbe();
            } else {
                recordFailure(new IllegalStateException("authority health status " + response.statusCode()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            recordFailure(e);
        }
    }

    private void recordHealthyProbe() {
        BooleanSupplier action = null;
        synchronized (this) {
            authorityStatus = "healthy";
            if ("standin".equals(mode)) {
                consecutiveHealthyChecks++;
                if (consecutiveHealthyChecks >= healthyChecksRequired && !recoveryRunning) {
                    recoveryRunning = true;
                    action = recoveryAction;
                }
            } else {
                consecutiveHealthyChecks = healthyChecksRequired;
            }
        }
        if (action == null) return;

        boolean recovered = false;
        try {
            recovered = action.getAsBoolean();
        } finally {
            synchronized (this) {
                recoveryRunning = false;
                if (recovered && "healthy".equals(authorityStatus)
                        && consecutiveHealthyChecks >= healthyChecksRequired) {
                    mode = "live";
                }
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}