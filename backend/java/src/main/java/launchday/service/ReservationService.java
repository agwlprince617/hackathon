package launchday.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import launchday.client.AuthorityClient;
import launchday.mapper.ReservationJson;
import launchday.model.ApiResult;
import launchday.model.ReconcileResult;
import launchday.model.StoredReservation;
import launchday.repository.ItemRepository;
import launchday.repository.ReservationRepository;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class ReservationService {
    private final DataSource dataSource;
    private final AuthorityClient authorityClient;
    private final AuthorityMonitor authorityMonitor;
    private final ReservationRepository reservationRepository;
    private final ItemRepository itemRepository;
    private final int standinMaxPerItem;
    private final ReentrantLock reconciliationLock = new ReentrantLock();

    private record ReconcileStep(String outcome, boolean queueEmpty) {}

    public ReservationService(DataSource dataSource, AuthorityClient authorityClient,
                              AuthorityMonitor authorityMonitor, ReservationRepository reservationRepository,
                              ItemRepository itemRepository, int standinMaxPerItem) {
        this.dataSource = dataSource;
        this.authorityClient = authorityClient;
        this.authorityMonitor = authorityMonitor;
        this.reservationRepository = reservationRepository;
        this.itemRepository = itemRepository;
        this.standinMaxPerItem = standinMaxPerItem;
    }

    public ApiResult reserve(String userId, String itemId, int quantity, String idempotencyKey)
            throws SQLException, InterruptedException {
        String hash = requestHash(itemId, quantity);
        try (Connection db = dataSource.getConnection()) {
            db.setAutoCommit(false);
            try {
                reservationRepository.lockIdempotencyKey(db, userId, idempotencyKey);
                StoredReservation existing = reservationRepository.findByIdempotencyKey(
                        db, userId, idempotencyKey);
                if (existing != null) {
                    db.commit();
                    if (!existing.requestHash().equals(hash)) {
                        return new ApiResult(422, Map.of("error", "idempotency_key_reused"));
                    }
                    return resultFor(existing);
                }

                String reservationId = UUID.randomUUID().toString();
                if (authorityMonitor.isStandin()) {
                    ApiResult result = reserveInStandin(
                            db, reservationId, userId, itemId, quantity, idempotencyKey, hash);
                    db.commit();
                    return result;
                }

                HttpResponse<String> authorityResponse;
                try {
                    authorityResponse = authorityClient.reserve(
                            reservationId, itemId, userId, quantity);
                } catch (HttpTimeoutException exception) {
                    authorityMonitor.recordFailure(exception);
                    ApiResult result = reserveInStandin(
                            db, reservationId, userId, itemId, quantity, idempotencyKey, hash);
                    db.commit();
                    return result;
                } catch (IOException exception) {
                    authorityMonitor.recordFailure(exception);
                    ApiResult result = reserveInStandin(
                            db, reservationId, userId, itemId, quantity, idempotencyKey, hash);
                    db.commit();
                    return result;
                }
                if (authorityResponse.statusCode() >= 500) {
                    authorityMonitor.recordFailure(new IOException(
                            "authority status " + authorityResponse.statusCode()));
                    ApiResult result = reserveInStandin(
                            db, reservationId, userId, itemId, quantity, idempotencyKey, hash);
                    db.commit();
                    return result;
                }
                if (authorityResponse.statusCode() != 201 && authorityResponse.statusCode() != 409) {
                    db.rollback();
                    return new ApiResult(
                            authorityResponse.statusCode(), JsonParser.parseString(authorityResponse.body()));
                }

                JsonObject authorityBody = JsonParser.parseString(authorityResponse.body()).getAsJsonObject();
                authorityMonitor.recordSuccessfulRequest();
                String status = authorityResponse.statusCode() == 201 ? "confirmed" : "rejected";
                String reason = string(authorityBody, "reason");
                StoredReservation reservation = new StoredReservation(
                        reservationId, itemId, userId, quantity, status, "live", reason, hash);
                reservationRepository.persist(db, reservation, idempotencyKey,
                        authorityResponse.statusCode(), authorityResponse.body());
                Integer available = integer(authorityBody, "available", -1);
                if (available != null && available >= 0) {
                    itemRepository.updateAfterAuthority(db, itemId, available);
                }
                db.commit();
                return resultFor(reservation);
            } catch (SQLException | InterruptedException | RuntimeException exception) {
                db.rollback();
                throw exception;
            }
        }
    }

    public boolean reconcileAfterRecovery() {
        try {
            return reconcilePending().complete();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException exception) {
            authorityMonitor.recordFailure(exception);
            return false;
        } catch (SQLException | IllegalStateException exception) {
            return false;
        }
    }

    public ReconcileResult reconcilePending() throws SQLException, IOException, InterruptedException {
        reconciliationLock.lockInterruptibly();
        try {
            int replayed = 0;
            int confirmed = 0;
            int reversed = 0;
            while (true) {
                ReconcileStep step = reconcileNextPending();
                if (step.queueEmpty()) {
                    return new ReconcileResult(replayed, confirmed, reversed, true);
                }
                replayed++;
                if ("confirmed".equals(step.outcome())) confirmed++;
                if ("reversed".equals(step.outcome())) reversed++;
            }
        } finally {
            reconciliationLock.unlock();
        }
    }

    private ApiResult reserveInStandin(Connection db, String reservationId, String userId, String itemId,
                                       int quantity, String idempotencyKey, String hash) throws SQLException {
        Integer shadowAvailable = itemRepository.lockAndGetShadowAvailable(db, itemId);
        int pendingQuantity = itemRepository.pendingQuantity(db, itemId);
        String status;
        String reason = null;
        if (pendingQuantity + quantity > standinMaxPerItem) {
            status = "rejected";
            reason = "standin_limit_reached";
        } else if (shadowAvailable == null || shadowAvailable < quantity) {
            status = "rejected";
            reason = "insufficient_stock";
        } else {
            status = "pending";
            itemRepository.decrementShadow(db, itemId, quantity);
        }

        StoredReservation reservation = new StoredReservation(
                reservationId, itemId, userId, quantity, status, "standin", reason, hash);
        reservationRepository.persist(db, reservation, idempotencyKey, null, null);
        return resultFor(reservation);
    }

    private ReconcileStep reconcileNextPending() throws SQLException, IOException, InterruptedException {
        try (Connection db = dataSource.getConnection()) {
            db.setAutoCommit(false);
            try {
                StoredReservation pending = reservationRepository.findOldestPending(db);
                if (pending == null) {
                    db.commit();
                    return new ReconcileStep(null, true);
                }

                HttpResponse<String> response;
                try {
                    response = authorityClient.reserve(
                            pending.id(), pending.itemId(), pending.userId(), pending.qty());
                } catch (IOException exception) {
                    authorityMonitor.recordFailure(exception);
                    throw exception;
                }
                if (response.statusCode() != 201 && response.statusCode() != 409) {
                    IOException failure = new IOException("authority status " + response.statusCode());
                    authorityMonitor.recordFailure(failure);
                    throw failure;
                }

                JsonObject authorityBody = JsonParser.parseString(response.body()).getAsJsonObject();
                String outcome = response.statusCode() == 201 ? "confirmed" : "reversed";
                String reason = string(authorityBody, "reason");
                reservationRepository.updatePendingStatus(db, pending.id(), outcome, reason,
                        response.statusCode(), response.body());
                Integer available = integer(authorityBody, "available", -1);
                if (available != null && available >= 0) {
                    itemRepository.updateAfterAuthority(db, pending.itemId(), available);
                }
                db.commit();
                return new ReconcileStep(outcome, false);
            } catch (SQLException | IOException | InterruptedException | RuntimeException exception) {
                db.rollback();
                throw exception;
            }
        }
    }

    private static ApiResult resultFor(StoredReservation reservation) {
        int statusCode = switch (reservation.status()) {
            case "confirmed" -> 201;
            case "pending" -> 202;
            default -> 409;
        };
        return new ApiResult(statusCode, ReservationJson.from(reservation));
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return null;
        try {
            String value = object.get(key).getAsString().trim();
            return value.isEmpty() ? null : value;
        } catch (UnsupportedOperationException | ClassCastException exception) {
            return null;
        }
    }

    private static Integer integer(JsonObject object, String key, int defaultValue) {
        if (object == null || !object.has(key)) return defaultValue;
        try {
            return object.get(key).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException | ClassCastException exception) {
            return null;
        }
    }

    private static String requestHash(String itemId, int quantity) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((itemId + "\n" + quantity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}