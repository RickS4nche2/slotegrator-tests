package qa.slotegrator.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Ответ создания; JSON-имя _id — наблюдаемая форма стенда. Опубликованный integer id разбирает ObservedPlayerContract. */
public record ObservedCreatedPlayerResponse(
        @JsonProperty("_id") String id,
        String username, String email, String name, String surname,
        @JsonProperty("currency_code") String currencyCode) {

    @Override
    public String toString() {
        return "ObservedCreatedPlayerResponse[данные скрыты]";
    }
}
