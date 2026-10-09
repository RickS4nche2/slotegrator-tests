package qa.slotegrator.tests.integration.players;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.InvalidJsonField;
import qa.slotegrator.helpers.data.InvalidJsonField.Violation;
import qa.slotegrator.helpers.data.PlayerData;
import qa.slotegrator.helpers.data.PlayersLifecycleExtension;
import qa.slotegrator.helpers.data.PlayersTestContext;
import qa.slotegrator.helpers.data.RegisteredPlayer;
import qa.slotegrator.helpers.reporting.ExpectedFailure;
import qa.slotegrator.helpers.reporting.KnownFailure;

/**
 * Независимые проверки создания с журналом попыток и собственной резервной очисткой.
 * Негативные 400/401 — принятые правила проекта на основе OpenAPI; см. docs/api-spec.md.
 */
@Epic("Players API")
@Feature("Создание игрока")
@Story("Создание сохраняет исходные данные и не меняет существующих игроков")
@Tag("api")
@Tag("integration")
@Tag("regression")
@DisplayName("Независимые проверки создания игрока")
class PlayerCreationIT {
    private PlayersTestContext context;

    @RegisterExtension
    final PlayersLifecycleExtension lifecycle = new PlayersLifecycleExtension(() -> context);

    @BeforeEach
    void prepareIndependentContext() {
        context = new PlayersTestContext(ProjectSettings.load());
        Allure.step("Получить и проверить токен собственного контекста",
                () -> context.authenticate(context.login()));
    }

    /**
     * Design: валидный класс, переход к двум игрокам и независимый эталон исходных полей.
     * Steps: подготовить соседа, подтвердить его, создать цель, прочитать профиль и список.
     * Expected: 201, ровно одна новая запись и исходные поля цели; весь исходный состав сохранён.
     */
    @Test
    @Tag("smoke")
    @DisplayName("Создание сохраняет исходные поля и не изменяет контрольного игрока")
    @Tag("CREATE-01")
    void createsPlayerWithoutChangingNeighbour() {
        var neighbour = context.preparePlayer("Контрольный");
        var before = Allure.step("Прочитать исходное состояние перед созданием",
                () -> context.snapshot());
        Allure.step("Подтвердить контрольного игрока перед созданием",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(neighbour)));
        var request = context.newPlayerRequest("Созданный");

        var response = Allure.step("Создать нового игрока рядом с контрольным", () -> context.create(request));
        var created = Allure.step("Создание вернуло HTTP 201 и исходные поля собственного игрока",
                () -> PlayersExpected.verifyCreated(response, request, context.ownedIds(), context.codec()));
        var player = new RegisteredPlayer(created.id(), request);
        var profile = Allure.step("Прочитать профиль нового игрока", () -> context.profile(request));
        var after = Allure.step("Прочитать список после создания", () -> context.snapshot());

