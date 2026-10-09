package qa.slotegrator.tests.self.expecteds;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.helpers.data.RegisteredPlayer;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Независимый оракул состава и сортировки списка игроков")
class PlayersListExpectedTest {
    private static final int PLAYER_COUNT = 3;
    private static final JsonCodec CODEC = new JsonCodec();
    private static final String ANNA_ONE_ID = "0123456789abcdef01234567";
    private static final String ANNA_TWO_ID = "123456789abcdef012345678";
    private static final String BORIS_ID = "23456789abcdef0123456789";
    private static final String EXTRA_ID = "3456789abcdef0123456789a";

    private static final List<RegisteredPlayer> REGISTERED = List.of(
            new RegisteredPlayer(BORIS_ID, new CreatePlayerRequest(
                    "USD", "boris@example.test", "Борис", "dummy-list-password",
                    "dummy-list-password", "Сидоров", "list_boris")),
            new RegisteredPlayer(ANNA_TWO_ID, new CreatePlayerRequest(
                    "EUR", "anna-two@example.test", "Анна", "dummy-list-password",
                    "dummy-list-password", "Петрова", "list_anna_two")),
            new RegisteredPlayer(ANNA_ONE_ID, new CreatePlayerRequest(
                    "GBP", "anna-one@example.test", "Анна", "dummy-list-password",
                    "dummy-list-password", "Иванова", "list_anna_one")));

    // Ответы заданы отдельно от запросов: эталон не выводится из наблюдаемого списка.
    private static final ObservedPlayerResponse ANNA_ONE = new ObservedPlayerResponse(
            ANNA_ONE_ID, "list_anna_one", "anna-one@example.test", "Анна", "Иванова", "GBP");
    private static final ObservedPlayerResponse ANNA_TWO = new ObservedPlayerResponse(
            ANNA_TWO_ID, "list_anna_two", "anna-two@example.test", "Анна", "Петрова", "EUR");
    private static final ObservedPlayerResponse BORIS = new ObservedPlayerResponse(
            BORIS_ID, "list_boris", "boris@example.test", "Борис", "Сидоров", "USD");
    private static final List<ObservedPlayerResponse> ORIGINAL = List.of(BORIS, ANNA_TWO, ANNA_ONE);
    // Известный порядок выбран вручную; обе Анны остаются отдельными записями.
    private static final List<ObservedPlayerResponse> SORTED = List.of(ANNA_ONE, ANNA_TWO, BORIS);

    private static final List<ListCase> VALID_LISTS = List.of(
            new ListCase("исходный-порядок", ORIGINAL),
            new ListCase("переставленные-записи", List.of(ANNA_ONE, BORIS, ANNA_TWO)));
    private static final List<ListCase> INVALID_MEMBERSHIP = List.of(
            new ListCase("пропущен-игрок", List.of(BORIS, ANNA_ONE)),
            new ListCase("лишний-игрок", List.of(BORIS, ANNA_TWO, ANNA_ONE, withId(ANNA_ONE, EXTRA_ID))),
            new ListCase("дубликат-id", List.of(BORIS, ANNA_ONE, ANNA_ONE)),
            new ListCase("подменён-валидный-id", List.of(BORIS, ANNA_TWO, withId(ANNA_ONE, EXTRA_ID))));
    private static final List<ListCase> CHANGED_LIST_FIELDS = List.of(
            new ListCase("подменено-имя", List.of(withName(BORIS, "Изменено сервисом"), ANNA_TWO, ANNA_ONE)),
            new ListCase("поля-переставлены-между-id", List.of(BORIS,
                    withId(ANNA_ONE, ANNA_TWO_ID), withId(ANNA_TWO, ANNA_ONE_ID))));
    private static final List<ListCase> INVALID_SORTING = List.of(
            new ListCase("нарушен-порядок", List.of(ANNA_ONE, BORIS, ANNA_TWO)),
            new ListCase("потеряна-запись-с-тем-же-именем", List.of(ANNA_ONE, BORIS)),
            new ListCase("продублирована-запись-с-тем-же-именем", List.of(ANNA_ONE, ANNA_ONE, BORIS)),
            new ListCase("изменена-запись-при-правильном-порядке", List.of(ANNA_ONE, ANNA_TWO,
                    new ObservedPlayerResponse(BORIS_ID, BORIS.username(), "changed@example.test",
                            BORIS.name(), BORIS.surname(), BORIS.currencyCode()))));

    /**
     * Design: метаморфная проверка — перестановка ответа сохраняет состав и связь ID с исходным запросом.
     * Steps: подтвердить три регистрации и проверить независимо заданный список в двух порядках.
     * Expected: все исходные поля совпадают, ожидание возвращает прочитанные записи без потерь и перестановок.
     */
    @ParameterizedTest(name = "Правильный список: {0}")
    @MethodSource("validLists")
    void acceptsExactRecordsFromOriginalRequests(ListCase testCase) {
        assertDoesNotThrow(() -> PlayersExpected.verifyRegistrations(REGISTERED, PLAYER_COUNT));
        var result = PlayersExpected.verifyList(response(testCase.players()), REGISTERED, CODEC);
        assertEquals(testCase.players(), result, "Проверка списка должна вернуть все прочитанные записи в их порядке");
    }

