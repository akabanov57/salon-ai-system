package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;

import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public MessageTraceServiceImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void logTrace(String traceId, PlatformType platformType, String platformId, Direction direction, String rawPayload, String messageText) {
    log.debug("Архивация диалога. Канал: {}, Натуральный ID: {}, Маркер операции: {}", platformType, platformId, traceId);

    try {
      // На низком уровне СУБД пытаемся сопоставить натуральный ключ с внутренним суррогатным ID [2]
      Long internalSurrogateClientId = dsl.select(CLIENTS.ID)
          .from(CLIENTS)
          .where(CLIENTS.TELEGRAM_ID.eq(platformId)) // Maps your concrete column link
          .fetchOneInto(Long.class);

      // Пишем запись в лог [2]
      dsl.insertInto(MESSAGE_TRACES)
          .set(MESSAGE_TRACES.TRACE_ID, traceId)
          .set(MESSAGE_TRACES.PLATFORM_TYPE, platformType.name())
          .set(MESSAGE_TRACES.PLATFORM_ID, platformId) // Фиксируем натуральный ключ [2]
          .set(MESSAGE_TRACES.DIRECTION, direction.name())
          .set(MESSAGE_TRACES.RAW_PAYLOAD, rawPayload)
          .set(MESSAGE_TRACES.MESSAGE_TEXT, messageText)
          .set(MESSAGE_TRACES.CLIENT_ID, internalSurrogateClientId) // Заполняем суррогатный ключ только если он нашелся! [2]
          .execute();

    } catch (Exception e) {
      log.error("Сбой архивации переписки для платформы {} (ID: {}): {}", platformType, platformId, e.getMessage(), e);
    }
  }
}
