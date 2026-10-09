package qa.slotegrator.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Наблюдаемые поля профиля/списка; неполный снимок может использоваться только для владения и очистки. */
public record ObservedPlayerResponse(
        String id, String username, String email, String name, String surname,
        @JsonProperty("currency_code") String currencyCode) {
    @Override
    public String toString() {
        return "ObservedPlayerResponse[данные скрыты]";
    }
}
