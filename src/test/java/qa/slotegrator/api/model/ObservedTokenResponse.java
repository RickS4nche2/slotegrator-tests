package qa.slotegrator.api.model;

/** Подтверждённая модель входа стенда; исходная TokenResponse сохраняет опубликованный контракт. */
public record ObservedTokenResponse(String accessToken) {
    @Override
    public String toString() {
        return "ObservedTokenResponse[токен скрыт]";
    }
}
