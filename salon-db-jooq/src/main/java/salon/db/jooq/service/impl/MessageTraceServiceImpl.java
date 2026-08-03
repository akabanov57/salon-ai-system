package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;

import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.PlatformType;
import salon.api.service.MessageTraceService;

/**
 * Реализация сервиса архивации сообщений на базе СУБД с использованием jOOQ.
 * Гарантирует запись каждого сообщения на диск для последующего анализа работы салона.
 */
@Singleton
final class MessageTraceServiceImpl implements MessageTraceService {

  private static final Logger log = LoggerFactory.getLogger(MessageTraceServiceImpl.class);

  private final DSLContext dsl;

  MessageTraceServiceImpl(DSLContext dsl) {
    this.dsl = dsl;
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
  public void logTrace(String traceId, PlatformType platformType, String platformId, Direction direction, String rawPayload, String messageText) {
    log.debug("Архивация диалога. Канал: {}, Натуральный ID: {}, Маркер: {}", platformType, platformId, traceId);

    try {
      // 1. Поиск внутреннего суррогатного ID клиента по многоканальным координатам платформы
      Long internalSurrogateClientId = dsl.select(CLIENTS.ID)
          .from(CLIENTS)
          .where(CLIENTS.PLATFORM_TYPE.eq(platformType.name()))
          .and(CLIENTS.PLATFORM_ID.eq(platformId))
          .fetchOneInto(Long.class);

      // 2. Вставка записи в полностью нормализованную таблицу MESSAGE_TRACES
      dsl.insertInto(MESSAGE_TRACES)
          .set(MESSAGE_TRACES.TRACE_ID, traceId)
          .set(MESSAGE_TRACES.DIRECTION, direction.name())
          .set(MESSAGE_TRACES.RAW_PAYLOAD, rawPayload)
          .set(MESSAGE_TRACES.MESSAGE_TEXT, messageText)
          .set(MESSAGE_TRACES.CLIENT_ID, internalSurrogateClientId) // Связываем через семантический внешний ключ
          .execute();

    } catch (Exception e) {
      // FIX: Вместо ручного хардкода StorageInfrastructureException
      // используем правильную доменную трансляцию!
      throw translateException(e);
    }
  }
}
