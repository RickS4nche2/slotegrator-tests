package qa.slotegrator.helpers.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import feign.Client;
import feign.Feign;
import feign.auth.BasicAuthRequestInterceptor;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import lombok.Getter;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.client.PlayersApi;
import qa.slotegrator.api.client.TesterApi;
import qa.slotegrator.api.model.LoginRequest;
import qa.slotegrator.api.model.ObservedTokenResponse;
import qa.slotegrator.expecteds.AuthenticationExpected;
import qa.slotegrator.helpers.config.ApiSettings;
import qa.slotegrator.helpers.config.AuthSettings;
import qa.slotegrator.helpers.http.HttpClients;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.reporting.HttpDiagnostics;
import qa.slotegrator.helpers.reporting.ReportSanitizer;
import qa.slotegrator.helpers.reporting.ReportingFeignClient;
import qa.slotegrator.helpers.reporting.SafeAllureFilter;

/** Один сеанс одного теста. Конструктор не выполняет HTTP-запросов. */
public final class ApiSession {
    private static final String INVALID_BEARER_TOKEN = "invalid-slotegrator-test-bearer";
    private static final String INVALID_BASIC_PASSWORD = "invalid-slotegrator-basic-password";
    private final ApiSettings settings;
    private final AuthSettings auth;
    @Getter
    private final JsonCodec codec = new JsonCodec();
    @Getter
    private final ReportSanitizer sanitizer = new ReportSanitizer(codec);
    private final HttpDiagnostics diagnostics = new HttpDiagnostics(sanitizer);
    // Один транспорт на сеанс; создание клиента не выполняет HTTP-запросов.
    private final Client transport = new ReportingFeignClient(HttpClients.feignTransport(), diagnostics);
    private String token;

    public ApiSession(ApiSettings settings, AuthSettings auth) {
        this.settings = settings;
        this.auth = auth;
        sanitizer.remember(auth.basicUsername());
        sanitizer.remember(auth.testerEmail());
        sanitizer.remember(auth.basicPassword());
        sanitizer.remember(auth.testerPassword());
    }

    public HttpResult login() {
        var client = builder().requestInterceptor(new BasicAuthRequestInterceptor(
                auth.basicUsername(), auth.basicPassword(), StandardCharsets.UTF_8))
                .target(TesterApi.class, settings.baseUri().toString());
        return HttpResult.read(client.login(new LoginRequest(auth.testerEmail(), auth.testerPassword())));
    }

    public ObservedTokenResponse authenticate() {
        token = null;
        return authenticate(login());
    }

    public ObservedTokenResponse authenticate(HttpResult response) {
        token = null;
        token = AuthenticationExpected.verifyUsableLogin(response, codec);
        sanitizer.remember(token);
        return new ObservedTokenResponse(token);
    }

    public PlayersApi players() {
        requireToken();
        return builder().requestInterceptor(request -> {
            requireToken();
            request.header("Authorization", "Bearer " + token);
        })
                .target(PlayersApi.class, settings.baseUri().toString());
    }

    public RequestSpecification restAssured() {
        requireToken();
        return HttpClients.restAssured(settings, codec)
                .filter((request, response, context) -> {
                    requireToken();
                    request.removeHeader("Authorization");
                    request.header("Authorization", "Bearer " + token);
                    return context.next(request, response);
                })
                .filter(new SafeAllureFilter(codec, diagnostics));
    }

    /** Независимый запрос отказа не наследует фильтр, восстанавливающий рабочий Bearer. */
    public RequestSpecification requestWithAuthorization(AuthorizationMode mode) {
        Objects.requireNonNull(mode, "Нужен явный режим авторизации");
        if (mode == AuthorizationMode.VALID)
            return restAssured();
        var request = HttpClients.restAssured(settings, codec)
                .filter(new SafeAllureFilter(codec, diagnostics));
        if (mode == AuthorizationMode.INVALID) {
            sanitizer.remember(INVALID_BEARER_TOKEN);
            request.header("Authorization", "Bearer " + INVALID_BEARER_TOKEN);
        }
        return request;
    }

    /** Исходные реквизиты для точечной мутации тела входа; toString модели не раскрывает значения. */
    public LoginRequest validLoginRequest() {
        return new LoginRequest(auth.testerEmail(), auth.testerPassword());
    }

    /** Вход через Rest Assured с явным Basic; не изменяет рабочий токен контрольного сеанса. */
    public HttpResult loginWithAuthorization(Object payload, AuthorizationMode mode) {
        Objects.requireNonNull(mode, "Нужен явный режим авторизации");
        byte[] body = payload instanceof byte[] bytes ? bytes.clone() : codec.encode(payload);
        sanitizer.rememberBody(body);
        var request = HttpClients.restAssured(settings, codec)
                .filter(new SafeAllureFilter(codec, diagnostics));
        if (mode != AuthorizationMode.MISSING) {
            String invalidPassword = INVALID_BASIC_PASSWORD.equals(auth.basicPassword())
                    ? INVALID_BASIC_PASSWORD + "-different"
                    : INVALID_BASIC_PASSWORD;
            String password = mode == AuthorizationMode.INVALID ? invalidPassword : auth.basicPassword();
            sanitizer.remember(password);
            String credentials = auth.basicUsername() + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            String basic = "Basic " + encoded;
            sanitizer.remember(credentials);
            sanitizer.remember(encoded);
            sanitizer.remember(basic);
            request.header("Authorization", basic);
        }
        return HttpResult.read(RestAssured.given().spec(request).body(body).post(ApiRoutes.LOGIN));
    }

    private Feign.Builder builder() {
        return HttpClients.feign(settings, codec, transport);
    }

    private void requireToken() {
        if (token == null)
            throw new IllegalStateException("Сначала требуется вход тестера");
    }
}
