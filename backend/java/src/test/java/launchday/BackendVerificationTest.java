package launchday;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_TESTS", matches = "true")
class BackendVerificationTest {
    private static final String API_URL = setting("API_URL", "http://127.0.0.1:8080");
    private static final String AUTHORITY_URL = setting("AUTHORITY_URL", "http://127.0.0.1:9000");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private record ApiResponse(int statusCode, JsonObject body) {}

    @BeforeEach
    void resetBeforeTest() throws Exception {
        resetServices();
    }

    @AfterEach
    void resetAfterTest() throws Exception {
        resetServices();
    }

    @Test
    void concurrentIdempotentRetriesCreateOneReservationAndOneAuthorityEffect() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResponse>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int requestNumber = 0; requestNumber < 50; requestNumber++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return reserve("idempotency-user", "hype-001", "same-key", 1);
                }));
            }
            start.countDown();

            Set<String> reservationIds = new HashSet<>();
            for (Future<ApiResponse> future : futures) {
                ApiResponse response = future.get();
                assertEquals(201, response.statusCode());
                reservationIds.add(response.body().get("reservationId").getAsString());
            }
            assertEquals(1, reservationIds.size());
        }

        ApiResponse authorityStatus = get(AUTHORITY_URL, "/admin/status");
        assertEquals(1, authorityStatus.body().get("reservations").getAsInt());
    }

    @Test
    void concurrentReservationsNeverOversellKnownStock() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResponse>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int requestNumber = 0; requestNumber < 200; requestNumber++) {
                int requestId = requestNumber;
                futures.add(executor.submit(() -> {
                    start.await();
                    return reserve("stock-user-" + requestId, "hype-001", "stock-key-" + requestId, 1);
                }));
            }
            start.countDown();

            int confirmed = 0;
            int rejected = 0;
            for (Future<ApiResponse> future : futures) {
                ApiResponse response = future.get();
                String status = response.body().get("status").getAsString();
                if (response.statusCode() == 201 && "confirmed".equals(status)) confirmed++;
                if (response.statusCode() == 409 && "rejected".equals(status)) rejected++;
            }
            assertEquals(50, confirmed);
            assertEquals(150, rejected);
        }
    }

    @Test
    void pendingReservationTransitionsToConfirmedAfterRecovery() throws Exception {
        setAuthorityMode("down");
        await(Duration.ofSeconds(8), () -> "standin".equals(healthMode()));

        ApiResponse pending = reserve("transition-user", "hype-001", "transition-key", 1);
        assertEquals(202, pending.statusCode());
        assertEquals("pending", pending.body().get("status").getAsString());
        String reservationId = pending.body().get("reservationId").getAsString();

        setAuthorityMode("healthy");
        await(Duration.ofSeconds(20), () ->
                "confirmed".equals(get(API_URL, "/reservations/" + reservationId)
                        .body().get("status").getAsString()));

        ApiResponse confirmed = get(API_URL, "/reservations/" + reservationId);
        assertEquals(200, confirmed.statusCode());
        assertEquals("confirmed", confirmed.body().get("status").getAsString());
    }

    @Test
    void replayReturnsFinalReservationWithoutAnotherAuthorityEffect() throws Exception {
        setAuthorityMode("down");
        await(Duration.ofSeconds(8), () -> "standin".equals(healthMode()));

        ApiResponse pending = reserve("replay-user", "hype-001", "replay-key", 1);
        String reservationId = pending.body().get("reservationId").getAsString();
        assertEquals("pending", pending.body().get("status").getAsString());

        setAuthorityMode("healthy");
        await(Duration.ofSeconds(20), () ->
                "confirmed".equals(get(API_URL, "/reservations/" + reservationId)
                        .body().get("status").getAsString()));

        ApiResponse replay = reserve("replay-user", "hype-001", "replay-key", 1);
        assertEquals(201, replay.statusCode());
        assertEquals(reservationId, replay.body().get("reservationId").getAsString());
        assertEquals("confirmed", replay.body().get("status").getAsString());

        ApiResponse authorityStatus = get(AUTHORITY_URL, "/admin/status");
        assertEquals(1, authorityStatus.body().get("reservations").getAsInt());
    }

    private static ApiResponse reserve(String userId, String itemId, String idempotencyKey, int quantity)
            throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("itemId", itemId);
        request.addProperty("userId", userId);
        request.addProperty("qty", quantity);
        return send(API_URL, "POST", "/reservations", request.toString(), idempotencyKey);
    }

    private static ApiResponse get(String baseUrl, String path) throws Exception {
        return send(baseUrl, "GET", path, null, null);
    }

    private static ApiResponse post(String baseUrl, String path, String body) throws Exception {
        return send(baseUrl, "POST", path, body, null);
    }

    private static ApiResponse send(String baseUrl, String method, String path, String body,
                                    String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonObject responseBody = JsonParser.parseString(response.body()).getAsJsonObject();
        return new ApiResponse(response.statusCode(), responseBody);
    }

    private static void resetServices() throws Exception {
        ApiResponse authorityReset = post(AUTHORITY_URL, "/admin/reset", "{}");
        assertTrue(authorityReset.statusCode() >= 200 && authorityReset.statusCode() < 300);
        ApiResponse apiReset = post(API_URL, "/admin/reset", "{}");
        assertTrue(apiReset.statusCode() >= 200 && apiReset.statusCode() < 300);
        await(Duration.ofSeconds(8), () -> "live".equals(healthMode()));
    }

    private static void setAuthorityMode(String mode) throws Exception {
        ApiResponse response = post(AUTHORITY_URL, "/admin/mode", "{\"mode\":\"" + mode + "\"}");
        assertEquals(200, response.statusCode());
    }

    private static String healthMode() throws Exception {
        return get(API_URL, "/health").body().get("mode").getAsString();
    }

    private static void await(Duration timeout, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return;
            Thread.sleep(100);
        }
        fail("condition was not met within " + timeout);
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}