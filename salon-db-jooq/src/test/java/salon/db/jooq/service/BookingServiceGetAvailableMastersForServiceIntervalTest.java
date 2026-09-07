package salon.db.jooq.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import salon.api.model.AppointmentStatus;
import salon.api.model.Master;
import salon.api.service.BookingService;

@InjectTest
@DisplayName("Встроенное тестирование подсистемы аналитического поиска мастеров jOOQ (Avaje Inject Test + H2)")
public class BookingServiceGetAvailableMastersForServiceIntervalTest {

  @Inject
  public BookingService bookingService;

  @Inject
  public DSLContext dslCtx;

  private final LocalDate targetDate = LocalDate.of(2026, 9, 7); // Тестовый понедельник

  @BeforeEach
  void setUpAndSeedData() {
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    // Гарантируем чистую схему перед каждым прогоном
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(MASTER_SERVICES).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.truncate(MASTER_SHIFT_BREAKS).execute();
    dslCtx.truncate(MASTER_SHIFTS).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // Заполнение справочников для тестов
    dslCtx.insertInto(SERVICES, SERVICES.ID, SERVICES.NAME, SERVICES.DURATION_MINUTES,
            SERVICES.PRICE)
        .values(10L, "Стрижка", 45, BigDecimal.valueOf(1500.00))
        .values(20L, "Окрашивание", 120, BigDecimal.valueOf(5000.00))
        .execute();
    dslCtx.insertInto(CLIENTS, CLIENTS.ID, CLIENTS.PLATFORM_TYPE, CLIENTS.PLATFORM_ID,
            CLIENTS.DISPLAY_NAME)
        .values(1L, "TELEGRAM", "123456", "Ира")
        .execute();
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  @DisplayName("Тест 1: Мастер должен исключаться, если запрашиваемый интервал попадает в его обеденный перерыв")
  void shouldExcludeMasterWhenIntervalOverlapsWithShiftBreak() {
    MDC.put("traceId", "TX-TEST1-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

    dslCtx.insertInto(MASTERS, MASTERS.ID, MASTERS.ALIAS, MASTERS.FIRST_NAME, MASTERS.LAST_NAME,
            MASTERS.SPECIALIZATION)
        .values(1L, "elena_colorist", "Елена", "Иванова", "Top Colorist")
        .execute();

    dslCtx.insertInto(MASTER_SERVICES, MASTER_SERVICES.MASTER_ID, MASTER_SERVICES.SERVICE_ID)
        .values(1L, 10L)
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS, MASTER_SHIFTS.ID, MASTER_SHIFTS.MASTER_ID,
            MASTER_SHIFTS.SHIFT_START, MASTER_SHIFTS.SHIFT_END)
        .values(100L, 1L, LocalDateTime.parse("2026-09-07T10:00:00"),
            LocalDateTime.parse("2026-09-07T18:00:00"))
        .execute();

    dslCtx.insertInto(MASTER_SHIFT_BREAKS, MASTER_SHIFT_BREAKS.SHIFT_ID,
            MASTER_SHIFT_BREAKS.BREAK_START, MASTER_SHIFT_BREAKS.BREAK_END)
        .values(100L, LocalDateTime.parse("2026-09-07T13:00:00"),
            LocalDateTime.parse("2026-09-07T14:00:00"))
        .execute();

    List<Master> result = bookingService.getAvailableMastersForServiceInterval(
        "Стрижка", targetDate, LocalTime.of(13, 0), LocalTime.of(13, 45)
    );

    assertTrue(result.isEmpty(), "Мастер не должен быть доступен во время своего перерыва");
  }

  @Test
  @DisplayName("Тест 2: Должен возвращать только свободного квалифицированного мастера среди сотрудников салона")
  void shouldReturnOnlyFreeAndQualifiedMasterAmongStaff() {
    MDC.put("traceId", "TX-TEST2-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

    // Елена занята на Окрашивании с 15:00 до 17:00
    dslCtx.insertInto(MASTERS, MASTERS.ID, MASTERS.ALIAS, MASTERS.FIRST_NAME, MASTERS.LAST_NAME,
            MASTERS.SPECIALIZATION)
        .values(1L, "elena_colorist", "Елена", "Иванова", "Top Colorist")
        .execute();

    dslCtx.insertInto(MASTER_SERVICES, MASTER_SERVICES.MASTER_ID, MASTER_SERVICES.SERVICE_ID)
        .values(1L, 20L)
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS, MASTER_SHIFTS.ID, MASTER_SHIFTS.MASTER_ID,
            MASTER_SHIFTS.SHIFT_START, MASTER_SHIFTS.SHIFT_END)
        .values(100L, 1L, LocalDateTime.parse("2026-09-07T10:00:00"),
            LocalDateTime.parse("2026-09-07T18:00:00"))
        .execute();

    dslCtx.insertInto(APPOINTMENTS, APPOINTMENTS.TICKET_CODE, APPOINTMENTS.CLIENT_ID,
            APPOINTMENTS.MASTER_ID, APPOINTMENTS.SERVICE_ID, APPOINTMENTS.APPOINTMENT_TIME,
            APPOINTMENTS.DURATION_MINUTES, APPOINTMENTS.PRICE, APPOINTMENTS.STATUS)
        .values("TICKET-222", 1L, 1L, 20L, LocalDateTime.parse("2026-09-07T15:00:00"), 120,
            BigDecimal.valueOf(5000.00),
            AppointmentStatus.APPROVED)
        .execute();

    // Кирилл абсолютно свободен в этот день
    dslCtx.insertInto(MASTERS, MASTERS.ID, MASTERS.ALIAS, MASTERS.FIRST_NAME, MASTERS.LAST_NAME,
            MASTERS.SPECIALIZATION)
        .values(2L, "kirill_stylist", "Кирилл", "Петров", "Stylist")
        .execute();

    dslCtx.insertInto(MASTER_SERVICES, MASTER_SERVICES.MASTER_ID, MASTER_SERVICES.SERVICE_ID)
        .values(2L, 20L)
        .execute();

    dslCtx.insertInto(MASTER_SHIFTS, MASTER_SHIFTS.ID, MASTER_SHIFTS.MASTER_ID,
            MASTER_SHIFTS.SHIFT_START, MASTER_SHIFTS.SHIFT_END)
        .values(200L, 2L, LocalDateTime.parse("2026-09-07T10:00:00"),
            LocalDateTime.parse("2026-09-07T18:00:00"))
        .execute();

    List<Master> result = bookingService.getAvailableMastersForServiceInterval(
        "Окрашивание", targetDate, LocalTime.of(14, 0), LocalTime.of(17, 0)
    );

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals("kirill_stylist", result.getFirst().alias());
  }

  @Test
  @DisplayName("Тест 3: Проверка жесткой валидации входных параметров на null и пустые значения фреймворком")
  void shouldThrowExceptionWhenParametersAreInvalid() {
    MDC.put("traceId", "TX-TEST3-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () ->
            bookingService.getAvailableMastersForServiceInterval("", targetDate,
                LocalTime.of(10, 0), LocalTime.of(12, 0))
        ),
        () -> assertThrows(NullPointerException.class, () ->
            bookingService.getAvailableMastersForServiceInterval("Стрижка", null,
                LocalTime.of(10, 0), LocalTime.of(12, 0))
        )
    );
  }

  /*
   * TODO: [FASE 2 - ТЕХНИЧЕСКИЙ ДОЛГ] Перевести данный тестовый класс на Testcontainers PostgreSQL Container.
   *
   * Использование H2 Embedded в режиме совместимости MODE=PostgreSQL накладывает ограничения на комплексные
   * интервальные функции СУБД (такие как интервальная арифметика дат СУБД вида `APPOINTMENT_TIME + INTERVAL '... minutes'`).
   * Для обеспечения 100% идентичности поведения тестов с реальной продакшен-базой данных, необходимо раскомментировать
   * и внедрить конфигурацию Testcontainers, приведенную в проектной спецификации.
   *
   * @Testcontainers
   * public class BookingServiceJooqRepositoryPostgresIT {
   *     @Container
   *     private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.4-alpine");
   *     // ... См. полную архитектурную спецификацию
   * }
   */
}
