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
    dslCtx.execute("SET REFERENTIAL_INTEGRITY FALSE");
    dslCtx.truncate(APPOINTMENTS).execute();
    dslCtx.truncate(SERVICES).execute();
    dslCtx.truncate(CLIENTS).execute();
    dslCtx.truncate(MASTERS).execute();
    dslCtx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    dslCtx.insertInto(CLIENTS)
        .set(CLIENTS.ID, mockClientId)
        .set(CLIENTS.PLATFORM_TYPE, "TELEGRAM")
        .set(CLIENTS.PLATFORM_ID, "999")
        .set(CLIENTS.DISPLAY_NAME, "Test Client")
        .execute();

    final String mockMasterAlias = "anna_manicure_w4";
    dslCtx.insertInto(MASTERS)
        .set(MASTERS.ID, mockMasterId)
        .set(MASTERS.ALIAS, mockMasterAlias)
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
   * <h3>Тест 1: Успешная цепочка переходов (AI_PENDING -> APPROVED -> COMPLETED)</h3>
   */
  @Test
  void shouldSuccessfullyProgressStatusWhenFollowingLegitimateTransitionChain() {
    // Arrange: Назначаем уникальный строковый бизнес-код билета визита
    String targetTicketCode = "SB-20260813-HAPPY";

    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, 5555L)
        .set(APPOINTMENTS.TICKET_CODE, targetTicketCode) // Бизнес-ключ
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().plusDays(1))
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING)
        .execute();

    // Act & Assert Шаг А: Одобрение по TICKET_CODE
    assertDoesNotThrow(() -> workflowService.approveAppointment(targetTicketCode),
        "Перевод записи из AI_PENDING в APPROVED по ticketCode легитимен.");

    AppointmentStatus statusAfterApproval = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.TICKET_CODE.eq(targetTicketCode)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.APPROVED, statusAfterApproval);

    // Act & Assert Шаг Б: Завершение визита по TICKET_CODE
    assertDoesNotThrow(() -> workflowService.completeAppointment(targetTicketCode),
        "Перевод одобренной записи в статус COMPLETED разрешен законом автомата.");

    AppointmentStatus statusAfterCompletion = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.TICKET_CODE.eq(targetTicketCode)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.COMPLETED, statusAfterCompletion);
  }

  /**
   * <h3>Тест 2: Запрет нелегального прыжка статуса (AI_PENDING -> COMPLETED)</h3>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenExecutingProhibitedStateJump() {
    String clashingTicketCode = "SB-20260813-CLASH";

    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, 7777L)
        .set(APPOINTMENTS.TICKET_CODE, clashingTicketCode)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().plusDays(2))
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING)
        .execute();

    // Act & Assert: Вызов по строковому бизнес-ключу обязан выбросить ошибку
    IntegrityViolationException exceptions = assertThrows(IntegrityViolationException.class, () ->
        workflowService.completeAppointment(clashingTicketCode)
    );

    assertTrue(exceptions.getMessage().contains("Ошибка конечного автомата"));

    AppointmentStatus unalteredStatus = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.TICKET_CODE.eq(clashingTicketCode)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.AI_PENDING, unalteredStatus);
  }

  /**
   * <h3>Тест 3: Попытка мутации несуществующего кода билета в СУБД</h3>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenTargetRecordIsMissingFromDatabase() {
    // Act & Assert: Передаем незарегистрированный текстовый токен
    IntegrityViolationException exception = assertThrows(IntegrityViolationException.class, () ->
        workflowService.approveAppointment("SB-ABSENT-TOKEN-999")
    );

    assertTrue(exception.getMessage().contains("не найдена в базе данных"));
  }

  /**
   * <h3>Тест 4: Успешная отмена черновика записи визита (AI_PENDING -> CANCELED)</h3>
   */
  @Test
  void shouldSuccessfullyCancelAppointmentWhenInAiPendingStatus() {
    String cancelTicketCode = "SB-20260813-CANCEL";

    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, 8888L)
        .set(APPOINTMENTS.TICKET_CODE, cancelTicketCode)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().plusDays(1))
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING)
        .execute();

    // Act
    assertDoesNotThrow(() -> workflowService.cancelAppointment(cancelTicketCode));

    // Assert
    AppointmentStatus identityStatus = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.TICKET_CODE.eq(cancelTicketCode)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.CANCELED, identityStatus);
  }

  /**
   * <h3>Тест 5: Запрет отмены визита, который уже успешно завершен (COMPLETED -> CANCELED)</h3>
   */
  @Test
  void shouldThrowIntegrityViolationExceptionWhenAttemptingToCancelACompletedAppointment() {
    String immutableTicketCode = "SB-20260813-IMMUTABLE";

    dslCtx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.ID, 9999L)
        .set(APPOINTMENTS.TICKET_CODE, immutableTicketCode)
        .set(APPOINTMENTS.CLIENT_ID, mockClientId)
        .set(APPOINTMENTS.MASTER_ID, mockMasterId)
        .set(APPOINTMENTS.SERVICE_ID, mockServiceId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, LocalDateTime.now().minusDays(1))
        .set(APPOINTMENTS.DURATION_MINUTES, 45)
        .set(APPOINTMENTS.PRICE, java.math.BigDecimal.valueOf(1500.00))
        .set(APPOINTMENTS.STATUS, AppointmentStatus.COMPLETED)
        .execute();

    // Act & Assert
    assertThrows(IntegrityViolationException.class, () ->
        workflowService.cancelAppointment(immutableTicketCode)
    );

    AppointmentStatus currentStatus = dslCtx.select(APPOINTMENTS.STATUS)
        .from(APPOINTMENTS).where(APPOINTMENTS.TICKET_CODE.eq(immutableTicketCode)).fetchOneInto(AppointmentStatus.class);
    assertEquals(AppointmentStatus.COMPLETED, currentStatus);
  }
}
