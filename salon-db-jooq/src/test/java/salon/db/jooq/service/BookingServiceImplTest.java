package salon.db.jooq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFT_BREAKS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;
import static salon.db.jooq.generated.Tables.SERVICES;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.Master;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.BookingService;

@InjectTest // CORRECT: Instructs avaje-inject to spin up the test container context
public class BookingServiceImplTest {

  @Inject
  public BookingService bookingService;

  @Inject
  public DSLContext dslCtx;

  @BeforeEach
  void setUp() {
    // Идеальная очистка контекста СУБД H2 перед каждым тестом
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    dslCtx.truncate(MESSAGE_TRACES).execute();
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(MASTER_SHIFTS).execute(); // FIX: Added explicit work shifts reset!
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  /**
   * <h3>Тест 1: Успешное создание профиля нового клиента (Happy Path)</h3>
   */
  @Test
  void shouldAutoCreateClientAndLogTraceWhenNewDiscovered() {
    ProcessMessageCommand command = new ProcessMessageCommand(
        "TX-BOOK-101", PlatformType.TELEGRAM, "55512345", "Natalia", "Хочу записаться"
    );

    bookingService.processMessage(command);

    var clientRecord = dslCtx.selectFrom(CLIENTS)
        .where(CLIENTS.PLATFORM_TYPE.eq(PlatformType.TELEGRAM.name()))
        .and(CLIENTS.PLATFORM_ID.eq("55512345"))
        .fetchOptional();

    assertTrue(clientRecord.isPresent());
    assertEquals("Natalia", clientRecord.get().getDisplayName());

    var traceRecord = dslCtx.selectFrom(MESSAGE_TRACES).where(MESSAGE_TRACES.TRACE_ID.eq("TX-BOOK-101")).fetchOptional();
    assertTrue(traceRecord.isPresent());
    assertEquals("INBOUND", traceRecord.get().getDirection());
    assertEquals(clientRecord.get().getId(), traceRecord.get().getClientId());
  }

  /**
   * <h3>Тест 2: Повторные обращения от существующего клиента (Идемпотентность профиля)</h3>
   */
  @Test
  void shouldReuseExistingProfileOnSubsequentRequests() {
    ProcessMessageCommand firstPass = new ProcessMessageCommand(
        "TX-BOOK-201", PlatformType.TELEGRAM, "99999", "Natalia", "Привет"
    );
    ProcessMessageCommand secondPass = new ProcessMessageCommand(
        "TX-BOOK-202", PlatformType.TELEGRAM, "99999", "Natalia", "Второе сообщение"
    );

    bookingService.processMessage(firstPass);
    bookingService.processMessage(secondPass);

    assertEquals(1, dslCtx.fetchCount(CLIENTS), "Повторные запросы не должны дублировать клиента.");
    assertEquals(2, dslCtx.fetchCount(MESSAGE_TRACES), "Каждое сообщение должно логироваться отдельно.");
  }

  /**
   * <h3>Тест 3: Строгая изоляция профилей разных пользователей</h3>
   */
  @Test
  void shouldMaintainStrictIsolationBetweenDistinctAccounts() {
    ProcessMessageCommand clientA = new ProcessMessageCommand(
        "TX-BOOK-301", PlatformType.TELEGRAM, "11111", "Natalia", "Запрос А"
    );
    ProcessMessageCommand clientB = new ProcessMessageCommand(
        "TX-BOOK-302", PlatformType.TELEGRAM, "22222", "Anna", "Запрос Б"
    );

    bookingService.processMessage(clientA);
    bookingService.processMessage(clientB);

    assertEquals(2, dslCtx.fetchCount(CLIENTS), "Должно быть создано 2 раздельных аккаунта.");
  }

  /**
   * <h3>Тест 4: Граничный случай — создание клиента без имени (Скрытый профиль / Meta API)</h3>
   * <p><b>Бизнес-контекст:</b> Если имя пользователя null или пустое, система не должна падать.
   * Она обязана подставить безопасное дефолтное значение "Guest" на уровне бизнес-логики.</p>
   */
  @Test
  void shouldFallbackToDefaultNameWhenFirstNameIsMissing() {
    ProcessMessageCommand command = new ProcessMessageCommand(
        "TX-BOOK-401", PlatformType.TELEGRAM, "777", null, "Привет от анонима"
    );

    bookingService.processMessage(command);

    var clientRecord = dslCtx.selectFrom(CLIENTS)
        .where(CLIENTS.PLATFORM_TYPE.eq(PlatformType.TELEGRAM.name()))
        .and(CLIENTS.PLATFORM_ID.eq("777"))
        .fetchOptional();

    assertTrue(clientRecord.isPresent());
    assertEquals("Guest", clientRecord.get().getDisplayName(), "При отсутствии имени система должна использовать заглушку 'Guest'.");
  }

  /**
   * <h3>Тест 5: Граничный случай — обработка пустого текстового содержимого</h3>
   * <p><b>Бизнес-контекст:</b> Клиент прислал пустую строку. Факт сессии должен зафиксироваться
   * в архиве логов без падения парсеров СУБД.</p>
   */
  @Test
  void shouldLogTraceCleanlyEvenWhenMessageTextIsEmpty() {
    ProcessMessageCommand command = new ProcessMessageCommand(
        "TX-BOOK-501", PlatformType.TELEGRAM, "11111", "Natalia", ""
    );

    bookingService.processMessage(command);

    var traceRecord = dslCtx.selectFrom(MESSAGE_TRACES).where(MESSAGE_TRACES.TRACE_ID.eq("TX-BOOK-501")).fetchOptional();
    assertTrue(traceRecord.isPresent());
    assertEquals("", traceRecord.get().getMessageText());
  }

  /**
   * <h3>Тест 6: Получение списка активных стилистов салона</h3>
   * <p><b>Бизнес-контекст:</b> ИИ запрашивает сетку мастеров для отправки клиенту.
   * Метод должен возвращать только тех специалистов, у которых выставлен флаг активности.</p>
   */
  @Test
  void shouldReturnOnlyActiveStylistsWhenQueried() {
    // Arrange
    LocalDateTime targetDate = LocalDateTime.parse("2026-08-10T12:00:00");

    // Мастер 1: Работает 10 августа
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, 1L)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, 1L)
        .set(MASTER_SHIFTS.SHIFT_START, LocalDateTime.parse("2026-08-10T10:00:00"))
        .set(MASTER_SHIFTS.SHIFT_END, LocalDateTime.parse("2026-08-10T20:00:00"))
        .execute();

