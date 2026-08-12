package salon.db.jooq.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFT_BREAKS;
import static salon.db.jooq.generated.Tables.SALON_CALENDAR_EXCEPTIONS;
import static salon.db.jooq.generated.Tables.SALON_WEEKLY_SCHEDULE;
import static salon.db.jooq.generated.Tables.SERVICES;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import salon.api.exception.IntegrityViolationException;
import salon.api.model.AppointmentStatus;
import salon.api.service.ShiftSchedulerService;
import salon.api.service.ShiftSchedulerService.BreakDto;
import salon.api.service.ShiftSchedulerService.PublishShiftCommand;

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

  private final Long mockMasterId = 77L;
  private final Long mockClientId = 88L;
  private final LocalDate testDate = LocalDate.of(2026, 8, 10); // Monday

  @BeforeEach
  void setUpCleanIsolatedH2State() {
    // Rigid foreign key isolation and complete ledger purging
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(MASTER_SHIFT_BREAKS).execute();
    dslCtx.truncate(MASTER_SHIFTS).execute();
    dslCtx.truncate(SALON_CALENDAR_EXCEPTIONS).execute();
    dslCtx.truncate(SALON_WEEKLY_SCHEDULE).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // Establish global infrastructure boundary: Salon operating slot 09:00 - 21:00 on Mondays
    dslCtx.insertInto(SALON_WEEKLY_SCHEDULE)
        .set(SALON_WEEKLY_SCHEDULE.DAY_OF_WEEK, DayOfWeek.MONDAY.name())
        .set(SALON_WEEKLY_SCHEDULE.IS_CLOSED, false)
        .set(SALON_WEEKLY_SCHEDULE.OPEN_TIME, LocalTime.of(9, 0))
        .set(SALON_WEEKLY_SCHEDULE.CLOSE_TIME, LocalTime.of(21, 0))
        .execute();

    // Setup master target profile
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, mockMasterId)
        .set(MASTERS.FIRST_NAME, "Irina")
        .set(MASTERS.LAST_NAME, "Stylist")
        .set(MASTERS.SPECIALIZATION, "Haircuts")
        .execute();

    // Setup client base context row
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, mockClientId)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, "555444")
        .set(CLIENTS.DISPLAY_NAME, "Michael")
        .execute();
  }

  /**
   * <h3>Test 1: Variant 1 Implementation Pass (Clean operational shift publication)</h3>
   *
   * <p><b>Business Context:</b> The salon manager registers a standard "blank" operational shift
   * for a stylist spanning from 10:00 to 18:00. No break arrays are allocated at this milestone [Strict Grounding].</p>
   */
  @Test
  void shouldSuccessfullyPublishCleanShiftWhenWithinSalonBounds() {
    // Arrange
    final LocalDateTime start = testDate.atTime(10, 0);
    final LocalDateTime end = testDate.atTime(18, 0);
    final PublishShiftCommand command = new PublishShiftCommand(mockMasterId, start, end, List.of());

    // Act
    shiftSchedulerService.publishShifts(List.of(command));

    // Assert
    final boolean shiftPersisted = dslCtx.fetchExists(
        dslCtx.selectFrom(MASTER_SHIFTS)
            .where(MASTER_SHIFTS.MASTER_ID.eq(mockMasterId))
            .and(MASTER_SHIFTS.SHIFT_START.eq(start))
            .and(MASTER_SHIFTS.SHIFT_END.eq(end))
    );
    assertTrue(shiftPersisted, "Variant 1 error: The standalone master shift row was not persisted safely.");
  }

  /**
   * <h3>Test 2: Variant 2 Implementation Pass (Atomic publication with planned batch breaks)</h3>
   *
   * <p><b>Business Context:</b> The owner registers a full working shift and immediately schedules
   * a lunch window (13:00 - 14:00) directly into the creation batch array [Strict Grounding].</p>
   */
  @Test
  void shouldAtomicallyPublishShiftAndPlannedBreaksViaBatchApi() {
    // Arrange
    final LocalDateTime start = testDate.atTime(9, 0);
    final LocalDateTime end = testDate.atTime(21, 0);
    final LocalDateTime breakStart = testDate.atTime(13, 0);
    final LocalDateTime breakEnd = testDate.atTime(14, 0);

    final BreakDto plannedBreak = new BreakDto(breakStart, breakEnd);
    final PublishShiftCommand command = new PublishShiftCommand(mockMasterId, start, end, List.of(plannedBreak));

    // Act
    shiftSchedulerService.publishShifts(List.of(command));

    // Assert
    final var shiftRow = dslCtx.selectFrom(MASTER_SHIFTS)
        .where(MASTER_SHIFTS.MASTER_ID.eq(mockMasterId))
        .fetchOne();
    assertNotNull(shiftRow, "The master shift record should be present.");

    final Long generatedShiftId = shiftRow.getId();

    // Verify jOOQ Batch API successfully pushed downstream breaks to disk
    final boolean breakPersisted = dslCtx.fetchExists(
        dslCtx.selectFrom(MASTER_SHIFT_BREAKS)
            .where(MASTER_SHIFT_BREAKS.SHIFT_ID.eq(generatedShiftId))
            .and(MASTER_SHIFT_BREAKS.BREAK_START.eq(breakStart))
            .and(MASTER_SHIFT_BREAKS.BREAK_END.eq(breakEnd))
    );
    assertTrue(breakPersisted, "Variant 2 error: The jOOQ Batch API failed to push associated child break entries.");
  }

  /**
   * <h3>Test 3: Variant 3 Operational Clash (Rejection on spontaneous break injection)</h3>
   *
   * <p><b>Business Context:</b> A live active client is booked from 14:00 to 15:00. The salon owner
   * attempts to injection-insert a rest break at 14:30. The client must have absolute priority [Strict Grounding].</p>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenInjectingBreakOntoActiveClientBooking() {
    // Arrange: 1. Setup a parent shift anchor safely
    final var shiftRecord = dslCtx.insertInto(MASTER_SHIFTS)
        .set(MASTER_SHIFTS.MASTER_ID, mockMasterId)
        .set(MASTER_SHIFTS.SHIFT_START, testDate.atTime(10, 0))
        .set(MASTER_SHIFTS.SHIFT_END, testDate.atTime(19, 0))
        .returning(MASTER_SHIFTS.ID)
        .fetchOne();

    Objects.requireNonNull(shiftRecord, "Test setup error: Failed to initialize parent master shift row.");
    final Long parentShiftId = shiftRecord.getId();

    // FIX: 1.1. Создаем обязательный эталон услуги в каталоге SERVICES для соблюдения FK целостности
    final long testServiceId = 999L;
    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, testServiceId)
        .set(SERVICES.NAME, "Техническая проверка")
        .set(SERVICES.DURATION_MINUTES, 60)
        .set(SERVICES.PRICE, BigDecimal.valueOf(1000.00))
        .execute();

    // 2. Setup a pre-existing live client booking (14:00 - 15:00) in APPROVED status
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, testServiceId) // FIX: Заполняем обязательный внешний ключ услуги
        .set(APPOINTMENTS.APPOINTMENT_TIME, testDate.atTime(14, 0))
        .set(APPOINTMENTS.DURATION_MINUTES, 60)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1000.00)) // FIX: Заполняем обязательную цену визита
        .set(APPOINTMENTS.STATUS, AppointmentStatus.APPROVED) // Используем доменный enum
        .execute();

    // Attempt to inject an overlapping break at 14:30 (Duration: 30 minutes, until 15:00)
    final LocalDateTime conflictingBreakStart = testDate.atTime(14, 30);
    final LocalDateTime conflictingBreakEnd = testDate.atTime(15, 0);

    // Act & Assert
    final IntegrityViolationException exception = assertThrows(IntegrityViolationException.class, () ->
        shiftSchedulerService.injectBreakIntoShift(mockMasterId, conflictingBreakStart, conflictingBreakEnd)
    );

    assertTrue(exception.getMessage().contains("на выбранное время уже есть предварительная или подтвержденная запись"),
        "The domain exception must clearly outline the priority conflict message.");

    // Verify isolation state: Database row must remain untainted from the break sequence
    final boolean breakLeaked = dslCtx.fetchExists(
        dslCtx.selectFrom(MASTER_SHIFT_BREAKS).where(MASTER_SHIFT_BREAKS.SHIFT_ID.eq(parentShiftId))
    );
    assertFalse(breakLeaked, "Variant 3 error: The conflicting break row leaked onto the disk instead of executing a rollback pass.");
  }
}
