package qa.slotegrator.helpers.http;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import feign.Client;
import feign.Feign;
import feign.Logger;
import feign.Request;
import feign.Retryer;
import feign.httpclient.ApacheHttpClient;
import feign.jackson3.Jackson3Decoder;
import feign.jackson3.Jackson3Encoder;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.mapper.ObjectMapperType;
import io.restassured.specification.RequestSpecification;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;

import qa.slotegrator.helpers.config.ApiSettings;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.json.PayloadException;

/** Создаёт отдельные настройки клиентов; вызовов API и общего изменяемого состояния нет. */
public final class HttpClients {

    private HttpClients() {
    }

    /** Транспорт передаётся явно: стандартный клиент Feign повторяет запросы при обрыве соединения. */
    public static Feign.Builder feign(ApiSettings settings, JsonCodec codec, Client transport) {
        Objects.requireNonNull(settings, "Нужны настройки HTTP");
        Objects.requireNonNull(transport, "Нужен транспорт без повторов");
        return Feign.builder()
                .client(transport)
                .encoder((object, type, template) -> {
                    try {
                        new Jackson3Encoder(codec.mapper()).encode(object, type, template);
                    } catch (Exception exception) {
                        throw new PayloadException("Не удалось сериализовать тело Feign");
                    }
                })
                .decoder((response, type) -> {
                    try {
                        return new Jackson3Decoder(codec.mapper()).decode(response, type);
                    } catch (Exception exception) {
                        throw new PayloadException("Ответ Feign не соответствует JSON-модели");
                    }
                })
                .retryer(Retryer.NEVER_RETRY)
                .logLevel(Logger.Level.NONE)
                .options(new Request.Options(
                        settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                        settings.readTimeout().toMillis(), TimeUnit.MILLISECONDS, false));
    }

    /** Повторы отключены и в Feign, и внутри HTTP-транспорта, включая DELETE без тела. */
    public static Client feignTransport() {
        return new ApacheHttpClient(org.apache.http.impl.client.HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                // Сессия не хранит открытые соединения между запросами.
                .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
                .build());
    }

    /** Каждый вызов возвращает новую спецификацию запроса без глобальных настроек Rest Assured. */
    public static RequestSpecification restAssured(ApiSettings settings, JsonCodec codec) {
        Objects.requireNonNull(settings, "Нужны настройки HTTP");
        var httpConfig = HttpClientConfig.httpClientConfig()
                .httpClientFactory(HttpClients::withoutRetries)
                .setParam("http.connection.timeout", Math.toIntExact(settings.connectTimeout().toMillis()))
                .setParam("http.socket.timeout", Math.toIntExact(settings.readTimeout().toMillis()));
        var config = RestAssuredConfig.config()
                .objectMapperConfig(ObjectMapperConfig.objectMapperConfig()
                        .defaultObjectMapperType(ObjectMapperType.JACKSON_3)
                        .jackson3ObjectMapperFactory((type, charset) -> codec.mapper()))
                .httpClient(httpConfig)
                .redirect(RedirectConfig.redirectConfig().followRedirects(false));
        return new RequestSpecBuilder()
                .setBaseUri(settings.baseUri().toString())
                .setAccept(ContentType.JSON)
                .setContentType(ContentType.JSON)
                .setConfig(config)
                .build();
    }

    // Rest Assured использует старый Apache HttpClient; повторы отключаем на уровне транспорта.
    @SuppressWarnings("deprecation")
    private static DefaultHttpClient withoutRetries() {
        var client = new DefaultHttpClient();
        client.setHttpRequestRetryHandler(new DefaultHttpRequestRetryHandler(0, false));
        return client;
    }
}
