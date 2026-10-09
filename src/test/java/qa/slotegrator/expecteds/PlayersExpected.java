package qa.slotegrator.expecteds;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import qa.slotegrator.api.ObservedPlayerContract;
import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedCreatedPlayerResponse;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.api.model.PlayerId;
import qa.slotegrator.helpers.data.RegisteredPlayer;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.json.PayloadException;
import qa.slotegrator.helpers.reporting.KnownFailure;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ожидания игрока: исходный запрос — независимый эталон, согласованность ответов проверяется отдельно. */
public final class PlayersExpected {
    public static final int CREATE_STATUS = 201;

    private PlayersExpected() {
    }

    public static void verifyEmpty(List<ObservedPlayerResponse> players) {
        assertTrue(players.isEmpty(), "Ожидался полностью пустой список игроков");
    }

    public static ObservedCreatedPlayerResponse verifyCreated(HttpResult response, CreatePlayerRequest request,
            Set<String> ownedIds, JsonCodec codec) {
        assertEquals(CREATE_STATUS, response.status(), "Создание должно вернуть HTTP 201");
        var body = codec.tree(response.body());
        ObservedPlayerContract.created(body);
        var fields = player(body, "_id");
        var player = new ObservedCreatedPlayerResponse(fields.id(), fields.username(), fields.email(), fields.name(),
                fields.surname(), fields.currencyCode());
        assertTrue(ownedIds.contains(player.id()), "ID создания не подтверждён снимком собственных записей");
        verifySubmittedFields(player.username(), player.email(), player.name(), player.surname(), player.currencyCode(),
                request);
        return player;
    }

    public static void verifyProfile(HttpResult response, CreatePlayerRequest request,
            String createdId, JsonCodec codec) {
        assertTrue(response.status() == 200 || response.status() == 201,
                "Профиль должен вернуть опубликованный HTTP 200 либо известный HTTP 201; получен " + response.status());
        var body = codec.tree(response.body());
        ObservedPlayerContract.profile(body);
        var player = player(body, "id");
        verifySubmittedFields(player.username(), player.email(), player.name(), player.surname(), player.currencyCode(),
                request);
        assertTrue(Objects.equals(createdId, player.id()), "ID профиля отличается от ID подтверждённого создания");
    }

    public static void verifyRegistrations(List<RegisteredPlayer> players, int expectedCount) {
        assertEquals(expectedCount, players.size(), "Создано неверное количество игроков");
        var ids = players.stream().map(RegisteredPlayer::id).toList();
        assertTrue(ids.stream().allMatch(PlayerId::isSafe), "Создание содержит небезопасный ID");
        assertEquals(players.size(), new HashSet<>(ids).size(), "Создание вернуло повторяющиеся ID");
    }

    public static List<ObservedPlayerResponse> verifyList(HttpResult response,
            List<RegisteredPlayer> registered, JsonCodec codec) {
        var players = readList(response, codec);
        assertEquals(registered.size(), players.size(), "Список содержит неверное количество игроков");
        verifyOwnPlayers(players, registered);
        return players;
    }

    public static List<ObservedPlayerResponse> readList(HttpResult response, JsonCodec codec) {
        assertEquals(200, response.status(), "Список должен вернуть HTTP 200");
        var json = codec.tree(response.body());
        assertTrue(json.isArray(), "Список должен быть корневым массивом");
        json.forEach(ObservedPlayerContract::profile);
        var decoded = new ArrayList<ObservedPlayerResponse>();
        json.forEach(entry -> decoded.add(player(entry, "id")));
        var players = List.copyOf(decoded);
        var ids = players.stream().map(ObservedPlayerResponse::id).toList();
        assertEquals(players.size(), new HashSet<>(ids).size(), "Список содержит повторяющиеся ID");
        return players;
    }

    public static void verifyOwnPlayers(List<ObservedPlayerResponse> players, List<RegisteredPlayer> registered) {
        var ids = players.stream().map(ObservedPlayerResponse::id).toList();
        assertEquals(players.size(), new HashSet<>(ids).size(), "Список содержит повторяющиеся ID");
        // До преобразования в Map отдельно отвергаем повтор ID исходных регистраций.
        verifyRegistrations(registered, registered.size());
        var actual = players.stream().collect(Collectors.toMap(ObservedPlayerResponse::id, Function.identity()));
        for (var expected : registered) {
            assertTrue(actual.containsKey(expected.id()), "Собственный игрок отсутствует в списке");
            var player = actual.get(expected.id());
            verifySubmittedFields(player.username(), player.email(), player.name(), player.surname(),
                    player.currencyCode(), expected.request());
        }
    }

    public static void verifyUnchanged(List<ObservedPlayerResponse> before, List<ObservedPlayerResponse> after) {
        assertEquals(before.size(), after.size(), "Операция изменила количество записей");
        assertEquals(after.size(), new HashSet<>(after.stream().map(ObservedPlayerResponse::id).toList()).size(),
                "После операции появились повторяющиеся ID");
        assertTrue(new HashSet<>(before).equals(new HashSet<>(after)), "Операция изменила состав или поля записей");
    }

    public static void verifyCreationState(List<ObservedPlayerResponse> before, List<ObservedPlayerResponse> after,
            RegisteredPlayer created) {
        verifyOwnPlayers(after, List.of(created));
        assertTrue(before.stream().noneMatch(player -> created.id().equals(player.id())),
                "Создание вернуло ID исходной записи");
        verifyUnchanged(before, after.stream().filter(player -> !created.id().equals(player.id())).toList());
    }