        Allure.step("Создана ровно одна запись с исходными полями, прежние игроки сохранены", () -> {
            PlayersExpected.verifyProfile(profile, request, player.id(), context.codec());
            PlayersExpected.verifyOwnPlayers(after, List.of(neighbour, player));
            PlayersExpected.verifyCreationState(before, after, player);
        });
    }

    /**
     * Design: нижняя допустимая граница minLength=4 для обоих полей пароля; прочие поля валидны.
     * Steps: подготовить соседа, создать игрока с одинаковыми четырёхсимвольными паролями, перечитать.
     * Expected: 201, ровно одна новая запись, исходные поля и прежние игроки сохранены.
     * Ожидание границы опирается на PlayerRequestDTO.
     */
    @Test
    @DisplayName("Одинаковые пароли длиной четыре символа допускают создание игрока")
    @Tag("CREATE-04")
    void acceptsPasswordsAtMinimumLength() {
        var neighbour = context.preparePlayer("Контрольный");
        var before = Allure.step("Прочитать исходное состояние перед граничным созданием",
                () -> context.snapshot());
        Allure.step("Подтвердить контрольного игрока перед граничным созданием",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(neighbour)));
        var valid = context.newPlayerRequest("Граница");
        var request = new CreatePlayerRequest(valid.currencyCode(), valid.email(), valid.name(),
                "Aa1!", "Aa1!", valid.surname(), valid.username());

        var response = Allure.step("Создать игрока с паролями на нижней границе длины",
                () -> context.create(request));
        var created = Allure.step("Граничное создание вернуло HTTP 201 и исходные поля",
                () -> PlayersExpected.verifyCreated(response, request, context.ownedIds(), context.codec()));
        var player = new RegisteredPlayer(created.id(), request);
        var profile = Allure.step("Прочитать профиль после граничного создания", () -> context.profile(request));
        var after = Allure.step("Прочитать список после граничного создания", () -> context.snapshot());

        Allure.step("Создан ровно один граничный игрок, исходные поля и прежние игроки сохранены", () -> {
            PlayersExpected.verifyProfile(profile, request, player.id(), context.codec());
            PlayersExpected.verifyOwnPlayers(after, List.of(neighbour, player));
            PlayersExpected.verifyCreationState(before, after, player);
        });
    }

    /**
     * Design: нижняя допустимая граница username minLength=4 при подтверждённо свободном значении.
     * Steps: подготовить соседа, прочитать занятые username, создать игрока со свободным коротким именем.
     * Expected: 201, ровно одна новая запись с исходными полями; прежние игроки сохранены, UUID в email.
     */
    @Test
    @DisplayName("Свободный username длиной четыре символа допускает создание игрока")
    @Tag("CREATE-04")
    void acceptsUsernameAtMinimumLength() {
        var neighbour = context.preparePlayer("Контрольный");
        var before = Allure.step("Прочитать занятые username перед граничным созданием",
                () -> context.snapshot());
        Allure.step("Подтвердить контрольного игрока перед выбором свободного username",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(neighbour)));
        var occupied = before.stream().map(ObservedPlayerResponse::username).collect(Collectors.toSet());
        var username = PlayerData.minimumLengthUsername(occupied);
        var valid = context.newPlayerRequest("Граница");
        var request = new CreatePlayerRequest(valid.currencyCode(), valid.email(), valid.name(),
                valid.passwordChange(), valid.passwordRepeat(), valid.surname(), username);

        var response = Allure.step("Создать игрока со свободным username на нижней границе длины",
                () -> context.create(request));
        var created = Allure.step("Создание с коротким username вернуло HTTP 201 и исходные поля",
                () -> PlayersExpected.verifyCreated(response, request, context.ownedIds(), context.codec()));
        var player = new RegisteredPlayer(created.id(), request);
        var profile = Allure.step("Прочитать профиль игрока с коротким username", () -> context.profile(request));
        var after = Allure.step("Прочитать список после создания игрока с коротким username",
                () -> context.snapshot());

        Allure.step("Создан ровно один игрок с коротким username, исходные поля и прежние игроки сохранены", () -> {
            PlayersExpected.verifyProfile(profile, request, player.id(), context.codec());
            PlayersExpected.verifyOwnPlayers(after, List.of(neighbour, player));
            PlayersExpected.verifyCreationState(before, after, player);
        });
    }

    /**
     * Design: оба пароля равны и короче minLength=4; несовпадение паролей исключено как причина отказа.
     * Steps: подтвердить соседа, отправить создание с двумя трёхсимвольными паролями, перечитать.
     * Expected: 400 по принятому правилу проекта; см. docs/api-spec.md.
     * Новая запись отсутствует, исходное состояние сохранено до резервной очистки.
     */
    @Test
    @DisplayName("Одинаковые пароли длиной три символа не допускают создание игрока")
    @Tag("CREATE-04")
    @ExpectedFailure(bug = "BUG-008", failure = KnownFailure.CREATE_INVALID_ACCEPTED)
    void rejectsMatchingShortPasswords() {
        var neighbour = context.preparePlayer("Контрольный");
        var valid = context.newPlayerRequest("Отклоняемый");
        var request = new CreatePlayerRequest(valid.currencyCode(), valid.email(), valid.name(),
                "abc", "abc", valid.surname(), valid.username());
        var before = Allure.step("Прочитать исходное состояние перед созданием с короткими паролями",
                () -> context.snapshot());
        Allure.step("Подтвердить контрольного игрока перед созданием с короткими паролями",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(neighbour)));

        var response = Allure.step("Создать игрока с двумя одинаковыми трёхсимвольными паролями",
                () -> context.create(request));
        var after = Allure.step("Прочитать фактическое состояние до резервной очистки",
                () -> context.list());
        Allure.step("Короткие совпадающие пароли отклонены с HTTP 400 без создания и изменения записей",
                () -> PlayersExpected.verifyRejectedCreate(response, 400, request, before, after));
    }

    /**
     * Design: required, nullable, строковые типы и нижняя недопустимая граница username.
     * Steps: подтвердить соседа, нарушить ограничение нового запроса, создать и перечитать до teardown.
     * Expected: 400 по принятому правилу проекта; см. docs/api-spec.md.
     * Новая запись отсутствует, исходный снимок сохранён.
     * Для длины и числового типа пароли совпадают: проверка равенства не подменяет нужное ограничение.
     */
    @ParameterizedTest(name = "PlayerRequestDTO: {0}")
    @MethodSource("invalidPlayers")
    @DisplayName("Нарушение схемы создания не сохраняет игрока и не меняет исходные записи")
    @Tag("CREATE-03")
    @Tag("CREATE-04")
    @ExpectedFailure(bug = "BUG-008", failure = KnownFailure.CREATE_INVALID_ACCEPTED, caseId = {
            "currency_code:MISSING", "currency_code:NULL", "currency_code:NUMBER", "email:MISSING", "email:NULL",
            "email:NUMBER", "name:MISSING", "name:NULL", "name:NUMBER", "password_change:MISSING",
            "password_change:NULL", "password_change:NUMBER", "password_repeat:MISSING", "password_repeat:NULL",
            "surname:MISSING", "surname:NULL", "surname:NUMBER", "username:MISSING",
            "username:NULL", "username:NUMBER", "username:TOO_SHORT"})
    void rejectsInvalidPlayer(InvalidJsonField invalid) {
        var neighbour = context.preparePlayer("Контрольный");
        var request = context.newPlayerRequest("Отклоняемый");
        var body = invalid.apply((ObjectNode) context.codec().tree(context.codec().encode(request)));
        if (invalid.field().startsWith("password_")
                && (invalid.violation() == Violation.TOO_SHORT || invalid.violation() == Violation.NUMBER)) {
            String pair = "password_change".equals(invalid.field()) ? "password_repeat" : "password_change";
            body.set(pair, body.get(invalid.field()).deepCopy());
        }
        var before = Allure.step("Прочитать исходное состояние перед некорректным созданием",
                () -> context.snapshot());
        Allure.step("Подтвердить контрольного игрока перед некорректным созданием",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(neighbour)));

        var response = Allure.step("Создать игрока с нарушением опубликованного ограничения",
                () -> context.create(body, AuthorizationMode.VALID));
        var after = Allure.step("Прочитать фактическое состояние до резервной очистки",
                () -> context.list());
        Allure.step("Создание отклонено с HTTP 400 без новой записи и изменения исходного состояния",
                () -> PlayersExpected.verifyRejectedCreate(response, 400, request, before, after));
    }

    /**
     * Design: отсутствующий и неверный Bearer при валидном теле; все остальные предпосылки подтверждены.
     * Steps: подготовить соседа, отправить создание с отрицательной авторизацией, перечитать с токеном.
     * Expected: 401 по принятому правилу проекта; см. docs/api-spec.md.
     * Попытка не добавила запись и не изменила снимок.
     */
    @ParameterizedTest(name = "Bearer: {0}")
    @EnumSource(value = AuthorizationMode.class, names = {"MISSING", "INVALID"})
    @DisplayName("Отсутствующий или неверный Bearer не разрешает создание игрока")
    @Tag("ACCESS-01")
    @Tag("ACCESS-02")
    void rejectsUnauthorizedCreation(AuthorizationMode mode) {
        var neighbour = context.preparePlayer("Контрольный");
        var request = context.newPlayerRequest("Отклоняемый");
        var body = context.codec().tree(context.codec().encode(request));
        var before = Allure.step("Прочитать исходное состояние перед созданием без доступа",
                () -> context.snapshot());
        Allure.step("Подтвердить контрольного игрока перед созданием без доступа",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(neighbour)));

        var response = Allure.step("Создать игрока с отсутствующим или неверным Bearer",
                () -> context.create(body, mode));
        var after = Allure.step("Авторизованно прочитать состояние до резервной очистки",
                () -> context.snapshot());
        Allure.step("Отказ HTTP 401 не изменил записи и не раскрыл идентификаторы сверх запроса", () -> {
            PlayersExpected.verifyRejectedCreate(response, 401, request, before, after);
            PlayersExpected.verifyNoPlayerIdentifiers(response, before, Set.of(request.email(), request.username()),
                    context.codec());
        });
    }

    private static Stream<InvalidJsonField> invalidPlayers() {
        var structural = Stream.of("currency_code", "email", "name", "password_change", "password_repeat",
                "surname", "username")
                .flatMap(field -> Stream.of(Violation.MISSING, Violation.NULL, Violation.NUMBER)
                        .filter(violation -> !("password_repeat".equals(field) && violation == Violation.NUMBER))
                        .map(violation -> new InvalidJsonField(field, violation)));
        var boundaries = Stream.of("username")
                .map(field -> new InvalidJsonField(field, Violation.TOO_SHORT));
        return Stream.concat(structural, boundaries);
    }
}
