package launchday.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import launchday.mapper.ReservationJson;
import launchday.model.ApiResult;
import launchday.model.AuditEvent;
import launchday.model.ReconcileResult;
import launchday.model.StoredReservation;
import launchday.repository.ReservationRepository;
import launchday.service.AuthorityMonitor;
import launchday.service.ItemService;
import launchday.service.ReservationService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReservationApi {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final AuthorityMonitor authorityMonitor;
    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;
    private final ItemService itemService;

    public ReservationApi(AuthorityMonitor authorityMonitor, ReservationService reservationService,
                          ReservationRepository reservationRepository, ItemService itemService) {
        this.authorityMonitor = authorityMonitor;
        this.reservationService = reservationService;
        this.reservationRepository = reservationRepository;
        this.itemService = itemService;
    }

    public void register(HttpServer server) {
        server.createContext("/health", this::health);
        server.createContext("/items", this::items);
        server.createContext("/reservations", this::reservations);
        server.createContext("/admin/audit", this::audit);
        server.createContext("/admin/reset", this::reset);
        server.createContext("/admin/reconcile", this::reconcile);
    }

    private void health(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        write(exchange, 200, Map.of(
                "status", "ok",
                "authority", authorityMonitor.authorityStatus(),
                "mode", authorityMonitor.mode()));
    }

    private void items(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String itemId = exchange.getRequestURI().getPath().replaceFirst("^/items/", "");
        if (itemId.isBlank() || itemId.contains("/")) {
            write(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        ApiResult result = itemService.getItem(itemId);
        write(exchange, result.statusCode(), result.body());
    }

    private void reservations(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(exchange.getRequestMethod()) && "/reservations".equals(path)) {
            postReserve(exchange);
            return;
        }
        if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/reservations/")) {
            String reservationId = path.substring("/reservations/".length());
            if (reservationId.isBlank() || reservationId.contains("/")) {
                write(exchange, 404, Map.of("error", "not_found"));
            } else {
                getOne(exchange, reservationId);
            }
            return;
        }
        if ("GET".equals(exchange.getRequestMethod()) && "/reservations".equals(path)) {
            list(exchange);
            return;
        }
        methodNotAllowed(exchange, "GET, POST");
    }

    private void postReserve(HttpExchange exchange) throws IOException {
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            write(exchange, 400, Map.of("error", "missing_idempotency_key"));
            return;
        }
        JsonObject request = readObject(exchange);
        String itemId = string(request, "itemId");
        String userId = string(request, "userId");
        Integer quantity = integer(request, "qty", 1);
        if (itemId == null || userId == null || quantity == null || quantity <= 0) {
            write(exchange, 400, Map.of("error", "invalid_body"));
            return;
        }
        try {
            ApiResult result = reservationService.reserve(
                    userId, itemId, quantity, idempotencyKey.trim());
            write(exchange, result.statusCode(), result.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            write(exchange, 502, Map.of("error", "authority_unreachable"));
        } catch (JsonParseException | IllegalStateException exception) {
            write(exchange, 502, Map.of("error", "authority_unreachable"));
        } catch (SQLException exception) {
            write(exchange, 500, Map.of("error", "database_error"));
        }
    }

    private void getOne(HttpExchange exchange, String reservationId) throws IOException {
        try {
            StoredReservation reservation = reservationRepository.findById(reservationId);
            if (reservation == null) {
                write(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            write(exchange, 200, ReservationJson.from(reservation));
        } catch (SQLException exception) {
            write(exchange, 500, Map.of("error", "query"));
        }
    }

    private void list(HttpExchange exchange) throws IOException {
        try {
            List<JsonObject> response = new ArrayList<>();
            for (StoredReservation reservation : reservationRepository.list(query(exchange, "userId"))) {
                response.add(ReservationJson.from(reservation));
            }
            write(exchange, 200, response);
        } catch (SQLException exception) {
            write(exchange, 500, Map.of("error", "query"));
        }
    }

    private void audit(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String reservationId = query(exchange, "reservationId");
        if (reservationId == null) {
            write(exchange, 400, Map.of("error", "missing_reservation_id"));
            return;
        }
        try {
            List<JsonObject> response = new ArrayList<>();
            for (AuditEvent storedEvent : reservationRepository.audit(reservationId)) {
                JsonObject event = new JsonObject();
                if (storedEvent.oldStatus() == null) event.add("oldStatus", JsonNull.INSTANCE);
                else event.addProperty("oldStatus", storedEvent.oldStatus());
                event.addProperty("newStatus", storedEvent.newStatus());
                if (storedEvent.reason() != null) event.addProperty("reason", storedEvent.reason());
                if (storedEvent.authorityStatusCode() != null) {
                    event.addProperty("authorityStatusCode", storedEvent.authorityStatusCode());
                }
                if (storedEvent.authorityResponse() != null) {
                    event.add("authorityResponse", JsonParser.parseString(storedEvent.authorityResponse()));
                }
                event.addProperty("timestamp", storedEvent.timestamp().toString());
                response.add(event);
            }
            if (response.isEmpty()) {
                write(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            write(exchange, 200, response);
        } catch (SQLException | JsonParseException exception) {
            write(exchange, 500, Map.of("error", "audit_query_failed"));
        }
    }

    private void reset(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        try {
            reservationRepository.deleteAll();
            itemService.refreshAllItems();
            write(exchange, 200, Map.of("status", "reset"));
        } catch (Exception exception) {
            write(exchange, 500, Map.of("error", "reset"));
        }
    }

    private void reconcile(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        try {
            ReconcileResult result = reservationService.reconcilePending();
            write(exchange, 200, Map.of(
                    "replayed", result.replayed(),
                    "confirmed", result.confirmed(),
                    "reversed", result.reversed()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            write(exchange, 503, Map.of("error", "reconciliation_interrupted"));
        } catch (IOException exception) {
            authorityMonitor.recordFailure(exception);
            write(exchange, 503, Map.of("error", "authority_unreachable"));
        } catch (SQLException | JsonParseException | IllegalStateException exception) {
            write(exchange, 500, Map.of("error", "reconciliation_failed"));
        }
    }

    private static JsonObject readObject(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) return null;
            var element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException exception) {
            return null;
        }
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

    private static boolean preflight(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static void write(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] response = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        write(exchange, 405, Map.of("error", "method_not_allowed"));
    }

    private static String query(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2
                    && URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key)
                    && !pair[1].isEmpty()) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}