package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.tables.Appointments.APPOINTMENTS;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.AppointmentStatus;
import salon.api.service.AppointmentWorkflowService;

@Singleton
final class AppointmentWorkflowServiceImpl implements AppointmentWorkflowService {

  private static final Logger log = LoggerFactory.getLogger(AppointmentWorkflowServiceImpl.class);
  private final DSLContext dslCtx;

  @Inject
  AppointmentWorkflowServiceImpl(DSLContext dslCtx) {
    this.dslCtx = dslCtx;
  }
  @Override
  public void approveAppointment(Long appointmentId) {
    executeStateTransition(appointmentId, AppointmentStatus.APPROVED,
        "Failed to transition appointment to APPROVED status due to data isolation issue");
  }

  @Override
  public void cancelAppointment(Long appointmentId) {
    executeStateTransition(appointmentId, AppointmentStatus.CANCELED,
        "Failed to transition appointment to CANCELED status due to data isolation issue");
  }

  @Override
  public void completeAppointment(Long appointmentId) {
    executeStateTransition(appointmentId, AppointmentStatus.COMPLETED,
        "Failed to transition appointment to COMPLETED status due to data isolation issue");
  }

  /**
   * Centralized transactional state transition executor engine.
   * Enforces strict row locking and invokes localized State Machine validation logic.
   */
  private void executeStateTransition(Long appointmentId, AppointmentStatus targetStatus, String exceptionContext) {
    log.info("[Domain Use-Case] Request to switch appointment [{}] status directly to [{}]", appointmentId, targetStatus);

    try {
      dslCtx.transaction(configuration -> {
        DSLContext tx = configuration.dsl();

        // STEP 1: CONCURRENT ISOLATION RINGS - Fetch row with a pessimistic row-level lock
        var record = tx.select(APPOINTMENTS.STATUS)
            .from(APPOINTMENTS)
            .where(APPOINTMENTS.ID.eq(appointmentId))
            .forUpdate() // PESSIMISTIC LOCK: Stops race condition collisions in mid-air
            .fetchOne();

        if (record == null) {
          throw new IntegrityViolationException(String.format(
              "Операция отклонена: Запись с идентификатором [%d] не найдена в базе данных салона.", appointmentId
          ));
        }

        // STEP 2: STATE MACHINE COMPLIANCE CHECK
        AppointmentStatus currentStatus = record.get(APPOINTMENTS.STATUS);

        if (!currentStatus.canTransitionTo(targetStatus)) {
          log.warn("[State Machine Clash] Illegal status mutation attempted: [{}] cannot transition to [{}]",
              currentStatus, targetStatus);
          throw new IntegrityViolationException(String.format(
              "Ошибка конечного автомата: Запрещено переводить запись [%d] из текущего статуса [%s] в запрашиваемый [%s]!",
              appointmentId, currentStatus, targetStatus
          ));
        }

        // STEP 3: PERSIST MUTATION STATE TO STORAGE
        int rowsUpdated = tx.update(APPOINTMENTS)
            .set(APPOINTMENTS.STATUS, targetStatus)
            .where(APPOINTMENTS.ID.eq(appointmentId))
            .execute();

        if (rowsUpdated != 1) {
          throw new StorageInfrastructureException(String.format(
              "Failed to rewrite status metadata row for ticket reference [%d]. Update mismatch.", appointmentId
          ));
        }

        log.info("[State Machine Success] Appointment [{}] cleanly mutated from [{}] to [{}]",
            appointmentId, currentStatus, targetStatus);
      });
    } catch (Exception ex) {
      throw translateException(exceptionContext, ex);
    }
  }

  /**
   * Translates deep persistence-layer driver exceptions into clean domain exceptions
   * ensuring architectural isolation parameters are fully preserved.
   */
  private RuntimeException translateException(String contextMessage, Exception ex) {
    log.error("Appointment workflow boundary trapped failure details: {}", ex.getMessage(), ex);

    if (ex instanceof IntegrityViolationException) {
      return (IntegrityViolationException) ex;
    }

    if (ex instanceof IntegrityConstraintViolationException) {
      return new IntegrityViolationException(contextMessage + ": Business data integrity restriction violated.", ex);
    }

    return new StorageInfrastructureException(contextMessage + ": Internal data storage layer error encountered.", ex);
  }
}
