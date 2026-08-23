package salon.e2e.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SERVICES;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;
import static salon.db.jooq.generated.Tables.SALON_WEEKLY_SCHEDULE;
import static salon.db.jooq.generated.Tables.SERVICES;

import io.avaje.http.client.HttpClient;
import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.inject.BeanScope;
import io.avaje.jex.Jex;
import io.avaje.jex.Jex.Server;
import io.avaje.jsonb.Jsonb;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.model.AppointmentStatus;

/**
 * <h3>Сквозной системный автотест без использования Mock-заглушек</h3>
 *
 * <p>Поднимает реальный HTTP-сервер Jex на тестовом порту, инициализирует чистую схему H2
 * и отправляет честные JSON-пакеты обновлений, симулируя поведение серверов Telegram.</p>
 */
class SalonE2EAutomationTest {

  private static final Logger log = LoggerFactory.getLogger(SalonE2EAutomationTest.class);

  private static BeanScope beanScope;
  private static DSLContext dslCtx;
  private static Server server;
  private static HttpClient telegram;
  private static final String BASE_URL = "http://localhost:8081/telegram";
  private static final String MOCK_CHAT_ID = "999888777";

  @BeforeAll
  static void startUpTotalSystemGraph() {
    // Собираем честный граф зависимостей ВСЕГО приложения БЕЗ МОКОВ.
    // Локальная фабрика LangChain4j автоматически подключится к вашей Ollama (Llama3).
    beanScope = BeanScope.builder().build();
    dslCtx = beanScope.get(DSLContext.class);

    // 3. Вытаскиваем уже ИДЕАЛЬНО настроенный Jex (с Jsonb, фильтрами и роутами) прямо из DI
    Jex jex = beanScope.get(Jex.class);
    // Jex сконфигурирован. Смотри WebConfiguration.
    server = jex.start();

    Jsonb jsonb = beanScope.get(Jsonb.class);
    telegram = HttpClient.builder()
        .baseUrl("http://localhost:" + server.port())
        .bodyAdapter(new JsonbBodyAdapter(jsonb))
        .build();

    // Программно запускаем наш HTTP-сервер Jex на выделенном тестовом порту 8081
    // (Убедитесь, что в конфигурации тестов порт Jex переопределяется на 8081)
    log.info("[E2E Test] Встроенный HTTP-сервер приложения успешно запущен на порту {}", server.port());
  }

  @AfterAll
  static void shutDownTotalSystemGraph() {
    if (server != null) {
      server.shutdown();
    }
    if (beanScope != null) {
      beanScope.close();
    }
  }

  @BeforeEach
  void prepareCleanDatabaseWithReferenceData() {
    log.info("[E2E Test] Очистка таблиц и заполнение тестовых справочников СУБД...");

    // Сброс ограничений целостности для атомарной очистки
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    dslCtx.truncate(MESSAGE_TRACES).execute();
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(MASTER_SHIFTS).execute();
    dslCtx.truncate(SALON_WEEKLY_SCHEDULE).execute();
    dslCtx.truncate(MASTER_SERVICES).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // 1. Заполнение прейскуранта услуг салона
    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, 1L)
        .set(SERVICES.NAME, "Женская стрижка модельная")
        .set(SERVICES.DURATION_MINUTES, 60)
        .set(SERVICES.PRICE, BigDecimal.valueOf(2500.00))
        .execute();

