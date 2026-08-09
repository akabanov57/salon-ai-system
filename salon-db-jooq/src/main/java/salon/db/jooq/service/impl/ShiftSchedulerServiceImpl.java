package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFT_BREAKS;

import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.AppointmentStatus;
import salon.api.model.SalonDayWindow;
import salon.api.service.SalonScheduleProvider;
import salon.api.service.ShiftSchedulerService;

@Singleton
final class ShiftSchedulerServiceImpl implements ShiftSchedulerService {

  private static final Logger log = LoggerFactory.getLogger(ShiftSchedulerServiceImpl.class);

  private final DSLContext dslCtx;
  private final SalonScheduleProvider salonScheduleProvider; // Инжектим провайдер границ салона

  public ShiftSchedulerServiceImpl(DSLContext dslCtx, SalonScheduleProvider salonScheduleProvider) {
    this.dslCtx = dslCtx;
    this.salonScheduleProvider = salonScheduleProvider;
  }

  @Override
  public void publishShifts(List<PublishShiftCommand> commands) {
    log.info("[Domain Use-Case] Начат пакетный процесс публикации смен (Команд: {})", commands.size());

    try {
      dslCtx.transaction(configuration -> {
        DSLContext tx = configuration.dsl();

        for (PublishShiftCommand cmd : commands) {
          // ШАГ 1: Валидация коридора относительно живого расписания салона (Вариант 1 и 2)
          SalonDayWindow salonWindow = salonScheduleProvider.getWorkingWindowFor(cmd.shiftStart().toLocalDate());

          if (salonWindow.isClosed()) {
            throw new IntegrityViolationException(String.format(
                "Ошибка публикации: Парикмахерская полностью закрыта на дату %s!", cmd.shiftStart().toLocalDate()
            ));
          }

          if (cmd.shiftStart().toLocalTime().isBefore(salonWindow.openTime()) ||
              cmd.shiftEnd().toLocalTime().isAfter(salonWindow.closeTime())) {
            throw new IntegrityViolationException(String.format(
                "Ошибка публикации: Смена мастера [%d] (%s - %s) выходит за официальные рамки работы салона (%s - %s)!",
                cmd.masterId(), cmd.shiftStart().toLocalTime(), cmd.shiftEnd().toLocalTime(),
                salonWindow.openTime(), salonWindow.closeTime()
            ));
          }

          // ШАГ 2: Проверка внутренних накладок смен самого мастера
          boolean shiftOverlaps = tx.fetchExists(
              tx.selectOne()
                  .from(MASTER_SHIFTS)
                  .where(MASTER_SHIFTS.MASTER_ID.eq(cmd.masterId()))
                  .and(MASTER_SHIFTS.SHIFT_START.lt(cmd.shiftEnd()))
                  .and(MASTER_SHIFTS.SHIFT_END.gt(cmd.shiftStart()))
          );

          if (shiftOverlaps) {
            throw new IntegrityViolationException(String.format(
                "Ошибка публикации: Обнаружено внутреннее пересечение графиков для мастера [%d] в интервале %s - %s!",
                cmd.masterId(), cmd.shiftStart(), cmd.shiftEnd()
            ));
          }

          // ШАГ 3: Запись родительской смены в MASTER_SHIFTS
          var shiftRecord = tx.insertInto(MASTER_SHIFTS)
              .set(MASTER_SHIFTS.MASTER_ID, cmd.masterId())
              .set(MASTER_SHIFTS.SHIFT_START, cmd.shiftStart())
              .set(MASTER_SHIFTS.SHIFT_END, cmd.shiftEnd())
              .returning(MASTER_SHIFTS.ID)
              .fetchOne();

          // Защитный барьер для исключения варнингов статического анализа IDE
          Objects.requireNonNull(shiftRecord,
              "Критическая ошибка СУБД: Не удалось зафиксировать и получить идентификатор опубликованной смены.");

          Long generatedShiftId = shiftRecord.get(MASTER_SHIFTS.ID);

          // ШАГ 4: Высокопроизводительная пакетная вставка перерывов через Batch API (Вариант 2)
          if (cmd.plannedBreaks() != null && !cmd.plannedBreaks().isEmpty()) {
            var batchQueries = cmd.plannedBreaks().stream()
                .map(b -> tx.insertInto(MASTER_SHIFT_BREAKS)
                    .set(MASTER_SHIFT_BREAKS.SHIFT_ID, generatedShiftId)
                    .set(MASTER_SHIFT_BREAKS.BREAK_START, b.breakStart())
                    .set(MASTER_SHIFT_BREAKS.BREAK_END, b.breakEnd()))
                .toList();

            tx.batch(batchQueries).execute();

            log.debug("[Domain] Успешно зафиксировано {} запланированных перерывов через Batch API для смены ID: {}",
                cmd.plannedBreaks().size(), generatedShiftId);
          }
        }
      });
    } catch (Exception ex) {
      throw translateException("Failed to execute transacted master shift publication pipeline", ex);
    }
  }

