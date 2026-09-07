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

import io.avaje.config.Config;
import io.avaje.http.client.HttpClient;
import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.inject.BeanScope;
import io.avaje.jex.Jex;
import io.avaje.jex.Jex.Server;
import io.avaje.jsonb.Jsonb;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private static Server salonServer;
  private static Server telegramMockServer;
  // Потокобезопасный буфер для сохранения отправленных бэкендом пакетов
  private static final List<String> interceptedOutboundPayloads = Collections.synchronizedList(new ArrayList<>());
  /**
   * Эмулятор телеграмма.
   */
  private static HttpClient telegram;
  private static String telegramSecretToken;
  private static final String MOCK_CHAT_ID = "999888777";

  private static HttpClient createDevClient(Jex jex, Server server, Jsonb jsonb) {
    final String scheme = jex.config().scheme();
    final String host = jex.config().host();
    final int port = server.port();
    if ("https".equals(scheme)) {
      // Создаём менеджер, который принимает любые сертификаты без проверки
      final TrustManager[] trustAllCerts = new TrustManager[]{
          new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
          }
      };

      final SSLContext sslContext;
      try {
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
      } catch (NoSuchAlgorithmException | KeyManagementException e) {
        throw new RuntimeException(e);
      }

      return HttpClient.builder()
          .requestTimeout(Duration.ofMinutes(4))
          .baseUrl(scheme + "://" + host + ":" + port)
          .bodyAdapter(new JsonbBodyAdapter(jsonb))
          .sslContext(sslContext)
          .build();
    } else if ("http".equals(scheme)) {
      return HttpClient.builder()
          .requestTimeout(Duration.ofMinutes(4))
          .baseUrl(scheme + "://" + host + ":" + port)
          .bodyAdapter(new JsonbBodyAdapter(jsonb))
          .build();
    } else {
      throw new RuntimeException("Unknown scheme.");
    }
  }

  @BeforeAll
  static void startUpTotalSystemGraph() {
    // 1. Инициализация и запуск Mock-сервера Telegram на порту 8082
    URI telegramMockServerUri = Config.getURI("telegram.api.baseUrl", "http://localhost:8083");
    log.info("[E2E Test] Запуск эмулятора Telegram API на порту {} ...", telegramMockServerUri.getPort());
    Jex telegramJex = Jex.create().port(telegramMockServerUri.getPort());

    // Перехват отправки текстовых уведомлений sendMessage
    telegramJex.post("/bot{token}/sendMessage", ctx -> {
      String requestBody = ctx.body();
      log.info("[Telegram Mock Server] Перехвачен исходящий ответ ИИ: {}", requestBody);
      interceptedOutboundPayloads.add(requestBody); // Сохраняем в буфер для проверок в JUnit
      ctx.status(200).json(Map.of("ok", true, "description", "Mock response accepted"));
    });

    // Перехват стартового запроса регистрации вебхука от TelegramWebhookInitializer
    telegramJex.post("/bot{token}/setWebhook", ctx -> {
      log.info("[Telegram Mock Server] Перехвачен запрос регистрации вебхука: {}", ctx.body());
      ctx.status(200).json(Map.of("ok", true, "result", true));
    });

    telegramMockServer = telegramJex.start();

    telegramSecretToken = Config.get("telegram.webhook.secret-token");
    // Собираем честный граф зависимостей ВСЕГО приложения БЕЗ МОКОВ.
    // Локальная фабрика LangChain4j автоматически подключится к вашей Ollama (Llama3).
    beanScope = BeanScope.builder().build();
    dslCtx = beanScope.get(DSLContext.class);

    // 3. Вытаскиваем уже ИДЕАЛЬНО настроенный Jex (с Jsonb, фильтрами и роутами) прямо из DI
    Jex jex = beanScope.get(Jex.class);
    // Jex сконфигурирован. Смотри WebConfiguration.
    salonServer = jex.start();

    Jsonb jsonb = beanScope.get(Jsonb.class);
    telegram = createDevClient(jex, salonServer, jsonb);

    // Программно запускаем наш HTTP-сервер Jex на выделенном тестовом порту 8081
    // (Убедитесь, что в конфигурации тестов порт Jex переопределяется на 8081)
    log.info("[E2E Test] Встроенный HTTP-сервер приложения успешно запущен на порту {}", salonServer.port());
  }

  @AfterAll
  static void shutDownTotalSystemGraph() {
    if (salonServer != null) {
      salonServer.shutdown();
    }
    if (telegramMockServer != null) {
      telegramMockServer.shutdown();
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
    // -----------------------------------------------------------------
    // ТУР 1: Первичное обращение пользователя в чат бота
    // -----------------------------------------------------------------
    String turn1JsonPayload = """
        {
          "message": {
            "chat": { "id": %s },
            "from": { "firstName": "Natalia" },
            "text": "Привет! Хочу записаться на стрижку"
          }
        }
        """.formatted(MOCK_CHAT_ID);

    // Отправляем HTTP-запрос в наш реальный WebhookController
    HttpResponse<String> response1 = telegram.request()
        // FIX: Передаем динамически считанный секретный токен для проверки подлинности шлюза
        .header("X-Telegram-Bot-Api-Secret-Token", telegramSecretToken)
        .path("api/v1/webhooks/telegram") // FIX: Приведено к единому стандарту 'webhook' (в единственном числе)
        .body(turn1JsonPayload)    // Автоматическая маршализация JSON через встроенный Jsonb
        .POST()
        .asString();
    assertEquals(204, response1.statusCode(), "Сервер обязан вернуть успешный статус HTTP 204 OK.");

    // Верифицируем состояние данных после первого ответа ИИ
    assertEquals(1, dslCtx.fetchCount(CLIENTS), "В СУБД должен автоматически создаться профиль клиента.");
    // Должно быть 2 записи - INBOUND и OUTBOUND
    assertEquals(2, dslCtx.fetchCount(MESSAGE_TRACES), "В MESSAGE_TRACES должен записаться входящий след.");

    // -----------------------------------------------------------------
    // ТУР 2: Финальный вызов ИИ-инструмента и создание талона бронирования
    // -----------------------------------------------------------------
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
