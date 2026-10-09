package qa.slotegrator.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.With;

/** Наблюдаемые поля профиля/списка; неполный снимок может использоваться только для владения и очистки. */
@With
public record ObservedPlayerResponse(
        String id, String username, String email, String name, String surname,
        @JsonProperty("currency_code") String currencyCode) {
    @Override
    public String toString() {
        return "ObservedPlayerResponse[данные скрыты]";
    }
}
