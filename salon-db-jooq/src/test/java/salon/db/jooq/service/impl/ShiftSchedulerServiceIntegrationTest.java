package salon.db.jooq.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SERVICES;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFT_BREAKS;
import static salon.db.jooq.generated.Tables.SERVICES;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import salon.api.exception.IntegrityViolationException;
import salon.api.model.AppointmentStatus;
import salon.api.service.ShiftSchedulerService;

/**
 * <h3>Integration Suite for Master Shift and Operational Break Management</h3>
 *
 * <p>Leverages {@code @InjectTest} to launch an ultra-fast compile-time generated Avaje test context,
 * establishing a clean relational sandbox environment backed by H2.</p>
 */
@InjectTest
public class ShiftSchedulerServiceIntegrationTest {

  @Inject
  public ShiftSchedulerService shiftSchedulerService;

  @Inject
  public DSLContext dslCtx;

  // Изолированные суррогатные ID для первоначальной настройки связанных строк в БД
  private final long mockClientId = 77001L;
  private final long mockMasterId = 77002L;
  private final long mockServiceId = 77003L;

  // Бесцифровые естественные бизнес-ключи доменного уровня
  private final String mockMasterAlias = "elena_colorist";

  private final LocalDate testDate = LocalDate.of(2026, 8, 20);