    // Мастер 2: Выходной (работает в другой день)
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, 2L)
        .set(MASTERS.FIRST_NAME, "Anna")
        .set(MASTERS.LAST_NAME, "Ivanova")
        .set(MASTERS.SPECIALIZATION, "Stylist")
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, 2L)
        .set(MASTER_SHIFTS.SHIFT_START, LocalDateTime.parse("2026-08-11T10:00:00"))
        .set(MASTER_SHIFTS.SHIFT_END, LocalDateTime.parse("2026-08-11T20:00:00"))
        .execute();

    // Act
    final List<Master> activeStylists = bookingService.getActiveMastersForDate(targetDate);

    // Assert
    assertEquals(1, activeStylists.size(), "Система обязана возвращать только мастеров со сменами на указанную дату.");
    final Master master = activeStylists.getFirst();
    assertEquals("Elena", master.firstName());
    assertEquals("Top Colorist", master.specialization());
  }

  /**
   * <h3>Тест 7: Успешное предварительное ИИ-бронирование слота (С учетом услуги и буфера)</h3>
   *
   * <p><b>Бизнес-контекст:</b> ИИ бронирует свободный временной слот для клиента.
   * Длительность и стоимость процедуры автоматически вычисляются сервером на основе объективных
   * параметров услуги из каталога. Выбранное время полностью укладывается в рабочую смену мастера [Strict Grounding].</p>
   */
  @Test
  void shouldSuccessfullyCreateProvisionBookingWhenSlotIsFree() {
    long clientId = 100L;
    long masterId = 1L;
    long serviceId = 55L;
    LocalDateTime slotTime = LocalDateTime.parse("2026-08-10T14:00:00");

    // 1. Создаем родительский профиль клиента
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientId)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, "123")
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    // 2. Создаем родительский профиль мастера
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, masterId)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .execute();

    // 3. Создаем нормативный эталон услуги в каталоге SERVICES
    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, serviceId)
        .set(SERVICES.NAME, "Женская стрижка")
        .set(SERVICES.DURATION_MINUTES, 60) // Объективная длительность: 60 минут
        .set(SERVICES.PRICE, BigDecimal.valueOf(2500.00)) // Стоимость на дату записи
        .execute();

    // 4. Публикуем официальную рабочую смену мастера на этот день (с 10:00 до 20:00)
    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, masterId)
        .set(MASTER_SHIFTS.SHIFT_START, LocalDateTime.parse("2026-08-10T10:00:00"))
        .set(MASTER_SHIFTS.SHIFT_END, LocalDateTime.parse("2026-08-10T20:00:00"))
        .execute();

    // Act: Запускаем обновленную двухэтапную доменную бронь по serviceId
    Optional<Appointment> appointmentOpt = bookingService.tryAiBooking(clientId, masterId, serviceId, slotTime);

    // Assert: Верифицируем успешность и точность копирования характеристик сделки
    assertTrue(appointmentOpt.isPresent(), "Запись должна быть создана: время попадает в смену мастера и полностью свободно.");

    Appointment appointment = appointmentOpt.get();
    assertEquals(AppointmentStatus.AI_PENDING, appointment.status(), "Первоначальный статус должен быть AI_PENDING.");
    assertEquals(60, appointment.durationMinutes(), "Сервер обязан скопировать длительность 60 минут из SERVICES.");
    // FIX: Сравниваем через compareTo == 0, чтобы игнорировать разницу в масштабе знаков после запятой
    assertEquals(0, java.math.BigDecimal.valueOf(2500.00).compareTo(appointment.price()),
        "Сервер обязан намертво зафиксировать цену 2500.00 в талоне записи.");
    assertEquals(serviceId, appointment.serviceId(), "Внешний ключ услуги должен быть корректно привязан.");
  }

  /**
   * <h3>Тест 8: Конфликт расписания при попытке ИИ-бронирования (С учетом буфера услуг)</h3>
   *
   * <p><b>Бизнес-контекст:</b> ИИ пытается записать клиента на слот, который пересекается
   * с уже существующей записью другого человека к этому же мастеру, либо попадает в зону действия
   * её 5-минутного санитарного буфера очистки места [Strict Grounding].</p>
   */
  @Test
  void shouldReturnEmptyOptionalWhenAiBookingClashesWithExistingAppointment() {
    // Arrange
    long clientA = 100L;
    long clientB = 200L;
    long masterId = 1L;
    long existingServiceId = 55L; // Услуга первого клиента (60 минут)
    long newServiceId = 56L;      // Услуга второго клиента

    LocalDateTime existingSlot = LocalDateTime.parse("2026-08-10T14:00:00"); // 14:00 - 15:00
    LocalDateTime clashingSlot = LocalDateTime.parse("2026-08-10T14:30:00"); // 14:30 - 15:30 (Накладка!)

    // 1. Создаем профили двух клиентов
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientA)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, "123")
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientB)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, "456")
        .set(CLIENTS.DISPLAY_NAME, "Anna")
        .execute();

    // 2. Создаем профиль мастера
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, masterId)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .execute();

    // 3. Создаем две разные записи в справочнике SERVICES
    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, existingServiceId)
        .set(SERVICES.NAME, "Сложное окрашивание")
        .set(SERVICES.DURATION_MINUTES, 60)
        .set(SERVICES.PRICE, java.math.BigDecimal.valueOf(5000.00))
        .execute();

    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, newServiceId)
        .set(SERVICES.NAME, "Укладка волос")
        .set(SERVICES.DURATION_MINUTES, 30)
        .set(SERVICES.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .execute();

    // 4. Публикуем официальную рабочую смену мастера (с 10:00 до 20:00)
    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, masterId)
        .set(MASTER_SHIFTS.SHIFT_START, LocalDateTime.parse("2026-08-10T10:00:00"))
        .set(MASTER_SHIFTS.SHIFT_END, LocalDateTime.parse("2026-08-10T20:00:00"))
        .execute();

    // 5. Создаем жесткую существующую бронь для Клиента А в статусе APPROVED с честным копированием параметров
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.CLIENT_ID, clientA)
        .set(APPOINTMENTS.MASTER_ID, masterId)
        .set(APPOINTMENTS.SERVICE_ID, existingServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, existingSlot)
        .set(APPOINTMENTS.DURATION_MINUTES, 60)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(5000.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.APPROVED) // Строго APPROVED согласно конечным статусам
        .execute();

    // Act: Пытаемся поверх записать Клиента Б на пересекающийся интервал времени
    Optional<Appointment> result = bookingService.tryAiBooking(clientB, masterId, newServiceId, clashingSlot);

    // Assert: Верифицируем, что jOOQ-предикат ЭТАПА Б заблокировал овербукинг
    assertTrue(result.isEmpty(), "Система обязана вернуть Optional.empty(), так как временные интервалы процедур пересекаются.");
  }

  /**
   * <h3>Тест 9: Конфликт бронирования ИИ с официальным перерывом мастера</h3>
   *
   * <p><b>Бизнес-контекст:</b> ИИ-ассистент пытается записать клиента на временной слот,
   * который пересекается с официально зарегистрированным окном отдыха (обедом) мастера.
   * Система обязана защитить личное время сотрудника и отклонить бронирование [Strict Grounding].</p>
   */
  @Test
  void shouldTransitionStatusToConfirmedWhenApprovedByOwner() {
    // Arrange
    long clientId = 100L;
    long masterId = 1L;
    long serviceId = 55L; // Услуга длительностью 60 минут

    LocalDate date = LocalDate.of(2026, 8, 10);
    LocalDateTime breakStart = date.atTime(13, 0); // Обед с 13:00
    LocalDateTime breakEnd = date.atTime(14, 0);   // Обед до 14:00

    // Клиент пытается занять слот 13:30 - 14:30 (Пересекает обед!)
    LocalDateTime targetBookingTime = date.atTime(13, 30);

    // 1. Создаем родительский профиль клиента
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientId)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, "123")
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    // 2. Создаем родительский профиль мастера
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, masterId)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .execute();

    // 3. Создаем услугу в справочнике SERVICES (60 минут)
    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, serviceId)
        .set(SERVICES.NAME, "Стрижка модельная")
        .set(SERVICES.DURATION_MINUTES, 60)
        .set(SERVICES.PRICE, java.math.BigDecimal.valueOf(2000.00))
        .execute();

    // 4. Публикуем родительскую рабочую смену мастера (с 10:00 до 20:00)
    var shiftRecord = dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, masterId)
        .set(MASTER_SHIFTS.SHIFT_START, date.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, date.atTime(20, 0))
        .returning(MASTER_SHIFTS.ID)
        .fetchOne();

    Objects.requireNonNull(shiftRecord, "Test Setup Failure: Master shift creation returned null.");
    Long generatedShiftId = shiftRecord.getId();

    // 5. Внедряем официальный перерыв мастера, привязанный к SHIFT_ID
    dslCtx.insertInto(MASTER_SHIFT_BREAKS)
        .set(MASTER_SHIFT_BREAKS.SHIFT_ID, generatedShiftId)
        .set(MASTER_SHIFT_BREAKS.BREAK_START, breakStart)
        .set(MASTER_SHIFT_BREAKS.BREAK_END, breakEnd)
        .execute();

    // Act: ИИ пытается зарезервировать слот на время обеда
    Optional<Appointment> result = bookingService.tryAiBooking(clientId, masterId, serviceId, targetBookingTime);

    // Assert: Железная верификация изоляции окна отдыха на ЭТАПЕ В
    assertTrue(result.isEmpty(),
        "Система обязана вернуть Optional.empty(), так как сеанс клиента накладывается на обеденный перерыв мастера.");

    // Дополнительный аудит: проверяем, что строка в APPOINTMENTS действительно НЕ появилась
    boolean bookingLeaked = dslCtx.fetchExists(
        dslCtx.selectFrom(APPOINTMENTS).where(APPOINTMENTS.MASTER_ID.eq(masterId))
    );
    assertFalse(bookingLeaked, "Критическая ошибка: запись просочилась в базу данных вопреки перерыву мастера!");
  }
}
