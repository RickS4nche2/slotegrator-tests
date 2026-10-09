package qa.slotegrator.helpers.reporting;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.json.PayloadException;

/** Реестр секретов принадлежит одному контексту; исходные запросы и ответы не изменяются. */
public final class ReportSanitizer {
    public static final String HIDDEN = "[скрыто]";
    private static final Set<String> HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\\.[A-Z0-9-]+)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private final Set<String> secrets = ConcurrentHashMap.newKeySet();
    private final JsonCodec codec;

    public ReportSanitizer(JsonCodec codec) {
        this.codec = codec;
    }

    public void remember(String value) {
        if (value != null && !value.isEmpty())
            secrets.add(value);
    }

    public String text(String value) {
        if (value == null)
            return "";
        String result = value;
        for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            result = result.replace(secret, HIDDEN);
        }
        // Адрес владельца и JWT могут прийти в произвольно названном поле или сообщении.
        return JWT.matcher(EMAIL.matcher(result).replaceAll(HIDDEN)).replaceAll(HIDDEN);
    }

    public String header(String name, String value) {
        if (HEADERS.contains(name.toLowerCase(Locale.ROOT)) || sensitiveField(name)) {
            rememberCredential(value);
            if (name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Set-Cookie")) {
                String[] pairs = value.split(";");
                int count = name.equalsIgnoreCase("Set-Cookie") ? Math.min(1, pairs.length) : pairs.length;
                for (int index = 0; index < count; index++) {
                    int separator = pairs[index].indexOf('=');
                    if (separator >= 0)
                        remember(pairs[index].substring(separator + 1).trim());
                }
            }
            return HIDDEN;
        }
        return text(value);
    }

    public void rememberBody(byte[] body) {
        if (body == null || body.length == 0 || body.length > 65_536)
            return;
        try {
            collect(codec.tree(body));
        } catch (PayloadException ignored) {
            // Некорректное тело во вложение не попадёт.
        }
    }

    public String body(byte[] body) {
        if (body == null || body.length == 0)
            return "";
        if (body.length > 65_536)
            return "[тело не приложено: превышен предел диагностики]";
        try {
            JsonNode node = codec.tree(body);
            collect(node);
            return codec.text(redact(node));
        } catch (PayloadException ignored) {
            return "[тело не приложено: не удалось безопасно разобрать JSON]";
        }
    }

    private void collect(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (sensitiveField(entry.getKey()))
                    collectValues(entry.getValue());
                collect(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::collect);
        }
    }

    private void collectValues(JsonNode node) {
        if (node.isObject() || node.isArray())
            node.forEach(this::collectValues);
        else if (!node.isNull())
            rememberCredential(node.asString());
    }

    /** Значение целиком и его части: токен после схемы Bearer, Base64 и пароль из Basic. */
    private void rememberCredential(String value) {
        remember(value);
        if (value.regionMatches(true, 0, "Bearer ", 0, 7))
            remember(value.substring(7));
        if (value.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                remember(value.substring(6));
                String decoded = new String(Base64.getDecoder().decode(value.substring(6)), StandardCharsets.UTF_8);
                remember(decoded);
                int separator = decoded.indexOf(':');
                if (separator >= 0)
                    remember(decoded.substring(separator + 1));
            } catch (IllegalArgumentException ignored) {
                // Полное значение всё равно скрывается.
            }
        }
    }

    private JsonNode redact(JsonNode node) {
        if (node.isObject()) {
            var result = (ObjectNode) node.deepCopy();
            node.properties().forEach(entry -> {
                result.remove(entry.getKey());
                result.set(text(entry.getKey()), sensitiveField(entry.getKey())
                        ? StringNode.valueOf(HIDDEN)
                        : redact(entry.getValue()));
            });
            return result;
        }
        if (node.isArray()) {
            var result = (ArrayNode) node.deepCopy();
            for (int index = 0; index < node.size(); index++)
                result.set(index, redact(node.get(index)));
            return result;
        }
        if (node.isNull())
            return node;
        String value = node.asString();
        String safe = text(value);
        return node.isString() || !value.equals(safe) ? StringNode.valueOf(safe) : node;
    }

    private static boolean sensitiveField(String name) {
        String normalized = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("passwd") || normalized.equals("pass")
                || normalized.equals("pwd") || normalized.contains("secret") || normalized.contains("session")
                || normalized.contains("authorization") || normalized.equals("bearer") || normalized.equals("cookie")
                || normalized.endsWith("token") || normalized.endsWith("apikey") || normalized.equals("jwt");
    }
}
