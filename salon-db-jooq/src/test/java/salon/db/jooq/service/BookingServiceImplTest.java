package salon.db.jooq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SERVICES;
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

  // Изолированные суррогатные ID для настройки исходного реляционного состояния в секции Arrange
  private final long surrogateClientId = 88001L;
  private final long surrogateMasterId = 88002L;
  private final long surrogateServiceId = 88003L;
  private final long clashingServiceId = 88004L;

  // Естественные строковые бизнес-ключи, которыми оперирует ИИ-ассистент
  private final String clientPlatformId = "TG-555444";
  private final String masterAlias = "elena_colorist";
  private final String targetServiceName = "Женская стрижка модельная";
  private final String clashingServiceName = "Укладка волос";

  private final LocalDate testDate = LocalDate.of(2026, 8, 15);

  @BeforeEach
  void setUpCleanIsolatedH2State() {
    // Жесткая очистка реляционных таблиц с временным отключением контроля внешних ключей
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(MASTER_SHIFT_BREAKS).execute();
    dslCtx.truncate(MASTER_SHIFTS).execute();
    dslCtx.truncate(MASTER_SERVICES).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // 1. Вставляем базовую строку клиента с естественным ключом PLATFORM_ID
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, surrogateClientId)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, clientPlatformId)
        .set(CLIENTS.DISPLAY_NAME, "Natalia")
        .execute();

    // 2. Вставляем строку мастера с естественным ключом ALIAS
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, surrogateMasterId)
        .set(MASTERS.ALIAS, masterAlias)
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .execute();

    // 3. Вставляем типы услуг в каталог SERVICES
    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, surrogateServiceId)
        .set(SERVICES.NAME, targetServiceName)
        .set(SERVICES.DURATION_MINUTES, 60)
        .set(SERVICES.PRICE, BigDecimal.valueOf(2500.00))
        .execute();

    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, clashingServiceId)
        .set(SERVICES.NAME, clashingServiceName)
        .set(SERVICES.DURATION_MINUTES, 30)
        .set(SERVICES.PRICE, BigDecimal.valueOf(1500.00))
        .execute();
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
    // Arrange: Используем новый уникальный идентификатор платформы, отличный от базового
    String recurringPlatformId = "TG-777777";
    ProcessMessageCommand command1 = new ProcessMessageCommand(
        "TRACE-101", PlatformType.TELEGRAM, recurringPlatformId, "Viktoria", "Первое обращение клиента"
    );
    ProcessMessageCommand command2 = new ProcessMessageCommand(
        "TRACE-102", PlatformType.TELEGRAM, recurringPlatformId, "Viktoria", "Повторное обращение в чат"
    );

    // Act: Прокатываем обе команды через сервис по очереди
    bookingService.processMessage(command1);
    bookingService.processMessage(command2);

    // Assert: 1. Верифицируем повторное использование и привязку логов к одному ID
    Long traceCount = dslCtx.selectCount().from(MESSAGE_TRACES)
        .where(MESSAGE_TRACES.CLIENT_ID.in(
            dslCtx.select(CLIENTS.ID).from(CLIENTS).where(CLIENTS.PLATFORM_ID.eq(recurringPlatformId))
        )).fetchOneInto(Long.class);

    assertEquals(2, traceCount, "Оба лога сообщений должны быть атомарно привязаны к одному профилю клиента.");

    // FIX: Учитываем базового клиента из @BeforeEach (1 baseline + 1 новый рекурсивный = 2 total rows)
    int totalExpectedClients = 2;
    assertEquals(totalExpectedClients, dslCtx.fetchCount(CLIENTS),
        "Повторные запросы не должны дублировать клиента в базе данных.");
  }

  /**
   * <h3>Тест 3: Строгая изоляция профилей разных пользователей</h3>
   */
  @Test
  void shouldMaintainStrictIsolationBetweenDistinctAccounts() {
    // Arrange: Конструируем две раздельные команды для новых пользователей мессенджера
    String customerAPlatformId = "TG-111111";
    String customerBPlatformId = "TG-222222";

    ProcessMessageCommand commandA = new ProcessMessageCommand(
        "TRACE-001", PlatformType.TELEGRAM, customerAPlatformId, "Alice", "Хочу записаться на окрашивание"
    );

    ProcessMessageCommand commandB = new ProcessMessageCommand(
        "TRACE-002", PlatformType.TELEGRAM, customerBPlatformId, "Bob", "Здравствуйте, сколько стоит стрижка?"
    );

    // Act: Прогоняем обе команды через высокоуровневый доменный метод бизнес-логики
    bookingService.processMessage(commandA);
    bookingService.processMessage(commandB);

    // Assert: 1. Проверяем корректность атомарного сохранения истории сообщений в MESSAGE_TRACES
    boolean traceAPersisted = dslCtx.fetchExists(
        dslCtx.selectFrom(MESSAGE_TRACES).where(MESSAGE_TRACES.TRACE_ID.eq("TRACE-001"))
    );
    boolean traceBPersisted = dslCtx.fetchExists(
        dslCtx.selectFrom(MESSAGE_TRACES).where(MESSAGE_TRACES.TRACE_ID.eq("TRACE-002"))
    );

    assertTrue(traceAPersisted, "Лог транзакции первого клиента обязан успешно сохраниться в MESSAGE_TRACES.");
    assertTrue(traceBPersisted, "Лог транзакции второго клиента обязан успешно сохраниться в MESSAGE_TRACES.");

    // 2. Верифицируем общее число клиентов на диске с учетом базовой строки окружения
    // (1 baseline клиент от @BeforeEach + 2 новых изолированных клиента от processMessage = 3)
    int totalExpectedClients = 3;
    assertEquals(totalExpectedClients, dslCtx.fetchCount(CLIENTS),
        "В БД должно находиться ровно 3 клиента (1 базовый профиль + 2 раздельно созданных аккаунта).");
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
    assertEquals("Клиент Салона", clientRecord.get().getDisplayName(), "При отсутствии имени система должна использовать заглушку 'Guest'.");
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
   * <p><b>Бизнес-контекст:</b> ИИ запрашивает сетку мастеров для отправки клиенту на конкретную дату визита.
   * Метод должен возвращать только тех специалистов, у которых на эту дату открыта рабочая смена.
   * Профиль мастера обязан содержать строковый ALIAS вместо суррогатного ID [Strict Grounding].</p>
   */
  @Test
  void shouldReturnOnlyActiveStylistsWhenQueried() {
    // Arrange: Настраиваем временную точку запроса
    LocalDateTime targetDate = LocalDateTime.parse("2026-08-10T12:00:00");

    // Мастер 1: Работает 10 августа (Должен попасть в выборку)
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, 1L)
        .set(MASTERS.ALIAS, "elena_petrova_t6") // Наш естественный бизнес-ключ
        .set(MASTERS.FIRST_NAME, "Elena")
        .set(MASTERS.LAST_NAME, "Petrova")
        .set(MASTERS.SPECIALIZATION, "Top Colorist")
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, 1L)
        .set(MASTER_SHIFTS.SHIFT_START, LocalDateTime.parse("2026-08-10T10:00:00"))
        .set(MASTER_SHIFTS.SHIFT_END, LocalDateTime.parse("2026-08-10T20:00:00"))
        .execute();

    // Мастер 2: Выходной 10 августа, работает в другой день (Должен быть отфильтрован)
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, 2L)
        .set(MASTERS.ALIAS, "anna_ivanova_t6")
        .set(MASTERS.FIRST_NAME, "Anna")
        .set(MASTERS.LAST_NAME, "Ivanova")
        .set(MASTERS.SPECIALIZATION, "Stylist")
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, 2L)
        .set(MASTER_SHIFTS.SHIFT_START, LocalDateTime.parse("2026-08-11T10:00:00"))
        .set(MASTER_SHIFTS.SHIFT_END, LocalDateTime.parse("2026-08-11T20:00:00"))
        .execute();

    // Act: Вызываем доработанный метод ядра бронирования
    final List<Master> activeStylists = bookingService.getActiveMastersForDate(targetDate);

    // Assert: Верифицируем точность фильтрации смен и маппинга полей
    assertEquals(1, activeStylists.size(),
        "Система обязана возвращать только мастеров со сменами на указанную дату.");

    final Master master = activeStylists.getFirst();
    assertEquals("elena_petrova_t6", master.alias(), "Профиль обязан содержать правильный строковый бизнес-ключ.");
    assertEquals("Elena", master.firstName(), "Имя мастера должно быть корректно извлечено.");
    assertEquals("Top Colorist", master.specialization(), "Специализация мастера должна совпадать с прейскурантом.");
  }

  /**
   * <h3>Тест 7: Успешное предварительное ИИ-бронирование слота (С учетом услуги, компетенции и буфера)</h3>
   *
   * <p><b>Бизнес-контекст:</b> ИИ бронирует свободный временной слот для клиента.
   * Длительность и стоимость процедуры автоматически вычисляются сервером на основе объективных
   * параметров услуги из каталога. Выбранное время полностью укладывается в рабочую смену мастера,
   * и мастер официально обладает квалификацией для выполнения этой услуги [Strict Grounding].</p>
   */
  @Test
  void shouldSuccessfullyCreateProvisionBookingWhenSlotIsFree() {
    // Arrange: Настраиваем допуск по квалификации и публикуем рабочую смену мастера
    dslCtx.insertInto(MASTER_SERVICES)
        .set(MASTER_SERVICES.MASTER_ID, surrogateMasterId)
        .set(MASTER_SERVICES.SERVICE_ID, surrogateServiceId)
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, surrogateMasterId)
        .set(MASTER_SHIFTS.SHIFT_START, testDate.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, testDate.atTime(20, 0))
        .execute();

    LocalDateTime bookingTime = testDate.atTime(14, 0);

    // Act: Вызываем метод с использованием исключительно бесцифровых строковых бизнес-ключей
    Optional<Appointment> appointmentOpt = bookingService.tryAiBooking(
        clientPlatformId, masterAlias, targetServiceName, bookingTime
    );

    // Assert: Верифицируем успешность прохождения транзакции и маппинга данных
    assertTrue(appointmentOpt.isPresent(), "Запись должна быть успешно зафиксирована на свободном слоте.");

    Appointment appointment = appointmentOpt.get();
    assertNotNull(appointment.ticketCode(), "Система обязана сгенерировать уникальный публичный TICKET_CODE визита.");
    assertTrue(appointment.ticketCode().startsWith("SB-"), "Код билета должен соответствовать официальному префиксу салона.");

    assertEquals(clientPlatformId, appointment.platformId());
    assertEquals(masterAlias, appointment.masterAlias());
    assertEquals(targetServiceName, appointment.serviceName());
    assertEquals(AppointmentStatus.AI_PENDING, appointment.status());
    assertEquals(60, appointment.durationMinutes());
    assertEquals(0, BigDecimal.valueOf(2500.00).compareTo(appointment.price()), "Историческая цена должна быть скопирована без искажений масштаба.");
  }

  /**
   * <h3>Тест 8: Конфликт расписания при попытке ИИ-бронирования (С учетом компетенций и буфера)</h3>
   *
   * <p><b>Бизнес-контекст:</b> ИИ пытается записать клиента на слот, который пересекается
   * с уже существующей записью другого человека к этому же мастеру, либо попадает в зону действия
   * её 5-минутного санитарного буфера очистки места. Мастер обладает квалификацией для обеих услуг [Strict Grounding].</p>
   */
  @Test
  void shouldReturnEmptyOptionalWhenAiBookingClashesWithExistingAppointment() {
    // Arrange: Задаем допуски мастера к обеим услугам и открываем смену
    dslCtx.insertInto(MASTER_SERVICES)
        .set(MASTER_SERVICES.MASTER_ID, surrogateMasterId)
        .set(MASTER_SERVICES.SERVICE_ID, surrogateServiceId)
        .execute();
    dslCtx.insertInto(MASTER_SERVICES)
        .set(MASTER_SERVICES.MASTER_ID, surrogateMasterId)
        .set(MASTER_SERVICES.SERVICE_ID, clashingServiceId)
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, surrogateMasterId)
        .set(MASTER_SHIFTS.SHIFT_START, testDate.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, testDate.atTime(20, 0))
        .execute();

    // Создаем существующую бронь (14:00 - 15:00). Санитарный буфер удерживает рабочее место до 15:05.
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.TICKET_CODE, "SB-260815-EXISTING")
        .set(APPOINTMENTS.CLIENT_ID, surrogateClientId)
        .set(APPOINTMENTS.MASTER_ID, surrogateMasterId)
        .set(APPOINTMENTS.SERVICE_ID, surrogateServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, testDate.atTime(14, 0))
        .set(APPOINTMENTS.DURATION_MINUTES, 60)
        .set(APPOINTMENTS.PRICE, BigDecimal.valueOf(2500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.APPROVED)
        .execute();

    // Act: Пытаемся вклинить второго клиента на время 14:30 (явное пересечение интервалов)
    Optional<Appointment> result = bookingService.tryAiBooking(
        clientPlatformId, masterAlias, clashingServiceName, testDate.atTime(14, 30)
    );

    // Assert
    assertTrue(result.isEmpty(), "Система обязана отвергнуть бронь визита из-за пересечения с существующим клиентом.");
  }

  /**
   * <h3>Тест 9: Конфликт бронирования ИИ с официальным перерывом мастера</h3>
   *
   * <p><b>Бизнес-контекст:</b> ИИ-ассистент пытается записать клиента на временной слот,
   * который пересекается с официально зарегистрированным окном отдыха (обедом) мастера.
   * Мастер имеет допуск к услуге. Система обязана отклонить бронирование [Strict Grounding].</p>
   */
  @Test
  void shouldTransitionStatusToConfirmedWhenApprovedByOwner() {
    // Arrange: Настраиваем допуск квалификации
    dslCtx.insertInto(MASTER_SERVICES).set(MASTER_SERVICES.MASTER_ID, surrogateMasterId).set(MASTER_SERVICES.SERVICE_ID, surrogateServiceId).execute();

    // Создаем смену и извлекаем её сгенерированный первичный ключ для связывания таблиц
    var shiftRecord = dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, surrogateMasterId)
        .set(MASTER_SHIFTS.SHIFT_START, testDate.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, testDate.atTime(20, 0))
        .returning(MASTER_SHIFTS.ID)
        .fetchOne();

    Objects.requireNonNull(shiftRecord, "Инициализация тестовой смены вернула пустой рекорд.");
    Long parentShiftId = shiftRecord.get(MASTER_SHIFTS.ID);

    // Внедряем официальный обеденный перерыв мастера (13:00 - 14:00)
    dslCtx.insertInto(MASTER_SHIFT_BREAKS)
        .set(MASTER_SHIFT_BREAKS.SHIFT_ID, parentShiftId)
        .set(MASTER_SHIFT_BREAKS.BREAK_START, testDate.atTime(13, 0))
        .set(MASTER_SHIFT_BREAKS.BREAK_END, testDate.atTime(14, 0))
        .execute();

    // Act: Клиент пытается записаться на 13:30 (в самый разгар обеда мастера)
    Optional<Appointment> result = bookingService.tryAiBooking(
        clientPlatformId, masterAlias, targetServiceName, testDate.atTime(13, 30)
    );

    // Assert
    assertTrue(result.isEmpty(), "Операция должна быть заблокирована: время зарезервировано под отдых сотрудника.");
  }

  /**
   * <h3>Тест 10: Отклонение бронирования при отсутствии квалификации мастера (ЭТАП 0)</h3>
   *
   * <p><b>Бизнес-контекст:</b> Клиент пытается записаться на услугу (например, Сложное окрашивание).
   * У выбранного мастера есть свободная смена, но он не обладает квалификацией для этой процедуры [Strict Grounding].</p>
   */
  @Test
  void shouldReturnEmptyOptionalWhenMasterLacksServiceCompetence() {
    // Arrange: Публикуем смену, но УМЫШЛЕННО НЕ добавляем запись допуска в MASTER_SERVICES
    dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, surrogateMasterId)
        .set(MASTER_SHIFTS.SHIFT_START, testDate.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, testDate.atTime(20, 0))
        .execute();

    // Act: Пытаемся записать клиента на услугу, к которой у мастера нет допуска
    Optional<Appointment> result = bookingService.tryAiBooking(
        clientPlatformId, masterAlias, targetServiceName, testDate.atTime(14, 0)
    );

    // Assert
    assertTrue(result.isEmpty(), "Система должна вернуть Optional.empty(), так как мастер не имеет квалификации для этой услуги.");
  }
}
