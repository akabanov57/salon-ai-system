package salon.db.jooq.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.SERVICES;
import static salon.db.jooq.generated.tables.Appointments.APPOINTMENTS;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import salon.api.exception.IntegrityViolationException;
import salon.api.model.AppointmentStatus;
import salon.api.service.AppointmentWorkflowService;

/**
 * <h3>State Machine Transition Rule Testing Matrix</h3>
 *
 * <p>Leverages {@code @InjectTest} to launch an ultra-fast compiled Avaje test container context,
 * spinning up a clean relational sandbox instance backed by H2.</p>
 */
@InjectTest
public class AppointmentWorkflowServiceIntegrationTest {

  @Inject
  public AppointmentWorkflowService workflowService;

  @Inject
  public DSLContext dslCtx;

  private final Long mockClientId = 1001L;
  private final Long mockMasterId = 2002L;
  private final Long mockServiceId = 3003L;

  @BeforeEach
  void setUpCleanIsolatedH2State() {
    // Rigid foreign key isolation and complete state clearing pass
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // Establish fundamental context data rows
    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, mockClientId)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, "999")
        .set(CLIENTS.DISPLAY_NAME, "Test Client")
        .execute();

    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, mockMasterId)
        .set(MASTERS.FIRST_NAME, "Anna")
        .set(MASTERS.LAST_NAME, "Master")
        .set(MASTERS.SPECIALIZATION, "Manicure")
        .execute();

    dslCtx.insertInto(SERVICES)
        .set(SERVICES.ID, mockServiceId)
        .set(SERVICES.NAME, "Basic Polish")
        .set(SERVICES.DURATION_MINUTES, 45)
        .set(SERVICES.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .execute();
  }

  /**
   * <h3>Test 1 (Happy Path Transition Sequence): Complete legitimate state execution chain</h3>
   *
   * <p><b>Business Context:</b> A manager accesses the Vaadin dashboard, views an AI-generated draft row,
   * clicks 'Approve', and later flags the session as successfully served at the day's end.</p>
   */
  @Test
  void shouldSuccessfullyProgressStatusWhenFollowingLegitimateTransitionChain() {
    // Arrange: Persist an initial AI draft ticket record
    Long ticketId = 5555L;
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, ticketId)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().plusDays(1))
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING)
        .execute();

    // Act & Assert Step A: Execute legitimate approval shift (AI_PENDING -> APPROVED)
    assertDoesNotThrow(() -> workflowService.approveAppointment(ticketId),
        "Moving a ticket from AI_PENDING to APPROVED is completely valid.");

    AppointmentStatus statusAfterApproval = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.ID.eq(ticketId)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.APPROVED, statusAfterApproval);

    // Act & Assert Step B: Execute terminal completion shift (APPROVED -> COMPLETED)
    assertDoesNotThrow(() -> workflowService.completeAppointment(ticketId),
        "Moving an APPROVED appointment to COMPLETED is a fully legal state mutation.");

    AppointmentStatus statusAfterCompletion = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.ID.eq(ticketId)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.COMPLETED, statusAfterCompletion);
  }

  /**
   * <h3>Test 2 (State Machine Crash Guard): Forcefully intercept and abort illegal jumps</h3>
   *
   * <p><b>Business Context:</b> An erroneous network packet or user action attempts to jump an
   * unapproved AI draft directly into an execution state, violating core sequence integrity parameters.</p>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenExecutingProhibitedStateJump() {
    // Arrange: Establish base raw draft slot row
    Long targetTicketId = 7777L;
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, targetTicketId)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().plusDays(2))
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING) // Draft baseline
        .execute();

    // Act & Assert: Directly push for COMPLETED execution bypassing approval filters
    IntegrityViolationException exceptions = assertThrows(IntegrityViolationException.class, () ->
            workflowService.completeAppointment(targetTicketId),
        "An AI_PENDING ticket must never bypass the APPROVED confirmation layer."
    );

    assertTrue(exceptions.getMessage().contains("Ошибка конечного автомата"),
        "The caught error sequence description must specify a clear State Machine clash failure message.");

    // Verification: Assert that row properties remained unaffected on disk during transaction failure loops
    AppointmentStatus unalteredStatus = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.ID.eq(targetTicketId)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.AI_PENDING, unalteredStatus,
        "An illegal state transition request must trigger a complete database rollback pass.");
  }

  /**
   * <h3>Test 3 (Absent Target Check): Verify behavior when trying to update a missing record</h3>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenTargetRecordIsMissingFromDatabase() {
    // Arrange: Explicitly leverage an unregistered metadata entity key
    Long nonexistentId = 9999123L;

    // Act & Assert: Call execution path targeting an empty slot row
    IntegrityViolationException exception = assertThrows(IntegrityViolationException.class, () ->
        workflowService.approveAppointment(nonexistentId)
    );

    assertTrue(exception.getMessage().contains("не найдена в базе данных"),
        "The error output stream must clearly describe a target-missing operational vector.");
  }

  /**
   * <h3>Test 4 (Valid Cancellation Pass): Cancel an active AI draft session</h3>
   *
   * <p><b>Business Context:</b> A client cancels their request via the chatbot before the
   * salon owner confirms it, or the owner declines the provisional booking via the dashboard [Strict Grounding].</p>
   */
  @Test
  void shouldSuccessfullyCancelAppointmentWhenInAiPendingStatus() {
    // Arrange: Create a baseline AI draft ticket record
    Long ticketId = 8888L;
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, ticketId)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().plusDays(1))
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING)
        .execute();

    // Act: Trigger the cancel action contract method
    assertDoesNotThrow(() -> workflowService.cancelAppointment(ticketId),
        "Cancelling a fresh provisional AI draft is completely valid.");

    // Assert: Verify state mutation on disk
    AppointmentStatus identityStatus = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.ID.eq(ticketId)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.CANCELED, identityStatus,
        "The state store must transition cleanly to CANCELED status.");
  }

  /**
   * <h3>Test 5 (Terminal Rejection Guard): Prevent cancellation of already completed visits</h3>
   *
   * <p><b>Business Context:</b> A client was already successfully served, and the transaction is closed.
   * The owner cannot accidentally trigger a cancel rule on a historical record [Strict Grounding].</p>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenAttemptingToCancelACompletedAppointment() {
    // Arrange: Establish a closed terminal visit record
    Long finishedTicketId = 9999L;
    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, finishedTicketId)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().minusDays(1)) // In the past
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.COMPLETED) // Terminal State
        .execute();

    // Act & Assert: Verify that a state mutation attempt on a terminal row is caught and rolled back
    IntegrityViolationException exception = assertThrows(IntegrityViolationException.class, () ->
            workflowService.cancelAppointment(finishedTicketId),
        "A closed COMPLETED transaction must be immutable to cancellation requests."
    );

    assertTrue(exception.getMessage().contains("Ошибка конечного автомата"),
        "The error payload must declare a clear state machine validation clash exception.");

    // Verify database state isolation remains untainted
    AppointmentStatus currentStatus = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.ID.eq(finishedTicketId)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.COMPLETED, currentStatus,
        "The database layer must enforce a strict rollback when a business transition constraint fails.");
  }
}
