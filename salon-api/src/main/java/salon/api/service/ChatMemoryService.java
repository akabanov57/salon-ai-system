package salon.api.service;

import java.util.Optional;
import salon.api.model.DialogueContext;
import salon.api.model.PlatformType;

/**
 * <h3>Высокоуровневый бизнес-сервис для управления жизненным циклом памяти ИИ-ассистента в
 * СУБД.</h3>
 * <p>Зачем: Слово Service в названии точнее отражает назначение компонента: он не просто
 * пробрасывает CRUD-операции к таблице, а инкапсулирует бизнес-логику сериализации/десериализации
 * слотов и координирует правила сохранения сессии. Физическое удаление строк контекста из базы
 * (DELETE) сознательно не используется. Вместо этого при успешной записи или принудительном сбросе
 * ("cancel_or_reset") контекст перезаписывается структурой INIT со свойствами null.</p>
 * <p>Почему: Хранение пустой структуры сессии в БД эффективнее полной очистки. При повторном
 * обращении клиента через месяц базе данных не придется выполнять тяжелую операцию INSERT с
 * выделением памяти и перестройкой индексов первичного ключа — СУБД выполнит молниеносный UPDATE по
 * существующей строке CLIENT_ID. Использование атомарного onDuplicateKeyUpdate() на уровне jOOQ
 * исключает возникновение взаимных блокировок (Deadlocks) в конкурентных потоках вебхуков.</p>
 */
public interface ChatMemoryService {

  /**
   * <h3>Извлечение сохраненного контекста сессии диалога</h3>
   * <p>
   * Выполняет поиск и восстановление текущего состояния конечного автомата из персистентного
   * хранилища.
   * </p>
   *
   * @param platformType Строгий классификатор канала коммуникации {@link PlatformType}. Не должен
   *                     быть {@code null}.
   * @param platformId   Уникальный текстовый идентификатор пользователя внутри целевой платформы.
   *                     Не должен быть {@code null} или пустым.
   * @return {@link Optional}, содержащий восстановленный иммутабельный {@link DialogueContext}, или
   * {@link Optional#empty()}, если клиент пишет впервые.
   * @throws salon.api.exception.StorageInfrastructureException если произошел критический сбой СУБД
   *                                                            (DataAccessException) или повреждена
   *                                                            сериализованная структура JSON.
   */
  Optional<DialogueContext> findContextByClientId(PlatformType platformType, String platformId);

  /**
   * <h3>Сохранение или обновление (Upsert) контекста сессии диалога</h3>
   * <p>
   * Выполняет атомарную запись текущего снимка состояния стейт-машины в базу данных, связывая
   * контекст с существующим ID клиента, найденным по натуральному ключу платформы.
   * </p>
   *
   * @param platformType Строгий классификатор канала коммуникации {@link PlatformType}. Не должен
   *                     быть {@code null}.
   * @param platformId   Уникальный текстовый идентификатор пользователя внутри целевой платформы.
   *                     Не должен быть {@code null} или пустым.
   * @param context      Текущий иммутабельный снимок состояния диалога {@link DialogueContext}.
   * @throws salon.api.exception.IntegrityViolationException    если нарушена целостность
   *                                                            бизнес-логики на уровне SELECT или
   *                                                            СУБД выбросила
   *                                                            {@link
   *                                                            IntegrityConstraintViolationException}.
   * @throws salon.api.exception.StorageInfrastructureException если транзакция СУБД упала из-за
   *                                                            сетевых/аппаратных ошибок.
   */
  void saveContext(PlatformType platformType, String platformId, DialogueContext context);

}
