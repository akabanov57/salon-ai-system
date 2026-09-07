package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.Tables.AI_CONVERSATIONAL_CONTEXTS;
import static salon.db.jooq.generated.Tables.CLIENTS;

import io.avaje.inject.External;
import io.avaje.json.JsonDataException;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.DialogueContext;
import salon.api.model.DialogueContext.Slots;
import salon.api.model.PlatformType;
import salon.api.service.ChatMemoryService;

@Singleton
final class ChatMemoryServiceImpl implements ChatMemoryService {

  private static final Logger log = LoggerFactory.getLogger(ChatMemoryServiceImpl.class);

  private final DSLContext dsl;
  private final JsonType<Slots> slotsJsonType;

  @Inject
  ChatMemoryServiceImpl(DSLContext dsl, @External Jsonb jsonb) {
    this.dsl = dsl;
    this.slotsJsonType = jsonb.type(DialogueContext.Slots.class);
  }

  @Override
  public Optional<DialogueContext> findContextByClientId(PlatformType platformType, String platformId) {
    try {
      // Выполняем INNER JOIN между контекстами ИИ и клиентами по связи 1:1
      return dsl.select(AI_CONVERSATIONAL_CONTEXTS.CURRENT_STATE, AI_CONVERSATIONAL_CONTEXTS.SERIALIZED_SLOTS)
          .from(AI_CONVERSATIONAL_CONTEXTS)
          .join(CLIENTS).on(AI_CONVERSATIONAL_CONTEXTS.CLIENT_ID.eq(CLIENTS.ID))
          .where(CLIENTS.PLATFORM_TYPE.eq(platformType.name()))
          .and(CLIENTS.PLATFORM_ID.eq(platformId))
          .fetchOptional()
          .map(record -> {
            try {
              DialogueContext.Slots slots = slotsJsonType.fromJson(record.get(AI_CONVERSATIONAL_CONTEXTS.SERIALIZED_SLOTS));
              return new DialogueContext(
                  record.get(AI_CONVERSATIONAL_CONTEXTS.CURRENT_STATE),
                  slots,
                  new DialogueContext.Metadata(0, 0, platformId)
              );
            } catch (JsonDataException e) {
              log.error("Критическая ошибка десериализации JSON слотов для клиента [{}, {}]", platformType, platformId, e);
              throw new StorageInfrastructureException("Внутренняя структура памяти диалога в ячейке TEXT повреждена", e);
            }
          });
    } catch (DataAccessException e) {
      log.error("Сбой СУБД при попытке чтения контекста ИИ для клиента [{}, {}]", platformType, platformId, e);
      throw new StorageInfrastructureException("Не удалось восстановить контекст разговора из базы данных", e);
    }
  }

  @Override
  public void saveContext(PlatformType platformType, String platformId, DialogueContext context) {
    try {
      String jsonSlots = slotsJsonType.toJson(context.slots());

      // Выполнение в рамках безопасной изолированной транзакции jOOQ
      dsl.transaction(configuration -> {
        var transactionalDsl = org.jooq.impl.DSL.using(configuration);

        Long clientId = transactionalDsl.select(CLIENTS.ID)
            .from(CLIENTS)
            .where(CLIENTS.PLATFORM_TYPE.eq(platformType.name()))
            .and(CLIENTS.PLATFORM_ID.eq(platformId))
            .fetchOneInto(Long.class);

        if (clientId == null) {
          throw new IntegrityViolationException(String.format(
              "Нарушение целостности: клиент с натуральным ключом [%s, %s] не обнаружен в системе.",
              platformType, platformId));
        }

        transactionalDsl.insertInto(AI_CONVERSATIONAL_CONTEXTS)
            .set(AI_CONVERSATIONAL_CONTEXTS.CLIENT_ID, clientId)
            .set(AI_CONVERSATIONAL_CONTEXTS.CURRENT_STATE, context.currentState())
            .set(AI_CONVERSATIONAL_CONTEXTS.SERIALIZED_SLOTS, jsonSlots)
            .onDuplicateKeyUpdate()
            .set(AI_CONVERSATIONAL_CONTEXTS.CURRENT_STATE, context.currentState())
            .set(AI_CONVERSATIONAL_CONTEXTS.SERIALIZED_SLOTS, jsonSlots)
            .set(AI_CONVERSATIONAL_CONTEXTS.UPDATED_AT, java.time.LocalDateTime.now())
            .execute();
      });
    } catch (IntegrityConstraintViolationException e) {
      // Перехватываем специфичную ошибку ограничений jOOQ
      log.error("СУБД заблокировала запись ИИ-контекста из-за нарушения внешних ключей/уникальности для [{}, {}]",
          platformType, platformId, e);

      // Транслируем в наше исключение нарушения целостности
      throw new IntegrityViolationException("Конфликт ограничений целостности при сохранении сессии диалога", e);

    } catch (DataAccessException e) {
      // Сюда попадают только инфраструктурные ошибки (сеть, пулы, диски)
      log.error("Инфраструктурный сбой транзакции сохранения контекста ИИ для [{}, {}]", platformType, platformId, e);
      throw new StorageInfrastructureException("Не удалось атомарно персистить состояние диалога ИИ из-за ошибки СУБД", e);
    }
  }
}
