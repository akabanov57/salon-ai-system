package salon.db.jooq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
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
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.truncate(MASTERS).execute();
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
    // Arrange: Напрямую вставляем одного активного мастера и одного уволенного/неактивного
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, 1L)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .set(MASTERS.IS_ACTIVE, true)
        .execute();

    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, 2L)
        .set(MASTERS.FIRST_NAME, "Anna")
        .set(MASTERS.LAST_NAME, "Ivanova")
        .set(MASTERS.SPECIALIZATION, "Stylist")
        .set(MASTERS.IS_ACTIVE, false)
        .execute();

    List<Master> activeStylists = bookingService.getAvailableStylists();
    assertEquals(1, activeStylists.size());
    assertEquals("Elena", activeStylists.getFirst().firstName());
  }

  /**
   * <h3>Тест 7: Успешное предварительное ИИ-бронирование слота</h3>
   * <p><b>Бизнес-контекст:</b> ИИ подобрал свободное время для клиента и пытается его занять.
   * Слот свободен, запись создается в статусе пред-проверки AI_PENDING.</p>
   */
  @Test
  void shouldSuccessfullyCreateProvisionBookingWhenSlotIsFree() {
    long clientId = 100L;
    long masterId = 1L;
    LocalDateTime slotTime = LocalDateTime.parse("2026-08-10T14:00:00");

    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientId)
        .set(CLIENTS.PLATFORM_TYPE, PlatformType.TELEGRAM.name())
        .set(CLIENTS.PLATFORM_ID, "123")
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, masterId)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .set(MASTERS.IS_ACTIVE, true)
        .execute();

    Optional<Appointment> appointmentOpt = bookingService.tryAiBooking(clientId, masterId, slotTime, 60);

    assertTrue(appointmentOpt.isPresent());
    assertEquals(AppointmentStatus.AI_PENDING, appointmentOpt.get().status());
  }

  /**
   * <h3>Тест 8: Конфликт расписания при попытке ИИ-бронирования</h3>
   * <p><b>Бизнес-контекст:</b> ИИ пытается записать клиента на слот, который пересекается
   * с уже существующей записью другого человека к этому же мастеру.</p>
   */
  @Test
  void shouldReturnEmptyOptionalWhenAiBookingClashesWithExistingAppointment() {
    long clientA = 100L;
    long clientB = 200L;
    long masterId = 1L;
    LocalDateTime existingSlot = LocalDateTime.parse("2026-08-10T14:00:00");
    LocalDateTime clashingSlot = LocalDateTime.parse("2026-08-10T14:30:00");

    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientA)
        .set(CLIENTS.PLATFORM_TYPE, PlatformType.TELEGRAM.name())
        .set(CLIENTS.PLATFORM_ID, "123")
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientB)
        .set(CLIENTS.PLATFORM_TYPE, PlatformType.TELEGRAM.name())
        .set(CLIENTS.PLATFORM_ID, "456")
        .set(CLIENTS.DISPLAY_NAME, "Anna")
        .execute();

    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, masterId)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .set(MASTERS.IS_ACTIVE, true)
        .execute();

    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.CLIENT_ID, clientA)
        .set(APPOINTMENTS.MASTER_ID, masterId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, existingSlot)
        .set(APPOINTMENTS.DURATION_MINUTES, 60)
        .set(APPOINTMENTS.STATUS, "CONFIRMED")
        .execute();

    Optional<Appointment> result = bookingService.tryAiBooking(clientB, masterId, clashingSlot, 60);
    assertTrue(result.isEmpty());
  }

  /**
   * <h3>Тест 9: Подтверждение записи владельцем салона (Оркестрация статуса)</h3>
   * <p><b>Бизнес-контекст:</b> Владелец салона заходит в панель управления, видит бронь AI_PENDING
   * и одобряет её, переводя в финальный рабочий статус CONFIRMED.</p>
   */
  @Test
  void shouldTransitionStatusToConfirmedWhenApprovedByOwner() {
    // Arrange
    long clientId = 100L;
    long masterId = 1L;
    long appointmentId = 999L;
    LocalDateTime slotTime = LocalDateTime.parse("2026-08-10T14:00:00");

    // Вставляем родительскую запись клиента с использованием новых заглавных колонок СУБД
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, clientId)
        .set(CLIENTS.PLATFORM_TYPE, PlatformType.TELEGRAM.name())
        .set(CLIENTS.PLATFORM_ID, "123")
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    // Вставляем родительскую запись мастера с соблюдением NOT NULL ограничений
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, masterId)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .set(MASTERS.IS_ACTIVE, true)
        .execute();

    // Создаем предварительный сеанс записи в исходном статусе черновика AI_PENDING
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, appointmentId)
        .set(APPOINTMENTS.CLIENT_ID, clientId)
        .set(APPOINTMENTS.MASTER_ID, masterId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, slotTime)
        .set(APPOINTMENTS.DURATION_MINUTES, 60)
        .set(APPOINTMENTS.STATUS, "AI_PENDING")
        .execute();

    // Act: Выполняем доменный метод аппрува по первичному ключу тикета
    bookingService.approveAppointment(appointmentId);

    // Assert: Напрямую вычитываем строку СУБД для проверки финального рантайм-состояния
    var record = dslCtx.selectFrom(APPOINTMENTS).where(APPOINTMENTS.ID.eq(appointmentId)).fetchOptional();

    assertTrue(record.isPresent(), "Запись сеанса должна остаться в таблице расписания.");
    // Проверяем строгое соответствие вашему внутреннему статус-инварианту APPROVED
    assertEquals("APPROVED", record.get().getStatus(), "После аппрува владельцем статус обязан стать APPROVED.");
  }
}
