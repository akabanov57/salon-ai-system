package salon.api.service;

/**
 * <h3>Доменный контракт диспетчеризации статусов визитов (Сценарий 4)</h3>
 *
 * <p>Управляет жизненным циклом записей клиентов, обеспечивая строгое соблюдение
 * правил конечного автомата (State Machine) на переходы между статусами визитов [Strict
 * Grounding].</p>
 *
 * <p><b>Разрешенная матрица переходов:</b>
 * <ul>
 *   <li>{@code AI_PENDING} (Черновик ИИ) &rarr; {@code APPROVED} (Одобрено управляющим) или
 *   {@code CANCELED} (Отменено)</li>
 *   <li>{@code APPROVED} (Одобрено) &rarr; {@code COMPLETED} (Успешно визит состоялся) или
 *   {@code CANCELED} (Отменено)</li>
 *   <li>{@code CANCELED}, {@code COMPLETED} &rarr; Являются терминальными статусами, любые
 *   переходы из них запрещены</li>
 * </ul>
 * </p>
 */
public interface AppointmentWorkflowService {

  /**
   * Переводит предварительную запись в статус APPROVED (Подтверждение черновика управляющим).
   *
   * <p>Этот статус делает запись официальной и выводит её на сетку Vaadin-календаря [Strict
   * Grounding].</p>
   *
   * @param appointmentId Уникальный идентификатор сеанса записи клиентов
   * @throws IntegrityViolationException    если запись не найдена или её текущий статус не
   *                                        позволяет сделать переход в APPROVED (например, уже
   *                                        отменена) [Strict Grounding]
   * @throws StorageInfrastructureException если произошел критический сбой на уровне СУБД (таймаут,
   *                                        потеря сети) [Strict Grounding]
   */
  void approveAppointment(Long appointmentId);

  /**
   * Переводит текущую запись в статус CANCELED (Отмена визита).
   *
   * <p>Допускается отмена как черновиков ИИ ({@code AI_PENDING}), так и уже подтвержденных записей
   * ({@code APPROVED}).
   * Отмена освобождает временной слот для последующего бронирования другими клиентами [Strict
   * Grounding].</p>
   *
   * @param appointmentId Уникальный идентификатор сеанса записи клиентов
   * @throws IntegrityViolationException    если запись не найдена или находится в статусе
   *                                        {@code COMPLETED} [Strict Grounding]
   * @throws StorageInfrastructureException если произошел критический сбой на уровне СУБД [Strict
   *                                        Grounding]
   */
  void cancelAppointment(Long appointmentId);

  /**
   * Переводит запись в статус COMPLETED (Фиксация успешного завершения визита).
   *
   * <p>Вызывается в конце рабочего дня, когда процедура успешно оказана. Данный статус
   * переводит запись в категорию завершенных сделок и делает её доступной для ИИ-сценариев отзывов
   * [Strict Grounding].</p>
   *
   * @param appointmentId Уникальный идентификатор сеанса записи клиентов
   * @throws IntegrityViolationException    если запись не найдена или совершается нелегальный
   *                                        прыжок в обход предварительного одобрения управляющим
   *                                        (например, напрямую из {@code AI_PENDING}) [Strict
   *                                        Grounding]
   * @throws StorageInfrastructureException если произошел критический сбой на уровне СУБД [Strict
   *                                        Grounding]
   */
  void completeAppointment(Long appointmentId);
}