  @Override
  public void injectBreakIntoShift(Long masterId, LocalDateTime breakStart,
      LocalDateTime breakEnd) {

    log.info("[Domain Use-Case] Запрошено оперативное внедрение перерыва для мастера [{}] ({} - {})",
        masterId, breakStart, breakEnd);

    try {
      dslCtx.transaction(configuration -> {
        DSLContext tx = configuration.dsl();

        // ШАГ 1: Поиск активной родительской смены мастера (Вариант 3)
        var parentShift = tx.select(MASTER_SHIFTS.ID)
            .from(MASTER_SHIFTS)
            .where(MASTER_SHIFTS.MASTER_ID.eq(masterId))
            .and(MASTER_SHIFTS.SHIFT_START.le(breakStart)) // Перерыв начнется не раньше смены
            .and(MASTER_SHIFTS.SHIFT_END.ge(breakEnd))     // Перерыв закончится не позже смены
            .fetchOne();

        if (parentShift == null) {
          throw new IntegrityViolationException(String.format(
              "Невозможно добавить перерыв: Заданный интервал %s - %s не укладывается ни в одну опубликованную смешанную сетку мастера [%d]!",
              breakStart, breakEnd, masterId
          ));
        }

        Long shiftId = parentShift.get(MASTER_SHIFTS.ID);

        // ШАГ 2: ЖЕЛЕЗНОЕ БИЗНЕС-ПРАВИЛО — Проверка накладок на живые записи клиентов
        boolean hasClientClash = tx.fetchExists(
            tx.selectOne()
                .from(APPOINTMENTS)
                .where(APPOINTMENTS.MASTER_ID.eq(masterId))
                // Строгое использование констант Enum взамен сырых строковых токенов
                .and(APPOINTMENTS.STATUS.in(AppointmentStatus.APPROVED, AppointmentStatus.AI_PENDING))
                .and(APPOINTMENTS.APPOINTMENT_TIME.lt(breakEnd))       // Запись начнется раньше конца обеда
                .and(org.jooq.impl.DSL.localDateTimeAdd(
                    APPOINTMENTS.APPOINTMENT_TIME,
                    APPOINTMENTS.DURATION_MINUTES,
                    org.jooq.DatePart.MINUTE
                ).gt(breakStart))                                      // Запись закончится позже старта обеда
        );

        if (hasClientClash) {
          log.warn("[Domain Compliance] Отклонение создания перерыва: слот пересекается с активной записью клиента!");
          throw new IntegrityViolationException(
              "Невозможно добавить перерыв: на выбранное время уже есть предварительная или подтвержденная запись клиента!"
          );
        }

        // ШАГ 3: Если коллизий с клиентами нет — фиксируем перерыв отдыха на диске
        tx.insertInto(MASTER_SHIFT_BREAKS)
            .set(MASTER_SHIFT_BREAKS.SHIFT_ID, shiftId)
            .set(MASTER_SHIFT_BREAKS.BREAK_START, breakStart)
            .set(MASTER_SHIFT_BREAKS.BREAK_END, breakEnd)
            .execute();

        log.info("[Domain Use-Case] Оперативный перерыв успешно внедрен в существующую смену ID: {}", shiftId);
      });
    } catch (Exception ex) {
      // Защищаем верхние слои от инфраструктурного шума СУБД!
      throw translateException("Failed to inject dynamic shift break transaction safely", ex);
    }
  }

  /**
   * Превращает низкоуровневые системные ошибки jOOQ и JDBC в типизированные
   * доменные исключения, сохраняя чистоту архитектурных границ [Strict Grounding].
   */
  private RuntimeException translateException(String contextMessage, Exception ex) {
    log.error("Shift scheduler boundary trapped failure details: {}", ex.getMessage(), ex);

    // Если это наше собственное бизнес-исключение проброшенное изнутри — выпускаем без изменений
    if (ex instanceof IntegrityViolationException) {
      return (IntegrityViolationException) ex;
    }

    if (ex instanceof IntegrityConstraintViolationException) {
      return new IntegrityViolationException(contextMessage + ": Business data integrity restriction violated.", ex);
    }

    Throwable cause = ex;
    while (cause != null) {
      if (cause instanceof java.sql.SQLException sqlEx) {
        String sqlState = sqlEx.getSQLState();
        if ("23505".equals(sqlState)) {
          return new IntegrityViolationException(contextMessage + ": Unique data constraint violation detected via SQLState.", ex);
        }
      }
      cause = cause.getCause();
    }

    return new StorageInfrastructureException(contextMessage + ": Internal data storage layer error encountered.", ex);
  }
}
