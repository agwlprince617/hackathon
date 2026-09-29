package launchday.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import launchday.client.AuthorityClient;
import launchday.model.ApiResult;
import launchday.model.CachedItem;
import launchday.repository.ItemRepository;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.Map;

public final class ItemService {
    private final AuthorityClient authorityClient;
    private final AuthorityMonitor authorityMonitor;
    private final ItemRepository itemRepository;

    public ItemService(AuthorityClient authorityClient, AuthorityMonitor authorityMonitor,
                       ItemRepository itemRepository) {
        this.authorityClient = authorityClient;
        this.authorityMonitor = authorityMonitor;
        this.itemRepository = itemRepository;
    }

    public ApiResult getItem(String itemId) {
        if (authorityMonitor.isStandin()) return cachedItem(itemId);
        try {
            HttpResponse<String> response = authorityClient.getItem(itemId);
            if (response.statusCode() != 200) {
                return new ApiResult(response.statusCode(), JsonParser.parseString(response.body()));
            }
            JsonObject item = JsonParser.parseString(response.body()).getAsJsonObject();
            int available = item.get("available").getAsInt();
            String name = string(item, "name");
            int shadowAvailable = itemRepository.storeAuthoritySnapshot(
                    itemId, name == null ? itemId : name, available);
            authorityMonitor.recordSuccessfulRequest();
            item.addProperty("shadowAvailable", shadowAvailable);
            item.addProperty("mode", "live");
            return new ApiResult(200, item);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ApiResult(502, Map.of("error", "authority_unreachable"));
        } catch (IOException exception) {
            authorityMonitor.recordFailure(exception);
            return cachedItem(itemId);
        } catch (RuntimeException | SQLException exception) {
            return new ApiResult(500, Map.of("error", "item_lookup_failed"));
        }
    }

    public void refreshAllItems() throws SQLException, IOException, InterruptedException {
        for (String itemId : itemRepository.itemIds()) {
            HttpResponse<String> response = authorityClient.getItem(itemId);
            if (response.statusCode() != 200) continue;
            JsonObject item = JsonParser.parseString(response.body()).getAsJsonObject();
            String name = string(item, "name");
            itemRepository.storeAuthoritySnapshot(
                    itemId, name == null ? itemId : name, item.get("available").getAsInt());
        }
    }

    private ApiResult cachedItem(String itemId) {
        try {
            CachedItem cached = itemRepository.findCached(itemId);
            if (cached == null) return new ApiResult(404, Map.of("error", "not_found"));
            JsonObject item = new JsonObject();
            item.addProperty("itemId", cached.id());
            item.addProperty("name", cached.name());
            item.addProperty("available", cached.available());
            item.addProperty("shadowAvailable", cached.shadowAvailable());
            item.addProperty("mode", "standin");
            return new ApiResult(200, item);
        } catch (SQLException exception) {
            return new ApiResult(500, Map.of("error", "item_lookup_failed"));
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
}