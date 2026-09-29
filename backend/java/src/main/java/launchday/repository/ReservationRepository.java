package launchday.repository;

import launchday.model.AuditEvent;
import launchday.model.StoredReservation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class ReservationRepository {
    private final DataSource dataSource;

    public ReservationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void lockIdempotencyKey(Connection db, String userId, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))")) {
            statement.setString(1, userId);
            statement.setString(2, idempotencyKey);
            statement.executeQuery();
        }
    }

    public StoredReservation findByIdempotencyKey(Connection db, String userId, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT id, item_id, user_id, qty, status, mode, reason, request_hash " +
                        "FROM reservations WHERE user_id=? AND idempotency_key=? FOR UPDATE")) {
            statement.setString(1, userId);
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return reservation(result);
            }
        }
    }

    public void persist(Connection db, StoredReservation reservation, String idempotencyKey,
                        Integer authorityStatusCode, String authorityResponse) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "INSERT INTO reservations " +
                        "(id, item_id, user_id, qty, status, idempotency_key, request_hash, mode, reason) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, reservation.id());
            statement.setString(2, reservation.itemId());
            statement.setString(3, reservation.userId());
            statement.setInt(4, reservation.qty());
            statement.setString(5, reservation.status());
            statement.setString(6, idempotencyKey);
            statement.setString(7, reservation.requestHash());
            statement.setString(8, reservation.mode());
            if (reservation.reason() == null) statement.setNull(9, Types.VARCHAR);
            else statement.setString(9, reservation.reason());
            statement.executeUpdate();
        }
        appendAuditEvent(db, reservation.id(), null, reservation.status(), reservation.reason(),
                authorityStatusCode, authorityResponse);
    }

    public StoredReservation findOldestPending(Connection db) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT id, item_id, user_id, qty, status, mode, reason, request_hash " +
                        "FROM reservations WHERE status='pending' " +
                        "ORDER BY created_at, id LIMIT 1 FOR UPDATE")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return reservation(result);
            }
        }
    }

    public void updatePendingStatus(Connection db, String reservationId, String status, String reason,
                                    int authorityStatusCode, String authorityResponse) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "UPDATE reservations SET status=?, reason=?, updated_at=now() " +
                        "WHERE id=? AND status='pending'")) {
            statement.setString(1, status);
            if (reason == null) statement.setNull(2, Types.VARCHAR);
            else statement.setString(2, reason);
            statement.setString(3, reservationId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("pending reservation changed during reconciliation");
            }
        }
        appendAuditEvent(db, reservationId, "pending", status, reason,
                authorityStatusCode, authorityResponse);
    }

    public boolean hasPendingReservations() throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement statement = db.prepareStatement(
                     "SELECT EXISTS (SELECT 1 FROM reservations WHERE status='pending')");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getBoolean(1);
        }
    }

    public StoredReservation findById(String reservationId) throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement statement = db.prepareStatement(
                     "SELECT id, item_id, user_id, qty, status, mode, reason, request_hash " +
                             "FROM reservations WHERE id=?")) {
            statement.setString(1, reservationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? reservation(result) : null;
            }
        }
    }

    public List<StoredReservation> list(String userId) throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement statement = db.prepareStatement(
                     "SELECT id, item_id, user_id, qty, status, mode, reason, request_hash " +
                             "FROM reservations WHERE (? IS NULL OR user_id=?) ORDER BY created_at, id")) {
            statement.setString(1, userId);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                List<StoredReservation> reservations = new ArrayList<>();
                while (result.next()) reservations.add(reservation(result));
                return reservations;
            }
        }
    }

    public List<AuditEvent> audit(String reservationId) throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement statement = db.prepareStatement(
                     "SELECT old_status, new_status, reason, authority_status_code, " +
                             "authority_response, created_at FROM reservation_events " +
                             "WHERE reservation_id=? ORDER BY created_at, id")) {
            statement.setString(1, reservationId);
            try (ResultSet result = statement.executeQuery()) {
                List<AuditEvent> events = new ArrayList<>();
                while (result.next()) {
                    events.add(new AuditEvent(
                            result.getString(1), result.getString(2), result.getString(3),
                            (Integer) result.getObject(4), result.getString(5),
                            result.getTimestamp(6).toInstant()));
                }
                return events;
            }
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement statement = db.prepareStatement("DELETE FROM reservations")) {
            statement.executeUpdate();
        }
    }

    private static void appendAuditEvent(Connection db, String reservationId, String oldStatus,
                                         String newStatus, String reason, Integer authorityStatusCode,
                                         String authorityResponse) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "INSERT INTO reservation_events " +
                        "(reservation_id, old_status, new_status, reason, authority_status_code, authority_response) " +
                        "VALUES (?,?,?,?,?,CAST(? AS jsonb))")) {
            statement.setString(1, reservationId);
            if (oldStatus == null) statement.setNull(2, Types.VARCHAR);
            else statement.setString(2, oldStatus);
            statement.setString(3, newStatus);
            if (reason == null) statement.setNull(4, Types.VARCHAR);
            else statement.setString(4, reason);
            if (authorityStatusCode == null) statement.setNull(5, Types.INTEGER);
            else statement.setInt(5, authorityStatusCode);
            if (authorityResponse == null) statement.setNull(6, Types.VARCHAR);
            else statement.setString(6, authorityResponse);
            statement.executeUpdate();
        }
    }

    private static StoredReservation reservation(ResultSet result) throws SQLException {
        return new StoredReservation(result.getString(1), result.getString(2), result.getString(3),
                result.getInt(4), result.getString(5), result.getString(6), result.getString(7),
                result.getString(8));
    }
}