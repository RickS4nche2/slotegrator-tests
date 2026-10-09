package qa.slotegrator.helpers.json;

import java.nio.charset.StandardCharsets;

import lombok.Getter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;

/** Один неизменяемый JSON-кодек для обоих клиентов. */
public final class JsonCodec {
    @Getter
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(String.class, config -> {
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            })
            .build();

    public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JacksonException exception) {
            throw new PayloadException("Не удалось сериализовать JSON");
        }
    }

    public String text(Object value) {
        return new String(encode(value), StandardCharsets.UTF_8);
    }

    public JsonNode tree(byte[] value) {
        try {
            JsonNode node = mapper.readTree(value);
            if (node == null)
                throw new PayloadException("Получено пустое JSON-тело");
            return node;
        } catch (JacksonException exception) {
            throw new PayloadException("Получено некорректное JSON-тело");
        }
    }

    public JsonNode tree(String value) {
        return tree(value.getBytes(StandardCharsets.UTF_8));
    }

    public <T> T decode(byte[] value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JacksonException exception) {
            throw new PayloadException("JSON не соответствует модели " + type.getSimpleName());
        }
    }
}
