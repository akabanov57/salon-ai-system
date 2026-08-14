package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFT_BREAKS;

import jakarta.inject.Singleton;
import java.time.LocalDate;
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
    log.info("[Domain Use-Case] Запущена пакетная публикация рабочих смен. Количество команд: [{}]", commands.size());

    try {
      dslCtx.transaction(configuration -> {
        DSLContext txCtx = configuration.dsl();

        for (PublishShiftCommand command : commands) {
          // 1. ИНФРАСТРУКТУРНЫЙ МАППИНГ: Разрешаем masterAlias в суррогатный ID базы данных
          Long masterId = txCtx.select(MASTERS.ID)
              .from(MASTERS)
              .where(MASTERS.ALIAS.eq(command.masterAlias().trim().toLowerCase()))
              .fetchOneInto(Long.class);

          if (masterId == null) {
            throw new IntegrityViolationException(String.format(
                "Ошибка публикации смены: Мастер с псевдонимом [%s] не зарегистрирован в системе.", command.masterAlias()
            ));
          }

          // 2. ВАЛИДАЦИЯ КОРИДОРОВ РАСПИСАНИЯ ОТНОСИТЕЛЬНО ЧАСОВ РАБОТЫ САЛОНА
          LocalDate shiftDate = command.shiftStart().toLocalDate();
          SalonDayWindow salonWindow = salonScheduleProvider.getWorkingWindowFor(shiftDate);

          // FIX: Принудительно извлекаем чистые часы LocalTime для прохождения строгой компиляции Java Time API
          if (command.shiftStart().toLocalTime().isBefore(salonWindow.openTime()) || command.shiftEnd().toLocalTime().isAfter(salonWindow.closeTime())) {
            throw new IntegrityViolationException(String.format(
                "Конфликт регламента: Рабочая смена мастера [%s] (%s - %s) выходит за пределы часов работы самого салона (%s - %s)!",
                command.masterAlias(), command.shiftStart(), command.shiftEnd(), salonWindow.openTime(), salonWindow.closeTime()
            ));
          }

          // 3. ПЕРСИСТЕНТНОСТЬ: Вставка родительской записи смены в MASTER_SHIFTS
          var shiftRecord = txCtx.insertInto(MASTER_SHIFTS)
              .set(MASTER_SHIFTS.MASTER_ID, masterId)
              .set(MASTER_SHIFTS.SHIFT_START, command.shiftStart())
              .set(MASTER_SHIFTS.SHIFT_END, command.shiftEnd())
              .returning(MASTER_SHIFTS.ID)
              .fetchOne();

          Objects.requireNonNull(shiftRecord, "Database failed to persist master shift entry.");
          Long shiftId = shiftRecord.getId();

          // 4. ПАКЕТНАЯ ВСТАВКА ВСТРОЕННЫХ ПЕРЕРЫВОВ (Вариант 2)
          if (command.plannedBreaks() != null && !command.plannedBreaks().isEmpty()) {
            var batchInsert = txCtx.batch(
                command.plannedBreaks().stream()
                    .map(b -> txCtx.insertInto(MASTER_SHIFT_BREAKS)
                        .set(MASTER_SHIFT_BREAKS.SHIFT_ID, shiftId)
                        .set(MASTER_SHIFT_BREAKS.BREAK_START, b.breakStart())
                        .set(MASTER_SHIFT_BREAKS.BREAK_END, b.breakEnd())
                    ).toList()
            );
            batchInsert.execute();
          }
          log.debug("[Domain Use-Case] Смена для мастера [{}] успешно зафиксирована на диске.", command.masterAlias());
        }
      });
    } catch (Exception ex) {
      throw translateException("Failed to publish batched master shift schedules", ex);
    }
  }

  @Override
  public void injectBreakIntoShift(String masterAlias, LocalDateTime breakStart, LocalDateTime breakEnd) {
    log.info("[Domain Use-Case] Запрос на оперативное внедрение перерыва для мастера [{}] в интервале ({} - {})",
        masterAlias, breakStart, breakEnd);

    try {
      dslCtx.transaction(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // 1. ИНФРАСТРУКТУРНЫЙ МАППИНГ: Переводим бизнес-ключ во внутренний Long ID
        var masterRecord = txCtx.select(MASTERS.ID)
            .from(MASTERS)
            .where(MASTERS.ALIAS.eq(masterAlias.trim().toLowerCase()))
            .fetchOne();

        if (masterRecord == null) {
          throw new IntegrityViolationException(String.format(
              "Операция отклонена: Мастер с псевдонимом [%s] не найден.", masterAlias
          ));
        }

        Long masterId = masterRecord.get(MASTERS.ID);

        // 2. ПОИСК РОДИТЕЛЬСКОЙ СМЕНЫ И ПЕССИМИСТИЧЕСКИЙ ЛОК СТРОКИ СУБД
        var shiftRecord = txCtx.select(MASTER_SHIFTS.ID)
            .from(MASTER_SHIFTS)
            .where(MASTER_SHIFTS.MASTER_ID.eq(masterId))
            .and(MASTER_SHIFTS.SHIFT_START.le(breakStart))
            .and(MASTER_SHIFTS.SHIFT_END.ge(breakEnd))
            .forUpdate() // Блокируем гонки параллельного изменения расписания менеджерами
            .fetchOne();

        if (shiftRecord == null) {
          throw new IntegrityViolationException(String.format(
              "Ошибка расписания: Мастер [%s] не находится на рабочей смене в запрашиваемый период перерыва.", masterAlias
          ));
        }

        Long shiftId = shiftRecord.get(MASTER_SHIFTS.ID);

        // 3. КОМПЛАЕНС-БАРЬЕР СЦЕНАРИЯ 5 (ВАРИАНТ 3): Проверяем накладки на живые визиты клиентов
        boolean hasActiveClientBooking = txCtx.fetchExists(
            txCtx.selectOne()
                .from(APPOINTMENTS)
                .where(APPOINTMENTS.MASTER_ID.eq(masterId))
                .and(APPOINTMENTS.STATUS.in(AppointmentStatus.APPROVED, AppointmentStatus.AI_PENDING))
                // Математика пересечений окон: Запись пересекается с перерывом, если она началась раньше конца перерыва
                // и заканчивается (время начала + длительность) позже начала перерыва
                .and(APPOINTMENTS.APPOINTMENT_TIME.lt(breakEnd))
                .and(org.jooq.impl.DSL.localDateTimeAdd(
                    APPOINTMENTS.APPOINTMENT_TIME,
                    APPOINTMENTS.DURATION_MINUTES,
                    org.jooq.DatePart.MINUTE
                ).gt(breakStart))
        );

        if (hasActiveClientBooking) {
          log.warn("[Schedule Clash] Не удалось вставить перерыв для [{}]: на выбранное время уже есть предварительная или подтвержденная запись", masterAlias);
          throw new IntegrityViolationException(
              "Операция отклонена: на выбранное время уже есть предварительная или подтвержденная запись живого клиента!"
          );
        }

        // 4. ПЕРСИСТЕНТНОСТЬ: Фиксация технологического окна отдыха в базе данных
        txCtx.insertInto(MASTER_SHIFT_BREAKS)
            .set(MASTER_SHIFT_BREAKS.SHIFT_ID, shiftId)
            .set(MASTER_SHIFT_BREAKS.BREAK_START, breakStart)
            .set(MASTER_SHIFT_BREAKS.BREAK_END, breakEnd)
            .execute();

        log.info("[Schedule Success] Технологический перерыв для [{}] успешно внедрен в СУБД.", masterAlias);
      });
    } catch (Exception ex) {
      throw translateException("Failed to dynamically inject rest break window into active shift", ex);
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
