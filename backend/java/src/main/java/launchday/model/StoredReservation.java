package launchday.model;

public record StoredReservation(String id, String itemId, String userId, int qty, String status,
                                String mode, String reason, String requestHash) {}