    public static void verifyRejectedCreate(HttpResult response, int expectedStatus, CreatePlayerRequest attempted,
            List<ObservedPlayerResponse> before, List<ObservedPlayerResponse> after) {
        // Даже известное сохранение некорректной новой записи не должно скрывать порчу исходных данных.
        var originalIds = before.stream().map(ObservedPlayerResponse::id).collect(Collectors.toSet());
        verifyUnchanged(before, after.stream().filter(player -> originalIds.contains(player.id())).toList());
        var added = after.stream().filter(player -> !originalIds.contains(player.id())).toList();
        KnownFailure.CREATE_INVALID_ACCEPTED.rejectIf(expectedStatus == 400 && response.status() == 201
                && added.size() == 1 && (attempted.email().equals(added.getFirst().email())
                        || attempted.username().equals(added.getFirst().username())));
        assertAll("Отказ создания не сохранил данные",
                () -> assertEquals(expectedStatus, response.status(), "Неверный HTTP-код отказа создания"),
                () -> assertTrue(after.stream().noneMatch(player -> attempted.email().equals(player.email())
                        || attempted.username().equals(player.username())),
                        "Отклонённый запрос фактически создал игрока"),
                () -> verifyUnchanged(before, after));
    }

    public static void verifyRejectedOperation(HttpResult response, int expectedStatus,
            List<ObservedPlayerResponse> before, List<ObservedPlayerResponse> after) {
        assertAll("Отказ операции не изменил данные",
                () -> assertEquals(expectedStatus, response.status(), "Неверный HTTP-код отказа операции"),
                () -> verifyUnchanged(before, after));
    }

    /** Отказ не раскрывает ID/email/username существующих игроков сверх явно переданных значений запроса. */
    public static void verifyNoPlayerIdentifiers(HttpResult response, List<ObservedPlayerResponse> protectedPlayers,
            Set<String> suppliedIdentifiers, JsonCodec codec) {
        String text = new String(response.body(), StandardCharsets.UTF_8);
        try {
            text = codec.text(codec.tree(response.body()));
        } catch (PayloadException ignored) {
            // Формат отказа не задан; известные идентификаторы проверяем также в текстовом ответе.
        }
        for (var player : protectedPlayers) {
            for (String identifier : List.of(player.id(), player.email(), player.username())) {
                if (!identifier.isBlank() && !suppliedIdentifiers.contains(identifier))
                    assertTrue(!text.contains(identifier), "Отказ раскрыл идентификатор игрока сверх данных запроса");
            }
        }
    }

    public static void verifyDeleted(HttpResult response, RegisteredPlayer target,
            List<ObservedPlayerResponse> before, List<ObservedPlayerResponse> after, JsonCodec codec) {
        verifyOwnPlayers(before, List.of(target));
        assertAll("Удалён только целевой игрок",
                () -> {
                    assertEquals(200, response.status(), "Удаление должно вернуть HTTP 200");
                    var body = codec.tree(response.body());
                    assertTrue(target.id().equals(ObservedPlayerContract.playerId(body, "_id")),
                            "Ответ DELETE относится к другому игроку");
                },
                () -> assertTrue(after.stream().noneMatch(player -> target.id().equals(player.id())
                        || target.request().email().equals(player.email())
                        || target.request().username().equals(player.username())),
                        "Целевой игрок остался после удаления"),
                () -> verifyUnchanged(before.stream().filter(player -> !target.id().equals(player.id())).toList(),
                        after));
    }

    public static void verifySortedPlayers(List<ObservedPlayerResponse> original,
            List<ObservedPlayerResponse> sorted, List<String> expectedNames) {
        assertEquals(original.size(), sorted.size(), "Сортировка изменила количество игроков");
        assertEquals(original.size(), new HashSet<>(original.stream().map(ObservedPlayerResponse::id).toList()).size(),
                "Исходный список содержит повторяющиеся ID");
        assertEquals(sorted.size(), new HashSet<>(sorted.stream().map(ObservedPlayerResponse::id).toList()).size(),
                "Сортировка продублировала ID");
        assertTrue(new HashSet<>(original).equals(new HashSet<>(sorted)),
                "Сортировка изменила данные или состав игроков");
        assertEquals(expectedNames, sorted.stream().map(ObservedPlayerResponse::name).toList(),
                "Порядок имён отличается от независимого эталона");
    }

    private static ObservedPlayerResponse player(tools.jackson.databind.JsonNode body, String observedId) {
        return new ObservedPlayerResponse(ObservedPlayerContract.playerId(body, observedId),
                body.get("username").stringValue(), body.get("email").stringValue(), body.get("name").stringValue(),
                body.get("surname").stringValue(), body.get("currency_code").stringValue());
    }

    private static void verifySubmittedFields(String username, String email, String name, String surname,
            String currencyCode, CreatePlayerRequest request) {
        // Не передаём строки ответа в assertEquals: произвольное отражение секрета не должно попасть в error output.
        assertAll("Игрок соответствует исходному запросу",
                () -> assertTrue(Objects.equals(request.username(), username), "Поле username изменено"),
                () -> assertTrue(Objects.equals(request.email(), email), "Поле email изменено"),
                () -> assertTrue(Objects.equals(request.name(), name), "Поле name изменено"),
                () -> assertTrue(Objects.equals(request.surname(), surname), "Поле surname изменено"),
                () -> assertTrue(Objects.equals(request.currencyCode(), currencyCode), "Поле currency_code изменено"));
    }
}
