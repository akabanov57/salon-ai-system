package salon.db.jooq.service.impl;

import static salon.db.jooq.generated.Tables.SALON_CALENDAR_EXCEPTIONS;
import static salon.db.jooq.generated.Tables.SALON_WEEKLY_SCHEDULE;

import jakarta.inject.Singleton;
import java.time.LocalDate;
import java.time.LocalTime;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.model.SalonDayWindow;
import salon.api.service.SalonScheduleProvider;

@Singleton
final class SalonScheduleProviderImpl implements SalonScheduleProvider {

  private static final Logger log = LoggerFactory.getLogger(SalonScheduleProviderImpl.class);
  private final DSLContext dslCtx;

  public SalonScheduleProviderImpl(DSLContext dslCtx) {
    this.dslCtx = dslCtx;
  }

  @Override
  public SalonDayWindow getWorkingWindowFor(LocalDate date) {
    String dayOfWeekName = date.getDayOfWeek().name();
    log.debug("[Infrastructure] Resolving salon working window for date [{}] ({})", date, dayOfWeekName);

    // Честный атомарный Fallback/Override запрос с использованием сгенерированных jOOQ-типов
    var record = dslCtx.select(
            DSL.coalesce(SALON_CALENDAR_EXCEPTIONS.IS_CLOSED, SALON_WEEKLY_SCHEDULE.IS_CLOSED).as("FINAL_CLOSED"),
            DSL.coalesce(SALON_CALENDAR_EXCEPTIONS.OPEN_TIME, SALON_WEEKLY_SCHEDULE.OPEN_TIME).as("FINAL_OPEN"),
            DSL.coalesce(SALON_CALENDAR_EXCEPTIONS.CLOSE_TIME, SALON_WEEKLY_SCHEDULE.CLOSE_TIME).as("FINAL_CLOSE")
        )
        .from(SALON_WEEKLY_SCHEDULE)
        .leftJoin(SALON_CALENDAR_EXCEPTIONS)
        .on(SALON_CALENDAR_EXCEPTIONS.CALENDAR_DATE.eq(date))
        .where(SALON_WEEKLY_SCHEDULE.DAY_OF_WEEK.eq(dayOfWeekName))
        .fetchOne();

    // Предохранительный барьер на случай пустой базовой конфигурации дней недели
    if (record == null) {
      log.error("[Infrastructure] Critical data anomaly: Day of week [{}] is missing in SALON_WEEKLY_SCHEDULE!", dayOfWeekName);
      return new SalonDayWindow(false, LocalTime.of(9, 0), LocalTime.of(21, 0));
    }

    // Извлекаем строго типизированные алиасы из jOOQ record
    return new SalonDayWindow(
        record.get("FINAL_CLOSED", Boolean.class),
        record.get("FINAL_OPEN", LocalTime.class),
        record.get("FINAL_CLOSE", LocalTime.class)
    );
  }
}
