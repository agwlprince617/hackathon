package launchday.repository;

import launchday.model.CachedItem;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ItemRepository {
    private final DataSource dataSource;

    public ItemRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public CachedItem findCached(String itemId) throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement statement = db.prepareStatement(
                     "SELECT name, available, shadow_available FROM items WHERE id=?")) {
            statement.setString(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new CachedItem(itemId, result.getString(1), result.getInt(2), result.getInt(3));
            }
        }
    }

    public Integer lockAndGetShadowAvailable(Connection db, String itemId) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT shadow_available FROM items WHERE id=? FOR UPDATE")) {
            statement.setString(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : null;
            }
        }
    }

    public int pendingQuantity(Connection db, String itemId) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT COALESCE(SUM(qty), 0) FROM reservations WHERE item_id=? AND status='pending'")) {
            statement.setString(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public void decrementShadow(Connection db, String itemId, int quantity) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "UPDATE items SET shadow_available=shadow_available-?, updated_at=now() WHERE id=?")) {
            statement.setInt(1, quantity);
            statement.setString(2, itemId);
            statement.executeUpdate();
        }
    }

    public void updateAfterAuthority(Connection db, String itemId, int available) throws SQLException {
        int shadowAvailable = Math.max(0, available - pendingQuantity(db, itemId));
        try (PreparedStatement statement = db.prepareStatement(
                "UPDATE items SET available=?, shadow_available=?, last_synced_at=now(), updated_at=now() " +
                        "WHERE id=?")) {
            statement.setInt(1, available);
            statement.setInt(2, shadowAvailable);
            statement.setString(3, itemId);
            statement.executeUpdate();
        }
    }

    public int storeAuthoritySnapshot(String itemId, String name, int available) throws SQLException {
        try (Connection db = dataSource.getConnection()) {
            db.setAutoCommit(false);
            try {
                int shadowAvailable = Math.max(0, available - pendingQuantity(db, itemId));
                try (PreparedStatement statement = db.prepareStatement(
                        "INSERT INTO items (id, name, available, shadow_available, last_synced_at, updated_at) " +
                                "VALUES (?,?,?,?,now(),now()) ON CONFLICT (id) DO UPDATE SET " +
                                "name=EXCLUDED.name, available=EXCLUDED.available, " +
                                "shadow_available=EXCLUDED.shadow_available, last_synced_at=now(), updated_at=now()")) {
                    statement.setString(1, itemId);
                    statement.setString(2, name);
                    statement.setInt(3, available);
                    statement.setInt(4, shadowAvailable);
                    statement.executeUpdate();
                }
                db.commit();
                return shadowAvailable;
            } catch (SQLException exception) {
                db.rollback();
                throw exception;
            }
        }
    }

    public List<String> itemIds() throws SQLException {
        try (Connection db = dataSource.getConnection();
             PreparedStatement statement = db.prepareStatement("SELECT id FROM items ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            List<String> itemIds = new ArrayList<>();
            while (result.next()) itemIds.add(result.getString(1));
            return itemIds;
        }
    }
}