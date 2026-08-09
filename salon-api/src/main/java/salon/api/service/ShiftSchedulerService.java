package salon.api.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <h3>Доменный контракт планирования рабочих графиков сотрудников (Сценарий 5)</h3>
 */
public interface ShiftSchedulerService {

  /**
   * Вариант 1 и 2: Комплексная публикация рабочих смен (как чистых, так и со встроенными перерывами).
   * Выполняет двухэтапную валидацию коридоров относительно глобального расписания салона.
   */
  void publishShifts(List<PublishShiftCommand> commands);

  /**
   * Вариант 3: Оперативное внедрение перерыва в уже существующую смену мастера.
   * @throws net.akabanov.salon.api.exception.IntegrityViolationException если на это время есть запись
   */
  void injectBreakIntoShift(Long masterId, LocalDateTime breakStart, LocalDateTime breakEnd);

  /**
   * <h3>Доменная команда пакетной публикации рабочей смены мастера</h3>
   *
   * <p>Плоский контракт (Data Transfer Object), инкапсулирующий хронологические
   * параметры создаваемого рабочего дня сотрудника и его фиксированных перерывов [Strict Grounding].</p>
   *
   * @param masterId      Уникальный идентификатор мастера салона, для которого публикуется график [Strict Grounding].
   * @param shiftStart    Дата и время фактического начала рабочей смены сотрудника.
   *                      Обязано быть не раньше официального часа открытия салона в этот день [Strict Grounding].
   * @param shiftEnd      Дата и время фактического окончания рабочей смены сотрудника.
   *                      Обязано быть не позже официального часа закрытия салона в этот день [Strict Grounding].
   * @param plannedBreaks Справочный список запланированных окон отдыха (обеды, технические перерывы).
   *                      Может быть пустым для чистых смен (Вариант 1) или содержать массив интервалов (Вариант 2) [Strict Grounding].
   */
  record PublishShiftCommand(
      Long masterId,
      LocalDateTime shiftStart,
      LocalDateTime shiftEnd,
      List<BreakDto> plannedBreaks
  ) {}

  /**
   * <h3>Доменное представление временного интервала перерыва</h3>
   *
   * <p>Легковесный контракт, описывающий точные границы окна отдыха внутри смены,
   * в течение которого мастер полностью недоступен для записи клиентов [Strict Grounding].</p>
   *
   * @param breakStart Дата и время начала перерыва мастера (например, старт обеда в {@code 13:00:00}).
   *                   Должно строго находиться внутри временных границ родительской смены [Strict Grounding].
   * @param breakEnd   Дата и время окончания перерыва мастера (например, конец обеда в {@code 14:00:00}).
   *                   Должно строго находиться внутри временных границ родительской смены и быть позже начала [Strict Grounding].
   */
  record BreakDto(
      LocalDateTime breakStart,
      LocalDateTime breakEnd
  ) {}

}
