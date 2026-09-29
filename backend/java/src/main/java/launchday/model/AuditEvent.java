package launchday.model;

import java.time.Instant;

public record AuditEvent(String oldStatus, String newStatus, String reason, Integer authorityStatusCode,
                         String authorityResponse, Instant timestamp) {}