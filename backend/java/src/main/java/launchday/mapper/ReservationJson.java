package launchday.mapper;

import com.google.gson.JsonObject;
import launchday.model.StoredReservation;

public final class ReservationJson {
    private ReservationJson() {}

    public static JsonObject from(StoredReservation reservation) {
        JsonObject response = new JsonObject();
        response.addProperty("reservationId", reservation.id());
        response.addProperty("itemId", reservation.itemId());
        response.addProperty("userId", reservation.userId());
        response.addProperty("qty", reservation.qty());
        response.addProperty("status", reservation.status());
        response.addProperty("mode", reservation.mode());
        if (reservation.reason() != null) response.addProperty("reason", reservation.reason());
        return response;
    }
}