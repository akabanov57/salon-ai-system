package salon.db.jooq.service;

import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;

import io.avaje.validation.constraints.Valid;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jooq.DSLContext;
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
  public Optional<Appointment> tryAiBooking(Long clientId, Long masterId, LocalDateTime appointmentTime, int durationMinutes) {
    log.info("Database Step: Attempting shift-aware provisional AI booking for master [{}] at [{}]", masterId, appointmentTime);

    try {
      return dslCtx.transactionResult(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // SUCCESS: Natively reuses our hidden internal private transactional helper!
        boolean isAvailable = isMasterAvailableAtInternal(txCtx, masterId, appointmentTime, durationMinutes);

        if (!isAvailable) {
          log.warn("Database Step: Rejection. Master [{}] is either not on shift or fully booked at [{}].", masterId, appointmentTime);
          return Optional.empty();
        }

        // =====================================================================
        // STEP 3: ATOMIC PROVISIONAL RESERVATION
        // =====================================================================
        // Completed Shift Timeline Frame Layout:
        // [SHIFT_START]─────────────────────────────────────────────────────────────[SHIFT_END]
        //               └───[Existing App]───┘               └───[New AI Reserved]───┘
        //
        var record = txCtx.insertInto(APPOINTMENTS)
            .set(APPOINTMENTS.CLIENT_ID, clientId)
            .set(APPOINTMENTS.MASTER_ID, masterId)
            .set(APPOINTMENTS.APPOINTMENT_TIME, appointmentTime)
            .set(APPOINTMENTS.DURATION_MINUTES, durationMinutes)
            .set(APPOINTMENTS.STATUS, "AI_PENDING")
            .returning()
            .fetchOne();

        Objects.requireNonNull(record, "Database failed to persist the provisional appointment frame.");

        return Optional.of(new Appointment(
            record.getId(),
            record.getClientId(),
            record.getMasterId(),
            record.getAppointmentTime(),
            record.getDurationMinutes(),
            salon.api.model.AppointmentStatus.valueOf(record.getStatus()),
            null,
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
        .set(APPOINTMENTS.STATUS, AppointmentStatus.APPROVED.name())
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
    log.debug("Business Step: Computing isolated availability check for master [{}] at [{}]", masterId, time);
    LocalDateTime endTime = time.plusMinutes(durationMinutes);

    // =====================================================================
    // STEP 1: WORK SHIFT CORRIDOR VALIDATION (Inside Transacted Context)
    // =====================================================================
    // Master Shift:   [SHIFT_START]───────────────────────────────[SHIFT_END]
    // Appointment:              [time]─────────────────[endTime]             ==> ALLOWED (True)
    //
    // Master Shift:               [SHIFT_START]───────────[SHIFT_END]
    // Appointment:   [time]──────────────────────────────────────[endTime]    ==> REJECTED (False)
    //
    boolean hasShift = txCtx.fetchExists(
        txCtx.selectFrom(MASTER_SHIFTS)
            .where(MASTER_SHIFTS.MASTER_ID.eq(masterId))
            .and(MASTER_SHIFTS.SHIFT_START.le(time))
            .and(MASTER_SHIFTS.SHIFT_END.ge(endTime))
    );

    if (!hasShift) {
      return false;
    }

    // =====================================================================
    // STEP 2: OVERLAP CONFLICT CHECK (Inside Transacted Context)
    // =====================================================================
    // Requested Slot:           [time]─────────────────────────────────[endTime]
    // Clash Case A:   [Existing Start]─────────────[Existing End]                    ==> Overlaps start
    // Clash Case B:                                [Existing Start]──────────[Existing End] ==> Overlaps end
    // Clash Case C:         [Existing Start]──────────────────────[Existing End]     ==> Swallows completely
    // Clash Case D:               [Existing Start]───────[Existing End]              ==> Sits inside completely
    //
    boolean hasClash = txCtx.fetchExists(
        txCtx.selectFrom(APPOINTMENTS)
            .where(APPOINTMENTS.MASTER_ID.eq(masterId))
            .and(APPOINTMENTS.STATUS.ne("CANCELED"))
            .and(APPOINTMENTS.APPOINTMENT_TIME.lt(endTime))
            .and(APPOINTMENTS.APPOINTMENT_TIME.add(APPOINTMENTS.DURATION_MINUTES.multiply(1)).gt(time))
    );

    return !hasClash;
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
