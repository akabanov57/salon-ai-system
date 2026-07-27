package salon.db.jooq.service;

import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.MASTERS;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.Client;
import salon.api.model.Master;
import salon.api.service.BookingService;

@Singleton
final class BookingServiceImpl implements BookingService {

  private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);

  private final DSLContext ctx;

  // Внедряем ТОЛЬКО DSLContext. Никаких промежуточных репозиториев!
  @Inject
  public BookingServiceImpl(DSLContext ctx) {
    this.ctx = ctx;
  }

  private RuntimeException translateException(String contextMessage, Exception ex) {
    log.error("Infrastructure trapped failure details: {}", ex.getMessage(), ex);

    // 1. Проверяем на специфичное для jOOQ нарушение ограничений уникальности (Natural Keys)
    if (ex instanceof IntegrityConstraintViolationException) {
      return new IntegrityViolationException(contextMessage + ": Business data integrity restriction violated.", ex);
    }

    // 2. Дополнительная защита на случай, если драйвер базы данных пробросил кастомный SQLState
    if (ex.getMessage() != null && ex.getMessage().contains("uk_")) {
      return new IntegrityViolationException(contextMessage + ": Unique natural key constraint violation.", ex);
    }

    if (ex.getCause() != null && ex.getCause().getMessage() != null && ex.getCause().getMessage().contains("uk_")) {
      return new IntegrityViolationException(contextMessage + ": Underlying structural unique constraint violation.", ex);
    }

    // 3. Во всех остальных случаях (NPE, падение коннекта к Postgres, тайм-аут) возвращаем общую ошибку хранилища
    return new StorageInfrastructureException(contextMessage + ": Internal data storage layer error encountered.", ex);
  }

  @Override
  public Client identifyOrCreateTelegramClient(String telegramId, String firstName) {
    log.info("Business Step: Identifying TG client [{}]", telegramId);
    try {
      return ctx.transactionResult(configuration -> {
        DSLContext txCtx = configuration.dsl();

        var optionalRecord = txCtx.selectFrom(CLIENTS)
            .where(CLIENTS.TELEGRAM_ID.eq(telegramId))
            .fetchOptional();

        if (optionalRecord.isPresent()) {
          var r = optionalRecord.get();
          return new Client(r.getId(), r.getFirstName(), r.getLastName(), r.getPhone(), r.getTelegramId(), r.getInstagramId(), r.getBonusBalance(), r.getCreatedAt());
        }

        log.info("New client discovered. Generating profile for {}", firstName);
        var newRecord = txCtx.insertInto(CLIENTS)
            .set(CLIENTS.FIRST_NAME, firstName)
            .set(CLIENTS.TELEGRAM_ID, telegramId)
            .returning()
            .fetchOne();

        // Если база вернула null, метод выбросит NPE с сообщением...
        Objects.requireNonNull(newRecord, "Database failed to return the newly inserted client record.");

        return new Client(
            newRecord.getId(), newRecord.getFirstName(), newRecord.getLastName(),
            newRecord.getPhone(), newRecord.getTelegramId(), newRecord.getInstagramId(),
            newRecord.getBonusBalance(), newRecord.getCreatedAt()
        );
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
    return ctx.selectFrom(MASTERS)
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
      return ctx.transactionResult(configuration -> {
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
    ctx.transaction(configuration -> configuration.dsl().update(APPOINTMENTS)
        .set(APPOINTMENTS.STATUS, AppointmentStatus.APPROVED.name())
        .where(APPOINTMENTS.ID.eq(appointmentId))
        .execute());
  }
}
