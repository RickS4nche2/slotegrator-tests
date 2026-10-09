package qa.slotegrator.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Наблюдаемый ответ создания: поле _id отличается от id в ответах чтения. */
public record ObservedCreatedPlayerResponse(
        @JsonProperty("_id") String id,
        String username, String email, String name, String surname,
        @JsonProperty("currency_code") String currencyCode) {

    @Override
    public String toString() {
        return "ObservedCreatedPlayerResponse[данные скрыты]";
    }
}
