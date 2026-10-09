package qa.slotegrator.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") String expiresIn,
        String scope) {
    @Override
    public String toString() {
        return "TokenResponse[тело скрыто]";
    }
}
