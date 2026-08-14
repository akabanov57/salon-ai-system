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
  public void approveAppointment(String ticketCode) {
    executeStateTransition(ticketCode, AppointmentStatus.APPROVED,
        "Failed to transition appointment to APPROVED status due to data isolation issue");
  }

  @Override
  public void cancelAppointment(String ticketCode) {
    executeStateTransition(ticketCode, AppointmentStatus.CANCELED,
        "Failed to transition appointment to CANCELED status due to data isolation issue");
  }

  @Override
  public void completeAppointment(String ticketCode) {
    executeStateTransition(ticketCode, AppointmentStatus.COMPLETED,
        "Failed to transition appointment to COMPLETED status due to data isolation issue");
  }

  /**
   * Централизованный бесцифровой движок управления транзакционными переходами статусов.
   * Оперирует исключительно естественным ключом TICKET_CODE и защищает от race conditions через FOR UPDATE.
   */
  private void executeStateTransition(String ticketCode, AppointmentStatus targetStatus, String exceptionContext) {
    log.info("[Domain Workflow] Request to switch ticket [{}] status directly to [{}]", ticketCode, targetStatus);

    if (ticketCode == null || ticketCode.trim().isEmpty()) {
      throw new IntegrityViolationException("Операция отклонена: Код билета визита не может быть пустым.");
    }

    try {
      dslCtx.transaction(configuration -> {
        DSLContext tx = configuration.dsl();

        // ШАГ 1: Пессимистическая блокировка строки по уникальному бизнес-ключу TICKET_CODE
        var record = tx.select(APPOINTMENTS.STATUS)
            .from(APPOINTMENTS)
            .where(APPOINTMENTS.TICKET_CODE.eq(ticketCode.trim()))
            .forUpdate() // Жёсткий замок СУБД против параллельных кликов менеджеров в Vaadin UI
            .fetchOne();

        if (record == null) {
          throw new IntegrityViolationException(String.format(
              "Операция отклонена: Запись с кодом билета [%s] не найдена в базе данных салона.", ticketCode
          ));
        }

        // ШАГ 2: Проверка легитимности шага в доменном конечном автомате
        AppointmentStatus currentStatus = record.get(APPOINTMENTS.STATUS); // Чистый jOOQ Enum маппинг

        if (!currentStatus.canTransitionTo(targetStatus)) {
          log.warn("[State Machine Clash] Prohibited mutation requested: [{}] -> [{}] for ticket [{}]",
              currentStatus, targetStatus, ticketCode);
          throw new IntegrityViolationException(String.format(
              "Ошибка конечного автомата: Запрещено переводить запись [%s] из текущего статуса [%s] в запрашиваемый [%s]!",
              ticketCode, currentStatus, targetStatus
          ));
        }

        // ШАГ 3: Фиксация мутации состояния в СУБД
        int rowsUpdated = tx.update(APPOINTMENTS)
            .set(APPOINTMENTS.STATUS, targetStatus)
            .where(APPOINTMENTS.TICKET_CODE.eq(ticketCode.trim()))
            .execute();

        if (rowsUpdated != 1) {
          throw new StorageInfrastructureException(String.format(
              "Failed to update status metadata row for ticket reference [%s]. Row mismatch.", ticketCode
          ));
        }

        log.info("[State Machine Success] Ticket [{}] cleanly mutated from [{}] to [{}]",
            ticketCode, currentStatus, targetStatus);
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