  @BeforeEach
  void setUpCleanIsolatedH2State() {
    // Каскадная очистка таблиц СУБД с временным отключением ссылочной целостности
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(MASTER_SHIFT_BREAKS).execute();
    dslCtx.truncate(MASTER_SHIFTS).execute();
    dslCtx.truncate(MASTER_SERVICES).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // 1. Создаем родительский профиль клиента для обеспечения целостности FK в APPOINTMENTS
    final String mockClientPlatformId = "TG-777888";
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, mockClientId)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, mockClientPlatformId)
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    // 2. Создаем эталонную запись мастера с обязательным уникальным бизнес-ключом ALIAS
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, mockMasterId)
        .set(MASTERS.ALIAS, mockMasterAlias)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .execute();

    // 3. Создаем базовую услугу в каталоге SERVICES
    final String mockServiceName = "Техническая стрижка";
    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, mockServiceId)
        .set(SERVICES.NAME, mockServiceName)
        .set(SERVICES.DURATION_MINUTES, 60)
        .set(SERVICES.PRICE, BigDecimal.valueOf(2000.00))
        .execute();

    // ПРИМЕЧАНИЕ: Мы больше не переопределяем поведение SalonScheduleProvider через Mockito.
    // Система автоматически использует реальный или тестовый бин из контекста Avaje,
    // который сверяет коридоры смен с регламентом работы заведения на эту дату [Strict Grounding].
  }

  /**
   * <h3>Тест 1 (Сценарий 5: Вариант 1 и 2): Пакетная публикация смены со встроенными перерывами</h3>
   * <p><b>Бизнес-контекст:</b> Управляющий публикует рабочий день мастера. Смена укладывается
   * в регламент работы заведения. Перерывы автоматически привязываются к сгенерированному ID смены [Strict Grounding].</p>
   */
  @Test
  void shouldSuccessfullyPublishShiftsAndPlannedBreaksUsingMasterAlias() {
    // Arrange: Формируем команду публикации смены (10:00 - 19:00) и одного обеденного перерыва (13:00 - 14:00)
    ShiftSchedulerService.BreakDto lunchBreak = new ShiftSchedulerService.BreakDto(
        testDate.atTime(13, 0),
        testDate.atTime(14, 0)
    );

    ShiftSchedulerService.PublishShiftCommand command = new ShiftSchedulerService.PublishShiftCommand(
        mockMasterAlias, // Передаем строковый бизнес-ключ вместо Long ID
        testDate.atTime(10, 0),
        testDate.atTime(19, 0),
        List.of(lunchBreak)
    );

    // Act: Запускаем доменный метод пакетной публикации
    assertDoesNotThrow(() -> shiftSchedulerService.publishShifts(List.of(command)),
        "Публикация корректной смены по masterAlias обязана завершиться успешно.");

    // Assert: 1. Проверяем, что запись смены физически появилась на диске и связана со скрытым ID мастера
    var shiftRecord = dslCtx.selectFrom(MASTER_SHIFTS)
        .where(MASTER_SHIFTS.MASTER_ID.eq(mockMasterId))
        .fetchOne();

    assertNotNull(shiftRecord, "Строка смены должна быть успешно добавлена в таблицу MASTER_SHIFTS.");
    assertEquals(testDate.atTime(10, 0), shiftRecord.getShiftStart());
    assertEquals(testDate.atTime(19, 0), shiftRecord.getShiftEnd());

    // 2. Верифицируем, что запланированный обеденный перерыв атомарно записался в MASTER_SHIFT_BREAKS
    Long generatedShiftId = shiftRecord.getId();
    var breakRecord = dslCtx.selectFrom(MASTER_SHIFT_BREAKS)
        .where(MASTER_SHIFT_BREAKS.SHIFT_ID.eq(generatedShiftId))
        .fetchOne();

    assertNotNull(breakRecord, "Запланированный перерыв обязан автоматически привязаться к созданному SHIFT_ID.");
    assertEquals(testDate.atTime(13, 0), breakRecord.getBreakStart());
    assertEquals(testDate.atTime(14, 0), breakRecord.getBreakEnd());
  }

  /**
   * <h3>Тест 2 (Сценарий 5: Вариант 3 - Happy Path): Успешное живое внедрение перерыва</h3>
   * <p><b>Бизнес-контекст:</b> Управляющий оперативно добавляет мастеру 30-минутный перерыв.
   * Время полностью свободно от записей клиентов, операция проходит успешно [Strict Grounding].</p>
   */
  @Test
  void shouldSuccessfullyInjectBreakIntoActiveShiftWhenNoClashesExist() {
    // Arrange: 1. Публикуем базовую рабочую смену мастера (10:00 - 20:00) через прямой SQL для чистоты изоляции
    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, mockMasterId)
        .set(MASTER_SHIFTS.SHIFT_START, testDate.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, testDate.atTime(20, 0))
        .execute();

    // Act: Внедряем спонтанный перерыв на кофе (16:00 - 16:30) через строковый псевдоним
    assertDoesNotThrow(() ->
            shiftSchedulerService.injectBreakIntoShift(mockMasterAlias, testDate.atTime(16, 0), testDate.atTime(16, 30)),
        "Внедрение перерыва в свободное окно смены не должно вызывать исключений."
    );

    // Assert: Проверяем фиксацию строки перерыва на диске
    boolean breakExists = dslCtx.fetchExists(
        dslCtx.selectOne()
            .from(MASTER_SHIFT_BREAKS)
            .where(MASTER_SHIFT_BREAKS.BREAK_START.eq(testDate.atTime(16, 0)))
    );
    assertTrue(breakExists, "Запись оперативного перерыва должна быть физически сохранена в СУБД.");
  }

  /**
   * <h3>Тест 3 (Сценарий 5: Вариант 3 - Накладка): Блокировка перерыва при конфликте с клиентом</h3>
   * <p><b>Бизнес-контекст:</b> Управляющий пытается поставить перерыв на время (14:30), где уже
   * присутствует подтвержденная запись клиента (14:00 - 15:00). Приоритет клиента защищает слот, операция отклоняется [Strict Grounding].</p>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenInjectingBreakOntoActiveClientBooking() {
    // Arrange: 1. Публикуем базовую родительскую смену мастера (10:00 - 19:00)
    var shiftRecord = dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, mockMasterId)
        .set(MASTER_SHIFTS.SHIFT_START, testDate.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, testDate.atTime(19, 0))
        .returning(MASTER_SHIFTS.ID)
        .fetchOne();

    Objects.requireNonNull(shiftRecord, "Test setup error: Failed to initialize parent master shift row.");
    Long parentShiftId = shiftRecord.getId();

    // 2. Создаем существующую подтвержденную запись клиента (14:00 - 15:00) со строгим соблюдением бизнес-ключей
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.TICKET_CODE, "SB-20260820-LIVE") // Обязательный токен билета визита
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, testDate.atTime(14, 0))
        .set(APPOINTMENTS.DURATION_MINUTES, 60) // Длительность: 1 час
        .set(APPOINTMENTS.PRICE, BigDecimal.valueOf(2000.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.APPROVED)
        .execute();

    // Попытка управляющего «вклинить» перерыв на 14:30 - 15:00 (Прямое пересечение!)
    LocalDateTime conflictingBreakStart = testDate.atTime(14, 30);
    LocalDateTime conflictingBreakEnd = testDate.atTime(15, 0);

    // Act & Assert: Ожидаем принудительное выбрасывание доменного бизнес-исключения комплаенса
    IntegrityViolationException exception = assertThrows(IntegrityViolationException.class, () ->
        shiftSchedulerService.injectBreakIntoShift(mockMasterAlias, conflictingBreakStart, conflictingBreakEnd)
    );

    // Верифицируем текст ошибки, возвращаемый на экран панели Vaadin UI
    assertTrue(exception.getMessage().contains("на выбранное время уже есть предварительная или подтвержденная запись"),
        "Исключение должно четко содержать регламентированное сообщение о приоритете записи живого клиента.");

    // Дополнительный аудит: Убеждаемся, что транзакция полностью откатилась и строка перерыва НЕ просочилась в СУБД
    boolean breakLeaked = dslCtx.fetchExists(
        dslCtx.selectFrom(MASTER_SHIFT_BREAKS).where(MASTER_SHIFT_BREAKS.SHIFT_ID.eq(parentShiftId))
    );
    assertFalse(breakLeaked, "Критический сбой изоляции: Строка перерыва была сохранена вопреки транзакционному откату.");
  }
}
