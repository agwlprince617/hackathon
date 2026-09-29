package launchday.client;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class AuthorityClient {
    private final HttpClient httpClient;
    private final String baseUrl;

        public AuthorityClient(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

        public HttpResponse<String> getItem(String itemId) throws IOException, InterruptedException {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/items/" + itemId))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

        public HttpResponse<String> reserve(String reservationId, String itemId, String userId, int quantity)
            throws IOException, InterruptedException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("reservationId", reservationId);
        requestBody.addProperty("itemId", itemId);
        requestBody.addProperty("userId", userId);
        requestBody.addProperty("qty", quantity);
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/reservations"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}