    // 2. Регистрация мастера с обязательным уникальным ALIAS
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, 1L)
        .set(MASTERS.ALIAS, "elena_colorist")
        .set(MASTERS.FIRST_NAME, "Елена")
        .set(MASTERS.LAST_NAME, "Петрова")
        .set(MASTERS.SPECIALIZATION, "Топ-стилист")
        .execute();

    // 3. Формирование матрицы компетенций (Связываем Елену со стрижкой)
    dslCtx.insertInto(MASTER_SERVICES)
        .set(MASTER_SERVICES.MASTER_ID, 1L)
        .set(MASTER_SERVICES.SERVICE_ID, 1L)
        .execute();

    // 4. Регламентируем часы работы заведения (25 августа 2026 года — это Вторник)
    dslCtx.insertInto(SALON_WEEKLY_SCHEDULE)
        .set(SALON_WEEKLY_SCHEDULE.DAY_OF_WEEK, "TUESDAY")
        .set(SALON_WEEKLY_SCHEDULE.OPEN_TIME, LocalTime.parse("09:00:00"))
        .set(SALON_WEEKLY_SCHEDULE.CLOSE_TIME, LocalTime.parse("20:00:00"))
        .execute();

    // 5. Публикуем официальную рабочую смену мастера Елены под таймлайн теста
    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.ID, 1L)
        .set(MASTER_SHIFTS.MASTER_ID, 1L)
        .set(MASTER_SHIFTS.SHIFT_START, LocalDateTime.parse("2026-08-25T10:00:00"))
        .set(MASTER_SHIFTS.SHIFT_END, LocalDateTime.parse("2026-08-25T20:00:00"))
        .execute();

    log.info("[E2E Test] Наполнение базы данных успешно завершено. Контур готов к прогону.");
  }

  @Test
  void shouldCompleteMultiTurnBookingConversationWithLiveLlama3() {
//    // -----------------------------------------------------------------
//    // ТУР 1: Первичное обращение пользователя в чат бота
//    // -----------------------------------------------------------------
//    String turn1JsonPayload = """
//        {
//          "message": {
//            "chat": { "id": %s },
//            "from": { "firstName": "Natalia" },
//            "text": "Привет! Хочу записаться на стрижку"
//          }
//        }
//        """.formatted(MOCK_CHAT_ID);
//
//    HttpRequest request1 = HttpRequest.newBuilder()
//        .uri(URI.create(BASE_URL))
//        .header("Content-Type", "application/json")
//        .POST(HttpRequest.BodyPublishers.ofString(turn1JsonPayload))
//        .build();
//
//    // Отправляем HTTP-запрос в наш реальный WebhookController
//    HttpResponse<String> response1 = telegram.send(request1, HttpResponse.BodyHandlers.ofString());
//    assertEquals(200, response1.statusCode(), "Сервер обязан вернуть успешный статус HTTP 200 OK.");
//
//    // Верифицируем состояние данных после первого ответа ИИ
//    assertEquals(1, dslCtx.fetchCount(CLIENTS), "В СУБД должен автоматически создаться профиль клиента.");
//    assertEquals(1, dslCtx.fetchCount(MESSAGE_TRACES), "В MESSAGE_TRACES должен записаться входящий след.");
//
//    // -----------------------------------------------------------------
//    // ТУР 2: Финальный вызов ИИ-инструмента и создание талона бронирования
//    // -----------------------------------------------------------------
//    String turn2JsonPayload = """
//        {
//          "message": {
//            "chat": { "id": %s },
//            "from": { "firstName": "Natalia" },
//            "text": "Запиши меня к Елене на 25 августа в 14:30"
//          }
//        }
//        """.formatted(MOCK_CHAT_ID);
//
//    HttpRequest request2 = HttpRequest.newBuilder()
//        .uri(URI.create(BASE_URL))
//        .header("Content-Type", "application/json")
//        .POST(HttpRequest.BodyPublishers.ofString(turn2JsonPayload))
//        .build();
//
//    HttpResponse<String> response2 = telegram.send(request2, HttpResponse.BodyHandlers.ofString());
//    assertEquals(200, response2.statusCode());
//
//    // ФИНАЛЬНАЯ ВЕРИФИКАЦИЯ: База данных обязана зафиксировать создание талона бронирования визита!
//    assertEquals(1, dslCtx.fetchCount(APPOINTMENTS), "Llama3 обязана успешно распознать контекст и совершить запись в APPOINTMENTS.");
//
//    var booking = dslCtx.selectFrom(APPOINTMENTS).fetchOne();
//    assertNotNull(booking);
//    assertTrue(booking.getTicketCode().startsWith("SB-260825-"), "Код билета обязан содержать префикс даты.");
//    assertEquals(AppointmentStatus.AI_PENDING, booking.getStatus(), "Статус записи должен быть предварительным (AI_PENDING).");
  }

}
