package salon.db.jooq.service;

import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
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

  private Client txIdOrCreateTelegramClientInternal(DSLContext txCtx, String telegramId, String firstName) {
    log.info("Business Step: Identifying TG client [{}] inside transactional bounds", telegramId);

    // FIX: Enforce a safe business fallback handle if the messaging platform hides the user's name
    // TODO Поддержка языка.
    final String resolvedName = (firstName == null || firstName.isBlank()) ? "Guest" : firstName;

    // Natively stream the mapping conversion or execute the insert fallback block cleanly
    return txCtx.selectFrom(CLIENTS)
        .where(CLIENTS.TELEGRAM_ID.eq(telegramId))
        .fetchOptional()
        .map(r -> new Client(
            r.getId(), r.getFirstName(), r.getLastName(), r.getPhone(),
            r.getTelegramId(), r.getInstagramId(), r.getBonusBalance(), r.getCreatedAt()
        ))
        .orElseGet(() -> {
          // FALLBACK PHASE: Triggers seamlessly only if the optional wrapper is empty!
          log.info("New client discovered. Generating profile for {}", firstName);
          var newRecord = txCtx.insertInto(CLIENTS)
              .set(CLIENTS.FIRST_NAME, resolvedName)
              .set(CLIENTS.TELEGRAM_ID, telegramId)
              .returning()
              .fetchOne();

          Objects.requireNonNull(newRecord, "Database failed to return the newly inserted client record.");

          return new Client(
              newRecord.getId(), newRecord.getFirstName(), newRecord.getLastName(),
              newRecord.getPhone(), newRecord.getTelegramId(), newRecord.getInstagramId(),
              newRecord.getBonusBalance(), newRecord.getCreatedAt()
          );
        });
  }

  @Override
  public void processMessage(@Valid ProcessMessageCommand command) {
    log.info("[Domain Use-Case] Начат цикл обработки обращения для платформы {} (ID: {})",
        command.platformType(), command.platformId());

    try {
      dslCtx.transaction(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // Идентифицируем клиента
        Client client = txIdOrCreateTelegramClientInternal(txCtx, command.platformId(), command.firstName());

        // Прямая атомарная вставка лога
        txCtx.insertInto(MESSAGE_TRACES)
            .set(MESSAGE_TRACES.TRACE_ID, command.traceId())
            .set(MESSAGE_TRACES.PLATFORM_TYPE, command.platformType().name())
            .set(MESSAGE_TRACES.PLATFORM_ID, command.platformId())
            .set(MESSAGE_TRACES.DIRECTION, "INBOUND")
            .set(MESSAGE_TRACES.MESSAGE_TEXT, command.messageText())
            .set(MESSAGE_TRACES.CLIENT_ID, client.id())
            .execute();
      });
    } catch (Exception ex) {
      // ...но этот блок моментально ПЕРЕХВАТИТ этот NPE!
      // И превратит его в чистое доменное исключение, скрыв технический мусор.
      throw translateException("Failed to identify or create Telegram client profile", ex);
    }
  }

  @Override
  public List<Master> getAvailableStylists() {
    log.debug("Запрос списка активных мастеров");
    return dslCtx.selectFrom(MASTERS)
        .where(MASTERS.IS_ACTIVE.eq(true))
        .fetch()
        .map(r -> new Master(r.getId(), r.getFirstName(), r.getLastName(), r.getSpecialization(), r.getIsActive()));
  }

  @Override
  public Optional<Appointment> tryAiBooking(Long clientId, Long masterId, LocalDateTime time, int durationMinutes) {
    log.info("Business Step: Attempting AI slot reservation for master {} starting at {}", masterId, time);

    try {
      LocalDateTime endTime = time.plusMinutes(durationMinutes);
      // Orchestrate the query logic inside an atomic, thread-safe jOOQ transaction pipeline
      return dslCtx.transactionResult(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // 1. Check for time-slot overlap using the mathematical grid allocation logic:
        // (RequestedStart < ExistingEnd) AND (RequestedEnd > ExistingStart)
        boolean overlapExists = txCtx.fetchExists(
            txCtx.selectFrom(APPOINTMENTS)
                .where(APPOINTMENTS.MASTER_ID.eq(masterId))
                .and(APPOINTMENTS.STATUS.ne(AppointmentStatus.CANCELED.name()))
                .and(APPOINTMENTS.APPOINTMENT_TIME.lt(endTime))
                // Computes column multiplications directly inside the native query engine
                .and(APPOINTMENTS.APPOINTMENT_TIME.plus(APPOINTMENTS.DURATION_MINUTES.mul(60)).gt(time))
        );

        if (overlapExists) {
          log.warn("Aborting reservation pipeline: Time slot conflict detected for master {} at {}", masterId, time);
          return Optional.empty();
        }

        // 2. The slot is vacant — perform the insert execution block
        var newAppRecord = txCtx.insertInto(APPOINTMENTS)
            .set(APPOINTMENTS.CLIENT_ID, clientId)
            .set(APPOINTMENTS.MASTER_ID, masterId)
            .set(APPOINTMENTS.APPOINTMENT_TIME, time)
            .set(APPOINTMENTS.DURATION_MINUTES, durationMinutes)
            .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING.name())
            .returning() // Auto-hydrates database constraints like generated IDs and timestamps
            .fetchOne();

        // Clean Fail-Fast check: Intercept empty return patterns to protect against silent failures
        Objects.requireNonNull(newAppRecord, "Database state failure: Returned a null row during appointment insertion allocation.");

        // 3. Map safely to our clean, immutable domain Record to shield underlying tables
        Appointment appointment = new Appointment(
            newAppRecord.getId(),
            newAppRecord.getClientId(),
            newAppRecord.getMasterId(),
            newAppRecord.getAppointmentTime(),
            newAppRecord.getDurationMinutes(),
            AppointmentStatus.valueOf(newAppRecord.getStatus()),
            newAppRecord.getPrice(),
            newAppRecord.getCreatedAt()
        );

        log.info("Successfully reserved pending appointment slot. Generated Ticket ID: {}", appointment.id());
        return Optional.of(appointment);
      });
    } catch (Exception ex) {
      // Catch both jOOQ SQL failures and internal NullPointerExceptions, then map to domain space
      throw translateException("Failed to complete conversational AI appointment scheduling flow", ex);
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
}
