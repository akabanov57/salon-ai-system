package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.Tables.INBOUND_EVENTS;

import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.PlatformType;
import salon.api.service.IdempotencyService;

@Singleton
final class IdempotencyServiceImpl implements IdempotencyService {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

  private final DSLContext dslCtx;

  IdempotencyServiceImpl(DSLContext dslCtx) {
    this.dslCtx = dslCtx;
  }

  /**
   * Превращает низкоуровневые системные ошибки jOOQ и JDBC драйвера в типизированные
   * доменные исключения, полностью изолируя вышестоящие слои от деталей СУБД.
   */
  private RuntimeException translateException(Exception ex) {
    log.error("Infrastructure trapped failure details: {}", ex.getMessage(), ex);

    // Static literal extracted to satisfy compiler analysis tools perfectly
    final String baseMsg = "Failed to commit conversation tracking frame to data logs";

    if (ex instanceof IntegrityConstraintViolationException) {
      return new IntegrityViolationException(baseMsg + ": Business data integrity restriction violated.", ex);
    }

    Throwable cause = ex;
    while (cause != null) {
      if (cause instanceof java.sql.SQLException sqlEx) {
        String sqlState = sqlEx.getSQLState();
        if ("23505".equals(sqlState)) {
          return new IntegrityViolationException(baseMsg + ": Unique data constraint violation detected via SQLState.", ex);
        }
      }
      cause = cause.getCause();
    }

    return new StorageInfrastructureException(baseMsg + ": Internal data storage layer error encountered.", ex);
  }

  @Override
  public boolean tryAcquireLock(PlatformType platformType, String messengerMessageId) {
    try {
      return dslCtx.transactionResult(configuration -> {
        DSLContext tx = configuration.dsl();

        // Легковесная атомарная вставка в таблицу уникальных замков
        tx.insertInto(INBOUND_EVENTS)
            .set(INBOUND_EVENTS.PLATFORM_TYPE, platformType.name())
            .set(INBOUND_EVENTS.MESSENGER_MESSAGE_ID, messengerMessageId)
            .execute();

        return true; // Успешно: замок захвачен, событие уникально
      });
    } catch (Exception e) {
      // Используем вашу правильную доменную трансляцию!
      throw translateException(e);
    }
  }
}