    /**
     * Design: классы эквивалентности — пропуск, добавление, дублирование и замена ID при прежнем размере.
     * Steps: изменить только состав корректного списка и сверить с неизменными регистрациями.
     * Expected: ни количество, ни множество без учёта дублей не заменяют точную проверку состава.
     */
    @ParameterizedTest(name = "Неверный состав списка: {0}")
    @MethodSource("invalidMembership")
    void rejectsMissingExtraDuplicateAndReplacedIds(ListCase testCase) {
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyList(response(testCase.players()), REGISTERED, CODEC));
    }

    /**
     * Design: error guessing — ответ меняет поле или приписывает правильные поля чужому ID.
     * Steps: сохранить количество и уникальные собственные ID, подменив имя либо данные двух Анн.
     * Expected: список сравнивается с исходным запросом именно этого ID, а не только с другими ответами.
     */
    @ParameterizedTest(name = "Независимые поля списка: {0}")
    @MethodSource("changedListFields")
    void rejectsFieldsChangedFromOriginalRequests(ListCase testCase) {
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyList(response(testCase.players()), REGISTERED, CODEC));
    }

    /**
     * Design: классы эквивалентности — другой успешный код, неверный корень и числовая валюта.
     * Steps: предъявить отдельно испорченный код или JSON-форму при правильном остальном содержимом.
     * Expected: ожидание отвергает сырой контракт до декодирования и предметного сравнения полей.
     */
    @ParameterizedTest(name = "Неверный контракт списка: {0}")
    @MethodSource("invalidResponses")
    void rejectsInvalidStatusRootAndCurrencyType(ResponseCase testCase) {
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyList(testCase.response(), REGISTERED, CODEC));
    }

    /**
     * Design: error guessing — повтор ID регистраций теряется или вызывает техническую ошибку при создании Map.
     * Steps: передать три регистрации с повтором ID и отдельный корректный ответ с тремя уникальными ID.
     * Expected: оба публичных ожидания дают предметный AssertionError, а не ошибку сборки Map.
     */
    @Test
    @DisplayName("Повтор ID регистраций отвергается до преобразования в Map")
    void rejectsDuplicateRegistrationsBeforeBuildingMap() {
        var duplicateRegistered = List.of(REGISTERED.get(0), REGISTERED.get(1),
                new RegisteredPlayer(ANNA_TWO_ID, REGISTERED.get(2).request()));
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyRegistrations(duplicateRegistered, PLAYER_COUNT));
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyList(response(ORIGINAL), duplicateRegistered, CODEC));
    }

    /**
     * Design: граница равенства ключей — две записи имеют одинаковое имя, но разные ID и прочие поля.
     * Steps: сверить исходный снимок с вручную заданным порядком «Анна, Анна, Борис».
     * Expected: обе Анны сохраняются, равные имена допустимы без дополнительного порядка по ID.
     */
    @Test
    @DisplayName("Сортировка сохраняет обе разные записи с одинаковым именем")
    void acceptsKnownOrderWithDistinctRecordsSharingName() {
        assertDoesNotThrow(
                () -> PlayersExpected.verifySortedPlayers(ORIGINAL, SORTED, List.of("Анна", "Анна", "Борис")));
    }

    /**
     * Design: мутации результата — неверный порядок, потеря, дублирование или изменение записи.
     * Steps: предъявить каждый результат одному неизменному исходному снимку.
     * Expected: правильного порядка имён недостаточно при нарушении полного состава или данных.
     */
    @ParameterizedTest(name = "Некорректный результат сортировки: {0}")
    @MethodSource("invalidSorting")
    void rejectsWrongOrderAndChangedRecords(ListCase testCase) {
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifySortedPlayers(ORIGINAL, testCase.players(),
                        List.of("Анна", "Анна", "Борис")));
    }

    private static Stream<ListCase> validLists() {
        return VALID_LISTS.stream();
    }

    private static Stream<ListCase> invalidMembership() {
        return INVALID_MEMBERSHIP.stream();
    }

    private static Stream<ListCase> changedListFields() {
        return CHANGED_LIST_FIELDS.stream();
    }

    private static Stream<ResponseCase> invalidResponses() {
        var numericCurrency = CODEC.tree(CODEC.encode(ORIGINAL));
        ((ObjectNode) numericCurrency.get(0)).put("currency_code", 840);
        return Stream.of(
                new ResponseCase("другой-успешный-статус", HttpResult.json(201, CODEC.text(ORIGINAL))),
                new ResponseCase("объект-вместо-массива", HttpResult.json(200, CODEC.text(BORIS))),
                new ResponseCase("числовая-валюта", HttpResult.json(200, CODEC.text(numericCurrency))));
    }

    private static Stream<ListCase> invalidSorting() {
        return INVALID_SORTING.stream();
    }

    private static HttpResult response(List<ObservedPlayerResponse> players) {
        return HttpResult.json(200, CODEC.text(players));
    }

    private static ObservedPlayerResponse withId(ObservedPlayerResponse player, String id) {
        return new ObservedPlayerResponse(id, player.username(), player.email(), player.name(), player.surname(),
                player.currencyCode());
    }

    private static ObservedPlayerResponse withName(ObservedPlayerResponse player, String name) {
        return new ObservedPlayerResponse(player.id(), player.username(), player.email(), name, player.surname(),
                player.currencyCode());
    }

    private record ListCase(String id, List<ObservedPlayerResponse> players) {
        @Override
        public String toString() {
            return id;
        }
    }

    private record ResponseCase(String id, HttpResult response) {
        @Override
        public String toString() {
            return id;
        }
    }
}
