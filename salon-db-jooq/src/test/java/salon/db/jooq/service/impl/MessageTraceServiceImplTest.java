package salon.db.jooq.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import salon.api.model.PlatformType;
import salon.api.service.MessageTraceService;

/**
 * <h2>Компонентный тест сервиса архивации сообщений салона</h2>
 * Архитектурно наследует подход {@code BookingServiceImplTest}.
 *
 * <p>Аннотация {@code @InjectTest} заставляет avaje-inject автоматически собрать
 * тестовый BeanScope, поднять H2-контекст jOOQ и внедрить зависимости в поля.</p>
 */
@InjectTest
public class MessageTraceServiceImplTest {

  // Автоматически внедряем jOOQ контекст, настроенный на работу с H2 из application-test.properties
  @Inject
  public DSLContext dsl;

  // Внедряем сам тестируемый сервис, сгенерированный и собранный DI-контейнером
  @Inject
  public MessageTraceService messageTraceService;

  // Уникальный ID клиента для этого тестового прогона
  private final String testPlatformId = "telegram_chat_999";

  @BeforeEach
  void setUp() {
    // =====================================================================
    // ЭТАЛОННАЯ ОЧИСТКА КОНТЕКСТА: ТУМБЛИРОВАНИЕ ОГРАНИЧЕНИЙ В H2
    // =====================================================================
    // CLEAN UP ENGINE: Temporarily unbind foreign key constraints in H2
    // to prevent cross-test isolation leaks
    dsl.execute("SET REFERENTIAL_INTEGRITY FALSE");

    // Truncate tables cleanly to restore an empty slate database state
    dsl.truncate(MESSAGE_TRACES).execute();
    dsl.truncate(CLIENTS).execute();

    // Re-enable referential validation rules before inserting active business records
    dsl.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // Подготавливаем чистую родительскую запись для беспрепятственной привязки внешних ключей
    dsl.insertInto(CLIENTS)
        .set(CLIENTS.FIRST_NAME, "Natalia")
        .set(CLIENTS.TELEGRAM_ID, testPlatformId)
        .execute();
  }

  @AfterEach
  void tearDown() {
    // ИСПРАВЛЕНО: Обязательно очищаем диагностическую карту потока после каждого теста,
    // чтобы предотвратить утечку контекста логирования в другие тестовые классы!
    MDC.clear();
  }

  /**
   * <p><b>ПРОВЕРЯЕМЫЕ БИЗНЕС-ЦЕЛИ (Сценарий тестирования компонента):</b>
   * <ul>
   *   <li>Убедиться, что DI-контейнер успешно собрал и предоставил рабочий инстанс {@code MessageTraceService}.</li>
   *   <li>Проверить, что jOOQ-запрос INSERT успешно выполняется в реальной H2-базе данных.</li>
   *   <li>Гарантировать корректную запись всех полей (ID, текст, сырой JSON) без генерации SQL-ошибок диалекта.</li>
   * </ul>
   * </p>
   */
  @Test
  void shouldSuccessfullyInsertMessageTraceRecordIntoH2Database() {
    // 1. Генерируем уникальный Trace ID для текущего прогона теста
    final String currentTestTraceId = "TX-MESSAGE-TRACE-RECORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    // ИСПРАВЛЕНО: Явно привязываем сгенерированный ID к диагностическому контексту текущего потока!
    MDC.put("traceId", currentTestTraceId);

    // Arrange: Настраиваем бизнес-параметры сообщения для проверки персистентности
    // Arrange
    String testTraceId = "TX-DB-TEST-777";
    String sampleText = "Проверка записи сквозного доменного журнала";

    // Act
    messageTraceService.logTrace(
        testTraceId,
        PlatformType.TELEGRAM,
        testPlatformId,
        MessageTraceService.Direction.INBOUND,
        "{\"raw_http_payload\": true}",
        sampleText
    );

    // Assert
    var record = dsl.selectFrom(MESSAGE_TRACES)
        .where(MESSAGE_TRACES.TRACE_ID.eq(testTraceId))
        .fetchOptional();

    assertTrue(record.isPresent(), "Запись лога должна успешно сохраниться в физической таблице MESSAGE_TRACES.");

    var savedLog = record.get();
    assertEquals(testTraceId, savedLog.getTraceId());
    assertEquals(PlatformType.TELEGRAM.name(), savedLog.getPlatformType());
    assertEquals(testPlatformId, savedLog.getPlatformId());
    assertEquals("INBOUND", savedLog.getDirection());
    assertEquals(sampleText, savedLog.getMessageText());
    assertNotNull(savedLog.getClientId(), "Суррогатный внешний ключ client_id должен автоматически связаться.");
  }

}
