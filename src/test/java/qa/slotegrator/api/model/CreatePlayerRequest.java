package qa.slotegrator.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreatePlayerRequest(
        @JsonProperty("currency_code") String currencyCode,
        String email,
        String name,
        @JsonProperty("password_change") String passwordChange,
        @JsonProperty("password_repeat") String passwordRepeat,
        String surname,
        String username) {
    @Override
    public String toString() {
        return "CreatePlayerRequest[тело скрыто]";
    }
}
