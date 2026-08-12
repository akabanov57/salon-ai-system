package salon.db.jooq.service;

import static org.jooq.impl.DSL.localDateTimeAdd;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFT_BREAKS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;
import static salon.db.jooq.generated.Tables.SERVICES;

import io.avaje.validation.constraints.Valid;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.DatePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.Client;
import salon.api.model.Master;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.BookingService;

@Singleton
final class BookingServiceImpl implements BookingService {

  private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);

  // FIX: Явно выделяем санитарный зазор между записями клиентов как константу класса
  private static final int SANITARY_BUFFER_MINUTES = 5;

  private final DSLContext dslCtx;

  // Внедряем ТОЛЬКО DSLContext. Никаких промежуточных репозиториев!
  @Inject
  public BookingServiceImpl(DSLContext dslCtx) {
    this.dslCtx = dslCtx;
  }

  private RuntimeException translateException(String contextMessage, Exception ex) {
    log.error("Infrastructure trapped failure details: {}", ex.getMessage(), ex);

    // 1. Handle jOOQ's native integrity exception wrappers directly
    if (ex instanceof org.jooq.exception.IntegrityConstraintViolationException) {
      return new IntegrityViolationException(contextMessage + ": Business data integrity restriction violated.", ex);
    }

    // 2. Fallback check: Extract raw JDBC SQLException metadata to check for SQLState 23505
    Throwable cause = ex;
    while (cause != null) {
      if (cause instanceof java.sql.SQLException sqlEx) {
        String sqlState = sqlEx.getSQLState();
        if ("23505".equals(sqlState)) { // Universal ANSI SQL standard code for unique constraint violation
          return new IntegrityViolationException(contextMessage + ": Unique data constraint violation detected via SQLState.", ex);
        }
      }
      cause = cause.getCause();
    }

    // 3. Structural fallback if any database pool drops, connection timeouts occur, etc.
    return new StorageInfrastructureException(contextMessage + ": Internal data storage layer error encountered.", ex);
  }

  /**
   * Выполняет поиск или генерацию профиля клиента с применением безопасной NOT NULL заглушки.
   */
  private Client txIdOrCreateClientInternal(DSLContext txCtx, PlatformType platformType, String platformId, String displayName) {
    log.info("Business Step: Identifying multi-channel client [{}] on platform [{}] inside transactional bounds",
        platformId, platformType);

    // Отсекаем пробельный мусор и подставляем вежливый дефолтный маркер "Guest"
    final String resolvedName = (displayName == null || displayName.isBlank()) ? "Guest" : displayName.trim();

    // Поиск по нормализованному составному индексу в верхнем регистре
    return txCtx.selectFrom(CLIENTS)
        .where(CLIENTS.PLATFORM_TYPE.eq(platformType.name()))
        .and(CLIENTS.PLATFORM_ID.eq(platformId))
        .fetchOptional()
        .map(r -> new Client(
            r.getId(),
            r.getPlatformType(),
            r.getPlatformId(),
            r.getDisplayName(),
            r.getBonusBalance(),
            r.getCreatedAt()
        ))
        .orElseGet(() -> {
          log.info("New multi-channel client discovered. Generating profile footprint for: {}", resolvedName);

          var newRecord = txCtx.insertInto(CLIENTS)
              .set(CLIENTS.PLATFORM_TYPE, platformType.name())
              .set(CLIENTS.PLATFORM_ID, platformId)
              .set(CLIENTS.DISPLAY_NAME, resolvedName)
              .returning()
              .fetchOne();

          Objects.requireNonNull(newRecord, "Database failed to return the newly inserted client record.");

          return new Client(
              newRecord.getId(),
              newRecord.getPlatformType(),
              newRecord.getPlatformId(),
              newRecord.getDisplayName(),
              newRecord.getBonusBalance(),
              newRecord.getCreatedAt()
          );
        });
  }

  @Override
  public void processMessage(@Valid ProcessMessageCommand command) {
    log.info("[Domain Use-Case] Начат цикл обработки обращения для платформы {} (ID: {})",
        command.platformType(), command.platformId());

    try {
      // Инициализируем неделимую трансляционную транзакцию высшего уровня
      dslCtx.transaction(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // 1. Идентифицируем или создаем нормализованного клиента в базе
        Client client = txIdOrCreateClientInternal(
            txCtx,
            command.platformType(),
            command.platformId(),
            command.displayName()
        );

        // 2. Выполняем прямую атомарную вставку входящего лога в текущей транзакции
        txCtx.insertInto(MESSAGE_TRACES)
            .set(MESSAGE_TRACES.TRACE_ID, command.traceId())
            .set(MESSAGE_TRACES.DIRECTION, "INBOUND")
            .set(MESSAGE_TRACES.MESSAGE_TEXT, command.messageText())
            .set(MESSAGE_TRACES.CLIENT_ID, client.id())
            .execute();

        log.debug("[Domain Use-Case] Входящий лог транзакции {} успешно сохранен.", command.traceId());
      });
    } catch (Exception ex) {
      // Перехватываем технический мусор jOOQ и превращаем его в чистое доменное исключение
      throw translateException("Failed to identify or create multi-channel client profile", ex);
    }
  }

  @Override
  public Optional<Appointment> tryAiBooking(Long clientId, Long masterId, Long serviceId, LocalDateTime appointmentTime) {
    log.info("Database Step: Attempting shift-and-break aware AI booking for master [{}] at service [{}]", masterId, serviceId);

    try {
      return dslCtx.transactionResult(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // 1. Извлекаем ОДНОВРЕМЕННО длительность и цену услуги из каталога jOOQ метамодели
        var serviceRecord = txCtx.select(SERVICES.DURATION_MINUTES, SERVICES.PRICE)
            .from(SERVICES)
            .where(SERVICES.ID.eq(serviceId))
            .fetchOne();

        if (serviceRecord == null) {
          log.warn("Database Step: Rejection. Service [{}] does not exist in catalog.", serviceId);
          return Optional.empty();
        }

        int durationMinutes = serviceRecord.get(SERVICES.DURATION_MINUTES);
        java.math.BigDecimal historicalPrice = serviceRecord.get(SERVICES.PRICE);

        // 2. Трехэтапная проверка доступности (включая константу буфера и перерывы)
        boolean isAvailable = isMasterAvailableAtInternal(txCtx, masterId, appointmentTime, durationMinutes);

        if (!isAvailable) {
          log.warn("Database Step: Rejection. Master [{}] is unavailable at [{}].", masterId, appointmentTime);
          return Optional.empty();
        }

        // 3. Атомарное сохранение с копированием цены на дату бронирования
        var record = txCtx.insertInto(APPOINTMENTS)
            .set(APPOINTMENTS.CLIENT_ID, clientId)
            .set(APPOINTMENTS.MASTER_ID, masterId)
            .set(APPOINTMENTS.SERVICE_ID, serviceId)
            .set(APPOINTMENTS.APPOINTMENT_TIME, appointmentTime)
            .set(APPOINTMENTS.DURATION_MINUTES, durationMinutes)
            .set(APPOINTMENTS.PRICE, historicalPrice) // Фиксируем цену мертвой хваткой на диске
            .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING)
            .returning()
            .fetchOne();

        java.util.Objects.requireNonNull(record, "Database failed to persist the provisional appointment frame.");

        return Optional.of(new Appointment(
            record.getId(),
            record.getClientId(),
            record.getMasterId(),
            record.getServiceId(),
            record.getAppointmentTime(),
            record.getDurationMinutes(),
            record.getPrice(), // Возвращаем в модель
            record.getStatus(),
            record.getCreatedAt()
        ));
      });
    } catch (Exception ex) {
      throw translateException("Failed to execute transactional AI slot reservation safety loop", ex);
    }
  }

  @Override
  public void approveAppointment(Long appointmentId) {
    log.info("Бизнес-шаг: Утверждение записи хозяйкой салона [id: {}]", appointmentId);
    dslCtx.transaction(configuration -> configuration.dsl().update(APPOINTMENTS)
        .set(APPOINTMENTS.STATUS, AppointmentStatus.APPROVED)
        .where(APPOINTMENTS.ID.eq(appointmentId))
        .execute());
  }

  /**
   * Извлекает список мастеров, у которых есть хотя бы одна опубликованная смена на выбранную дату.
   */
  @Override
  public List<Master> getActiveMastersForDate(LocalDateTime date) {
    log.info("Database Step: Querying active masters working on date: [{}]", date);

    LocalDateTime startOfDay = date.toLocalDate().atStartOfDay();
    LocalDateTime endOfDay = date.toLocalDate().atTime(23, 59, 59);

    try {
      return dslCtx.selectDistinct(MASTERS.ID, MASTERS.FIRST_NAME, MASTERS.LAST_NAME, MASTERS.SPECIALIZATION)
          .from(MASTERS)
          .join(MASTER_SHIFTS)
          .on(MASTER_SHIFTS.MASTER_ID.eq(MASTERS.ID))
          .where(MASTER_SHIFTS.SHIFT_START.between(startOfDay, endOfDay))
          .fetch()
          .map(r -> new Master(
              r.get(MASTERS.ID),
              r.get(MASTERS.FIRST_NAME),
              r.get(MASTERS.LAST_NAME),
              r.get(MASTERS.SPECIALIZATION)
          ));
    } catch (Exception ex) {
      throw translateException("Failed to query working master profiles for targeted calendar date", ex);
    }
  }

  /**
   * ПРИВАТНЫЙ ХЕЛПЕР СЛОЯ ПЕРСИСТЕНТНОСТИ: Выполняет проверку внутри заданной транзакции.
   * Полностью инкапсулирует детали jOOQ (DSLContext) внутри модуля БД.
   */
  private boolean isMasterAvailableAtInternal(DSLContext txCtx, Long masterId, LocalDateTime time, int durationMinutes) {
    log.debug("Business Step: Computing isolated availability check for master [{}] at [{}] with a {}-min buffer",
        masterId, time, SANITARY_BUFFER_MINUTES);

    // Фактическое время окончания самой процедуры клиента
    LocalDateTime baseEndTime = time.plusMinutes(durationMinutes);
    // Время освобождения рабочего места с учетом санитарного перерыва
    LocalDateTime endTimeWithBuffer = time.plusMinutes(durationMinutes + SANITARY_BUFFER_MINUTES);

    // =====================================================================
    // ЭТАП А: Проверка коридора рабочей смены и пессимистический лок
    // =====================================================================
    // Master Shift:   [SHIFT_START]──────────────────────────────────────────────[SHIFT_END]
    // App Base Time:               [time]──────────────[baseEndTime]                        ==> ALLOWED (True)
    // App with Buffer:             [time]──────────────[baseEndTime]...[endTimeWithBuffer]  ==> ALLOWED (True)
    //
    // ВАЖНО: Сама процедура (baseEndTime) обязана полностью укладываться в смену.
    // Санитарный буфер уборки места (endTimeWithBuffer) может легитимно выходить за рамки смены.
    // =====================================================================
    var shiftRecord = txCtx.select(MASTER_SHIFTS.ID)
        .from(MASTER_SHIFTS)
        .where(MASTER_SHIFTS.MASTER_ID.eq(masterId))
        .and(MASTER_SHIFTS.SHIFT_START.le(time))
        .and(MASTER_SHIFTS.SHIFT_END.ge(baseEndTime))
        .forUpdate()
        .fetchOne();

    if (shiftRecord == null) {
      return false;
    }

    Long shiftId = shiftRecord.get(MASTER_SHIFTS.ID);

    // =====================================================================
    // ЭТАП Б: Проверка накладок на существующие визиты с учетом буфера
    // =====================================================================
    // Existing App:            [APPOINTMENT_TIME]────────[DURATION_MINUTES]...[+5 min Buffer]
    // New App Clash A:   [time]─────────────────────────[endTimeWithBuffer]                   ==> CLASH (False)
    // New App Clash B:                                 [time]────────────────[endTimeWithBuffer] ==> CLASH (False)
    // New App Clash C:         [time]────────────────────────────────────────[endTimeWithBuffer] ==> CLASH (False)
    // New App Safe Slot:                                                         [time]───────── ==> ALLOWED (True)
    // =====================================================================
    boolean hasClash = txCtx.fetchExists(
        txCtx.selectOne()
            .from(APPOINTMENTS)
            .where(APPOINTMENTS.MASTER_ID.eq(masterId))
            .and(APPOINTMENTS.STATUS.in(AppointmentStatus.APPROVED, AppointmentStatus.AI_PENDING))
            // Условие 1: Существующая запись началась раньше, чем закончится новая (с учетом буфера новой)
            .and(APPOINTMENTS.APPOINTMENT_TIME.lt(endTimeWithBuffer))
            // Условие 2: Существующая запись вместе со своим буфером закончится позже, чем начнется новая
            .and(localDateTimeAdd(
                APPOINTMENTS.APPOINTMENT_TIME,
                APPOINTMENTS.DURATION_MINUTES.add(SANITARY_BUFFER_MINUTES), // Внедряем константу в jOOQ выражение
                DatePart.MINUTE
            ).gt(time))
    );

    if (hasClash) {
      return false; // Слот занят другим клиентом или его технологическим буфером
    }

    // =====================================================================
    // ЭТАП В: Проверка накладок на личные/технические перерывы мастера
    // =====================================================================
    // Master Break:            [BREAK_START]──────────────────────────[BREAK_END]
    // New App Clash A:   [time]───────────────[baseEndTime]                                    ==> CLASH (False)
    // New App Clash B:                                [time]─────────────────[baseEndTime]     ==> CLASH (False)
    // New App Safe Slot:                                                          [time]────── ==> ALLOWED (True)
    //
    // ВАЖНО: Запись клиента не имеет права пересекаться с окнами отдыха мастера.
    // =====================================================================
    boolean hitsBreak = txCtx.fetchExists(
        txCtx.selectOne()
            .from(MASTER_SHIFT_BREAKS)
            .where(MASTER_SHIFT_BREAKS.SHIFT_ID.eq(shiftId))
            .and(MASTER_SHIFT_BREAKS.BREAK_START.lt(baseEndTime))
            .and(MASTER_SHIFT_BREAKS.BREAK_END.gt(time))
    );

    return !hitsBreak;
  }

  /**
   * Публичный интерфейсный метод для внешних модулей (ИИ, Веб).
   * Использует основной dsl-контекст подключения.
   */
  @Override
  public boolean isMasterAvailableAt(Long masterId, LocalDateTime time, int durationMinutes) {
    return isMasterAvailableAtInternal(this.dslCtx, masterId, time, durationMinutes);
  }
}
