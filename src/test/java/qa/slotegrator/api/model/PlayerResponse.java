package qa.slotegrator.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** Тип currency_code ещё не описан исходным OpenAPI, поэтому сохраняется без преобразования. */
public record PlayerResponse(
        Long id, String username, String email, String name, String surname,
        @JsonProperty("currency_code") JsonNode currencyCode) {
    public PlayerResponse {
        currencyCode = currencyCode == null ? null : currencyCode.deepCopy();
    }

    @Override
    public JsonNode currencyCode() {
        return currencyCode == null ? null : currencyCode.deepCopy();
    }
    @Override
    public String toString() {
        return "PlayerResponse[id=" + id + "]";
    }
}
