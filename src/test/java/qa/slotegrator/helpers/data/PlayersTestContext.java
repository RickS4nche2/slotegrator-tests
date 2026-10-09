package qa.slotegrator.helpers.data;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import tools.jackson.databind.JsonNode;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.api.model.PlayerLookupRequest;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.helpers.auth.ApiSession;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

/** Данные одного API-теста, исходные HTTP-ответы и резервная очистка собственных записей. */
public final class PlayersTestContext implements AutoCloseable {
    public static final int PLAYER_COUNT = 12;
    private final ProjectSettings settings;
    private final ApiSession session;
    private final UUID runId = UUID.randomUUID();
    private FeignPlayerOperations operations;
    private CreationJournal journal;
    private int createRequests;
    private int requestNumber;
    private boolean cleanupAttempted;
    private boolean closed;

    public PlayersTestContext(ProjectSettings settings) {
        this.settings = settings;
        session = new ApiSession(settings.api(), settings.auth());
    }

    /** Явное разрешение изменять данные стенда; пустой список не доказывает изоляцию. */
    public static void requireAuthorized(String authorization) {
        if (!"true".equals(authorization))
            throw new IllegalStateException("Для E2E требуется явное разрешение: -De2e.authorized=true");
    }

    public HttpResult login() {
        ensureOpen();
        return session.login();
    }

    public HttpResult loginResponse() {
        ensureOpen();
        return session.loginWithAuthorization(session.validLoginRequest(), AuthorizationMode.VALID);
    }

    public void authenticate(HttpResult login) {
        ensureOpen();
        if (journal != null)
            throw new IllegalStateException("Повторный вход не должен заменять журнал прогона");
        session.authenticate(login);
        operations = new FeignPlayerOperations(session.players(), codec());
        journal = new CreationJournal(runId, operations);
    }

    public List<CreatePlayerRequest> playerRequests() {
        ensureOpen();
        return PlayerData.lifecyclePlayers(runId, settings.currencyCode());
    }

    public CreatePlayerRequest newPlayerRequest(String name) {
        ensureOpen();
        return PlayerData.player(runId, ++requestNumber, settings.currencyCode(), name);
    }

    /** Подготовка проверяет пригодность фикстуры; главное действие остаётся в конкретном тесте. */
    public RegisteredPlayer preparePlayer(String name) {
        return Allure.step("Подготовить собственного игрока: " + name, () -> {
            var request = newPlayerRequest(name);
            var response = create(request);
            var created = PlayersExpected.verifyCreated(response, request, ownedIds(), codec());
            return new RegisteredPlayer(created.id(), request);
        });
    }

    public List<ObservedPlayerResponse> list() {
        ensureAuthenticated();
        return operations.list();
    }

    public HttpResult listResponse() {
        return listResponse(AuthorizationMode.VALID);
    }

    public HttpResult listResponse(AuthorizationMode authorization) {
        ensureAuthenticated();
        return HttpResult.read(RestAssured.given().spec(session.requestWithAuthorization(authorization))
                .get(ApiRoutes.GET_PLAYERS));
    }

    public List<ObservedPlayerResponse> snapshot() {
        return PlayersExpected.readList(listResponse(), codec());
    }

    public HttpResult create(CreatePlayerRequest request) {
        return create(request, request.email(), request.username(), AuthorizationMode.VALID);
    }

    public HttpResult create(JsonNode body, AuthorizationMode authorization) {
        return create(body, stringField(body, "email"), stringField(body, "username"), authorization);
    }

    private HttpResult create(Object body, String email, String username, AuthorizationMode authorization) {
        ensureAuthenticated();
        if (cleanupAttempted)
            throw new IllegalStateException("Создание после начала очистки запрещено");
        if (createRequests >= PLAYER_COUNT)
            throw new IllegalStateException("Лимит 12 запросов создания исчерпан");
        journal.beginAttempt(email, username);
        createRequests++;
        var result = HttpResult.read(RestAssured.given().spec(session.requestWithAuthorization(authorization))
                .body(body).post(ApiRoutes.CREATE_PLAYER));
        // Реальное сохранение определяется до assertions, в том числе после ответа с ошибкой.
        journal.reconcile();
        return result;
    }

    public HttpResult profile(CreatePlayerRequest request) {
        return profile(new PlayerLookupRequest(request.email()), AuthorizationMode.VALID);
    }

    public HttpResult profile(Object body, AuthorizationMode authorization) {
        ensureAuthenticated();
        return HttpResult.read(RestAssured.given().spec(session.requestWithAuthorization(authorization))
                .body(body).post(ApiRoutes.GET_PLAYER));
    }

    public HttpResult delete(RegisteredPlayer player, AuthorizationMode authorization) {
        ensureAuthenticated();
        if (cleanupAttempted)
            throw new IllegalStateException("Проверяемое удаление после начала очистки запрещено");
        journal.requireOwned(player.id());
        return HttpResult.read(RestAssured.given().spec(session.requestWithAuthorization(authorization))
                .pathParam("id", player.id()).delete(ApiRoutes.DELETE_PLAYER));
    }

    public Set<String> ownedIds() {
        ensureAuthenticated();
        return journal.ownedIds();
    }

    public JsonCodec codec() {
        return session.codec();
    }

    /** Проверенный пустой итог заменяет резервную очистку, если журнал подтверждает отсутствие своих записей. */
    public void confirmNothingOwned(List<ObservedPlayerResponse> current) {
        ensureAuthenticated();
        if (!cleanupAttempted && journal.closeIfNothingRemains(current))
            cleanupAttempted = true;
    }

    public void cleanup() {
        if (cleanupAttempted || journal == null)
            return;
        cleanupAttempted = true;
        journal.close();
    }

    @Override
    public void close() {
        if (closed)
            return;
        try {
            if (!cleanupAttempted && createRequests > 0)
                Allure.step("Резервная очистка собственных игроков", this::cleanup);
        } finally {
            closed = true;
        }
    }

    private void ensureOpen() {
        if (closed)
            throw new IllegalStateException("Контекст API-теста закрыт");
    }

    private void ensureAuthenticated() {
        ensureOpen();
        if (journal == null)
            throw new IllegalStateException("До операций с игроками требуется явная авторизация контекста");
    }

    private static String stringField(JsonNode body, String field) {
        var value = body.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
