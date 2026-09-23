package salon.db.jooq.service;

import static org.jooq.impl.DSL.localDateTimeAdd;
import static org.jooq.impl.DSL.upper;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;
import static salon.db.jooq.generated.Tables.CLIENTS;
import static salon.db.jooq.generated.Tables.INBOUND_EVENTS;
import static salon.db.jooq.generated.Tables.MASTERS;
import static salon.db.jooq.generated.Tables.MASTER_SERVICES;
import static salon.db.jooq.generated.Tables.MASTER_SHIFTS;
import static salon.db.jooq.generated.Tables.MASTER_SHIFT_BREAKS;
import static salon.db.jooq.generated.Tables.MESSAGE_TRACES;
import static salon.db.jooq.generated.Tables.SERVICES;

import io.avaje.validation.constraints.Valid;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.DatePart;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.MasterUnavailableException;
import salon.api.exception.MessageIdempotencyException;
import salon.api.exception.ServiceNotFoundException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.CatalogService;
import salon.api.model.Master;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.BookingService;

/**
 * <h3>Реализация инфраструктурного порта управления процессами бронирования слотов</h3>
 *
 * <p>Инкапсулирует в себе всю работу с реляционными таблицами СУБД через jOOQ fluent API.
 * Реализует паттерн транзакционного кросс-маппинга для бесшовного перевода строковых бизнес-ключей
 * ИИ-агента во внутренние числовые суррогатные идентификаторы СУБД внутри единой изолированной транзакции.</p>
 */
@Singleton
final class BookingServiceImpl implements BookingService {

  private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);

  // FIX: Явно выделяем санитарный зазор между записями клиентов как константу класса
  private static final int SANITARY_BUFFER_MINUTES = 5;

  /** Форматтер даты для генерации читаемых кодов билетов */
  private static final DateTimeFormatter DATE_TOKEN_FORMATTER = DateTimeFormatter.ofPattern("yyMMdd");

  private final DSLContext dslCtx;

  // Внедряем ТОЛЬКО DSLContext. Никаких промежуточных репозиториев!
  @Inject
  public BookingServiceImpl(DSLContext dslCtx) {
    this.dslCtx = dslCtx;
  }

  /**
   * Преобразует системные ошибки jOOQ в чистые доменные исключения согласно текущей редакции проекта.
   */
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
   * [Шаг А] Внутренний транзакционный хелпер: Находит существующего или регистрирует нового клиента.
   * Возвращает внутренний Long ID СУБД напрямую, не выпуская его за границы приватных методов слоев [Strict Grounding].
   */
  private Long txIdOrCreateClientInternal(
      DSLContext tx, PlatformType platformType, String platformId, String displayName) {

    // Барьер нормализации имени для защиты ограничений NOT NULL в таблице CLIENTS
    final String normalizedName = (displayName == null || displayName.trim().isEmpty())
        ? "Клиент Салона"
        : displayName.trim();

    // Функциональная цепочка возвращает только ID
    return tx.select(CLIENTS.ID)
        .from(CLIENTS)
        .where(CLIENTS.PLATFORM_TYPE.eq(platformType.name()))
        .and(CLIENTS.PLATFORM_ID.eq(platformId))
        .fetchOptional(CLIENTS.ID) // Точечно извлекаем Optional<Long> вместо всей строки
        .orElseGet(() -> {
          log.info("[Functional Registration] Creating client record with database ID generation for: [{}]", platformId);

          return tx.insertInto(CLIENTS)
              .set(CLIENTS.PLATFORM_TYPE, platformType.name())
              .set(CLIENTS.PLATFORM_ID, platformId)
              .set(CLIENTS.DISPLAY_NAME, normalizedName)
              .returning(CLIENTS.ID)
              .fetchOne(CLIENTS.ID); // Атомарно возвращаем сгенерированный BIGSERIAL
        });
  }

  @Override
  public void processMessage(@Valid ProcessMessageCommand command) {
    log.debug("[Domain Use-Case] Начат цикл обработки обращения для платформы {} (ID: {}, MsgID: {})",
        command.platformType(), command.platformId(), command.messengerMessageId());

    try {
      dslCtx.transaction(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // 1. Атомарно захватываем уникальный сетевой замок в таблице INBOUND_EVENTS
        try {
          txCtx.insertInto(INBOUND_EVENTS)
              .set(INBOUND_EVENTS.PLATFORM_TYPE, command.platformType().name())
              .set(INBOUND_EVENTS.MESSENGER_MESSAGE_ID, command.messengerMessageId())
              .execute();
        } catch (IntegrityConstraintViolationException ex) {
          // jOOQ гарантирует, что любая ошибка дубликата ключа (23505) попадет именно сюда
          throw new MessageIdempotencyException("Обнаружен повторный пакет сообщения [Message ID: " + command.messengerMessageId() + "]", ex);
        }

        // 2. Идентифицируем или создаем клиента
        Long surrogateClientId = txIdOrCreateClientInternal(
            txCtx, command.platformType(), command.platformId(), command.displayName()
        );

        // 3. Выполняем прямую вставку входящего лога
        txCtx.insertInto(MESSAGE_TRACES)
            .set(MESSAGE_TRACES.TRACE_ID, command.traceId())
            .set(MESSAGE_TRACES.DIRECTION, "INBOUND")
            .set(MESSAGE_TRACES.MESSAGE_TEXT, command.messageText())
            .set(MESSAGE_TRACES.CLIENT_ID, surrogateClientId)
            .execute();

        log.debug("[Domain Use-Case] Входящий лог транзакции {} успешно сохранен.", command.traceId());
      });
    } catch (MessageIdempotencyException ex) {
      throw ex; // Пробрасываем бизнес-исключение идемпотентности без изменений
    } catch (Exception ex) {
      throw translateException("Failed to identify or create multi-channel client profile", ex);
    }
  }

  @Override
  public Appointment tryAiBooking(String platformId, String masterAlias,
      String serviceName, LocalDateTime appointmentTime) {

    log.debug(
        "[Persistence Layer] Initiating natural key transacted allocation for Client[{}], Master[{}], Service[{}]",
        platformId, masterAlias, serviceName);

    try {
      return dslCtx.transactionResult(configuration -> {
        DSLContext txCtx = configuration.dsl();

        // ШАГ 1: АТОМАРНЫЙ КРОСС-МАППИНГ СТРОК В ID ЗА ОДИН ПРОХОД.
        // Извлекаем скрытые первичные ключи всех трех родительских таблиц одновременно, исключая паразитные JOIN-запросы
        final var ctxRecord = txCtx.select(
                CLIENTS.ID.as("SUB_CLIENT_ID"),
                MASTERS.ID.as("SUB_MASTER_ID"),
                SERVICES.ID.as("SUB_SERVICE_ID"),
                SERVICES.DURATION_MINUTES,
                SERVICES.PRICE
            )
            .from(CLIENTS)
            .crossJoin(MASTERS)
            .crossJoin(SERVICES)
            .where(CLIENTS.PLATFORM_ID.eq(platformId))
            .and(MASTERS.ALIAS.eq(masterAlias.trim().toLowerCase()))
            .and(upper(SERVICES.NAME).eq(serviceName.trim().toUpperCase()))
            .fetchOne();

        // Защитный барьер: если хотя бы одна бизнес-строка не найдена в таблицах — ИИ мгновенно получает отказ
        if (ctxRecord == null) {
          log.warn("[Key Resolution Mismatch] Transacted cross-mapping failed. Natural strings do not correspond to database entries.");
          throw new ServiceNotFoundException("Услуга или мастер не найдены в каталоге салона.");
        }

        final Long surrogateClientId = ctxRecord.get("SUB_CLIENT_ID", Long.class);
        final Long surrogateMasterId = ctxRecord.get("SUB_MASTER_ID", Long.class);
        final Long surrogateServiceId = ctxRecord.get("SUB_SERVICE_ID", Long.class);

        final int durationMinutes = ctxRecord.get(SERVICES.DURATION_MINUTES);
        final BigDecimal historicalPrice = ctxRecord.get(SERVICES.PRICE);

        // ШАГ 2: ЗАПУСК 4-ЭТАПНОГО КОМПЛАЕНС-ФИЛЬТРА ПО СКРЫТЫМ ID
        final boolean isAvailable = isMasterAvailableAtInternal(txCtx, surrogateMasterId, surrogateServiceId, appointmentTime, durationMinutes);

        if (!isAvailable) {
          log.warn("[Booking Aborted] Target slot criteria checks failed for master alias [{}] at time [{}]", masterAlias, appointmentTime);
          throw new MasterUnavailableException("Выбранное время у этого мастера уже занято.");
        }

        // ШАГ 3: ГЕНЕРАЦИЯ ПУБЛИЧНОГО БИЗНЕС-КОДА БИЛЕТА (Вариант А)
        final String generatedTicketCode = generateUniqueTicketCode(appointmentTime);

        // ШАГ 4: АТОМАРНАЯ ЗАПИСЬ СЕССИИ В APPOINTMENTS С СОХРАНЕНИЕМ ИСТОРИЧЕСКИХ ДАННЫХ
        final var record = txCtx.insertInto(APPOINTMENTS)
            .set(APPOINTMENTS.TICKET_CODE, generatedTicketCode)
            .set(APPOINTMENTS.CLIENT_ID, surrogateClientId)
            .set(APPOINTMENTS.MASTER_ID, surrogateMasterId)
            .set(APPOINTMENTS.SERVICE_ID, surrogateServiceId)
            .set(APPOINTMENTS.APPOINTMENT_TIME, appointmentTime)
            .set(APPOINTMENTS.DURATION_MINUTES, durationMinutes)
            .set(APPOINTMENTS.PRICE, historicalPrice) // Фиксируем стоимость мертвой хваткой на дату сделки
            .set(APPOINTMENTS.STATUS, AppointmentStatus.AI_PENDING)
            .returning()
            .fetchOne();

        //  ЯВНАЯ ДЕТЕРМИНИРОВАННАЯ ОБРАБОТКА NULL БЕЗ ИСПОЛЬЗОВАНИЯ NULL-POINTER-EXCEPTION
        if (record == null) {
          log.error("[Database Write Failure] Insert operation completed, but the database returned an empty record proxy for Ticket [{}]", generatedTicketCode);
          throw new StorageInfrastructureException("Сбой инфраструктуры СУБД: не удалось зафиксировать талон бронирования в базе данных.");
        }

        // Возвращаем наверх чистую доменную сущность, собранную исключительно на бизнес-ключах
        return new Appointment(
            record.getTicketCode(),
            platformId,
            masterAlias,
            serviceName,
            record.getAppointmentTime(),
            record.getDurationMinutes(),
            record.getPrice(),
            record.getStatus(),
            record.getCreatedAt()
        );
      });
    } catch (ServiceNotFoundException | MasterUnavailableException ex) {
      throw ex; // Пробрасываем чистые доменные ошибки бизнес-логики без изменений
    } catch (Exception ex) {
      throw translateException("Critical failure wrapped inside natural-key allocation sequence processing", ex);
    }
  }

  /**
   * Извлекает список мастеров, у которых есть хотя бы одна опубликованная смена на выбранную дату.
   */
  @Override
  public List<Master> getActiveMastersForDate(LocalDateTime date) {
    log.debug("[Persistence Layer] Fetching active master roster for target date: [{}]", date);

    try {
      return dslCtx.select(
              MASTERS.ALIAS, // FIX: Select the string ALIAS field to match the new Master domain specification
              MASTERS.FIRST_NAME,
              MASTERS.LAST_NAME,
              MASTERS.SPECIALIZATION
          )
          .from(MASTERS)
          // Look up active masters who have a valid published work shift covering the target timeline
          .join(MASTER_SHIFTS).on(MASTER_SHIFTS.MASTER_ID.eq(MASTERS.ID))
          .where(MASTER_SHIFTS.SHIFT_START.le(date))
          .and(MASTER_SHIFTS.SHIFT_END.ge(date))
          .fetch()
          .stream()
          .map(r -> new Master(
              r.get(MASTERS.ALIAS), // FIX: Read the exact String alias token to pass validation bounds cleanly
              r.get(MASTERS.FIRST_NAME),
              r.get(MASTERS.LAST_NAME),
              r.get(MASTERS.SPECIALIZATION)
          ))
          .toList();

    } catch (Exception ex) {
      throw translateException("Failed to retrieve active master roster profiles for the specified date timeline", ex);
    }
  }

  /**
   * ПРИВАТНЫЙ ХЕЛПЕР СЛОЯ ПЕРСИСТЕНТНОСТИ: Выполняет проверку внутри заданной транзакции.
   * Полностью инкапсулирует детали jOOQ (DSLContext) внутри модуля БД.
   */
  @SuppressWarnings("GrazieStyle")
  private boolean isMasterAvailableAtInternal(DSLContext txCtx, Long masterId, Long serviceId, LocalDateTime time, int durationMinutes) {
    log.debug(
        "Business Step: Computing isolated availability check for master [{}] at [{}] with a {}-min buffer",
        masterId, time, SANITARY_BUFFER_MINUTES);

    // =====================================================================
    // ЭТАП 0: Проверка матрицы компетенций (Умеет ли мастер делать эту услугу?)
    // =====================================================================
    // MASTER_SERVICES Matrix:  [MASTER_ID: 1, SERVICE_ID: 55] (Окрашивание) -> ALLOWED (True)
    // Requested Target:        [MASTER_ID: 1, SERVICE_ID: 99] (Маникюр)     -> REJECTED (False)
    // =====================================================================
    final boolean hasCompetence = txCtx.fetchExists(
        txCtx.selectOne()
            .from(MASTER_SERVICES)
            .where(MASTER_SERVICES.MASTER_ID.eq(masterId))
            .and(MASTER_SERVICES.SERVICE_ID.eq(serviceId))
    );

    if (!hasCompetence) {
      log.warn("Business Step: Rejection. Master [{}] does not have specialization for service [{}].", masterId, serviceId);
      return false; // Защитный барьер: мастер физически не умеет выполнять эту процедуру!
    }

    // Фактическое время окончания самой процедуры клиента
    final LocalDateTime baseEndTime = time.plusMinutes(durationMinutes);
    // Время освобождения рабочего места с учетом санитарного перерыва
    final LocalDateTime endTimeWithBuffer = time.plusMinutes(durationMinutes + SANITARY_BUFFER_MINUTES);

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
    final var shiftRecord = txCtx.select(MASTER_SHIFTS.ID)
        .from(MASTER_SHIFTS)
        .where(MASTER_SHIFTS.MASTER_ID.eq(masterId))
        .and(MASTER_SHIFTS.SHIFT_START.le(time))
        .and(MASTER_SHIFTS.SHIFT_END.ge(baseEndTime))
        .forUpdate()
        .fetchOne();

    if (shiftRecord == null) {
      return false;
    }

    final Long shiftId = shiftRecord.get(MASTER_SHIFTS.ID);

    // =====================================================================
    // ЭТАП Б: Проверка накладок на существующие визиты с учетом буфера
    // =====================================================================
    // Existing App:            [APPOINTMENT_TIME]────────[DURATION_MINUTES]...[+5 min Buffer]
    // New App Clash A:   [time]─────────────────────────[endTimeWithBuffer]                   ==> CLASH (False)
    // New App Clash B:                                 [time]────────────────[endTimeWithBuffer] ==> CLASH (False)
    // New App Clash C:         [time]────────────────────────────────────────[endTimeWithBuffer] ==> CLASH (False)
    // New App Safe Slot:                                                         [time]───────── ==> ALLOWED (True)
    // =====================================================================
    final boolean hasClash = txCtx.fetchExists(
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
      log.debug("[Stage B Reject] Requested slot collides with another client record or its sanitary buffer zone");
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
    final boolean hitsBreak = txCtx.fetchExists(
        txCtx.selectOne()
            .from(MASTER_SHIFT_BREAKS)
            .where(MASTER_SHIFT_BREAKS.SHIFT_ID.eq(shiftId))
            .and(MASTER_SHIFT_BREAKS.BREAK_START.lt(baseEndTime))
            .and(MASTER_SHIFT_BREAKS.BREAK_END.gt(time))
    );

    if (hitsBreak) {
      log.debug("[Stage C Reject] Target slot overlaps with an allocated master break window");
      return false;
    }

    return true; // Все защитные барьеры успешно пройдены!
  }

  /**
   * Публичный интерфейсный метод для внешних модулей (ИИ, Веб).
   * Использует основной dsl-контекст подключения.
   */
  @Override
  public boolean isMasterAvailableAt(Long masterId, Long serviceId, LocalDateTime time, int durationMinutes) {
    return isMasterAvailableAtInternal(this.dslCtx, masterId, serviceId, time, durationMinutes);
  }

  @Override
  public List<CatalogService> searchServicesInCatalog(String keyword) {
    log.debug(
        "[Persistence Layer] Executing text-search matching filter inside services catalog for: [{}]",
        keyword);

    try {
      String pattern = "%" + keyword.trim().toUpperCase() + "%";

      return dslCtx.select(SERVICES.NAME, SERVICES.DURATION_MINUTES, SERVICES.PRICE)
          .from(SERVICES)
          .where(org.jooq.impl.DSL.upper(SERVICES.NAME).like(pattern))
          .fetch()
          .stream()
          .map(r -> new CatalogService(
              r.get(SERVICES.NAME),
              r.get(SERVICES.DURATION_MINUTES),
              r.get(SERVICES.PRICE)
          ))
          .toList();
    } catch (Exception ex) {
      throw translateException("Failed to query database services catalogue by keyword", ex);
    }
  }

  @Override
  public List<Master> getAvailableMastersForServiceInterval(
      String serviceName,
      LocalDate date,
      LocalTime timeFrom,
      LocalTime timeTo
  ) {
    log.debug("Запуск аналитического подбора мастеров для услуги '{}' на дату {} в интервале {}-{}",
        serviceName, date, timeFrom, timeTo);

    // Защитный заслон: Валидация входных параметров (Preconditions)
    if (serviceName == null || serviceName.isBlank()) {
      throw new IllegalArgumentException("Наименование услуги для подбора мастеров не может быть пустым.");
    }
    Objects.requireNonNull(date, "Целевая дата визита не может быть null.");
    Objects.requireNonNull(timeFrom, "Нижняя граница времени (timeFrom) не может быть null.");
    Objects.requireNonNull(timeTo, "Верхняя граница времени (timeTo) не может быть null.");

    if (!timeTo.isAfter(timeFrom)) {
      throw new IllegalArgumentException(String.format(
          "Некорректный интервал времени: верхняя граница (%s) должна быть строго позже нижней (%s).", timeTo, timeFrom));
    }

    final LocalDateTime windowStart = LocalDateTime.of(date, timeFrom);
    final LocalDateTime windowEnd = LocalDateTime.of(date, timeTo);

    try {
      // Выбираем только те поля, которые необходимы для конструирования рекорда Master
      return dslCtx.select(MASTERS.ALIAS, MASTERS.FIRST_NAME, MASTERS.LAST_NAME,
              MASTERS.SPECIALIZATION)
          .from(MASTERS)
          .join(MASTER_SERVICES).on(MASTER_SERVICES.MASTER_ID.eq(MASTERS.ID))
          .join(SERVICES).on(SERVICES.ID.eq(MASTER_SERVICES.SERVICE_ID))
          .join(MASTER_SHIFTS).on(MASTER_SHIFTS.MASTER_ID.eq(MASTERS.ID))
          .where(SERVICES.NAME.likeIgnoreCase("%" + serviceName + "%"))
          .and(MASTER_SHIFTS.SHIFT_START.le(windowStart))
          .and(MASTER_SHIFTS.SHIFT_END.ge(windowEnd))
          .and(DSL.exists(
              dslCtx.selectOne().where(
                  DSL.notExists(
                          dslCtx.selectOne()
                              .from(MASTER_SHIFT_BREAKS)
                              .where(MASTER_SHIFT_BREAKS.SHIFT_ID.eq(MASTER_SHIFTS.ID))
                              .and(MASTER_SHIFT_BREAKS.BREAK_START.lt(windowEnd))
                              .and(MASTER_SHIFT_BREAKS.BREAK_END.gt(windowStart))
                      )
                      .and(DSL.notExists(
                          dslCtx.selectOne()
                              .from(APPOINTMENTS)
                              .where(APPOINTMENTS.MASTER_ID.eq(MASTERS.ID))
                              .and(APPOINTMENTS.STATUS.in(AppointmentStatus.APPROVED,
                                  AppointmentStatus.AI_PENDING))
                              .and(APPOINTMENTS.APPOINTMENT_TIME.lt(windowEnd))
                              .and(APPOINTMENTS.APPOINTMENT_TIME.add(
                                  APPOINTMENTS.DURATION_MINUTES.mul(60)).gt(windowStart))
                      ))
              )
          ))
          // ИСПРАВЛЕНО: Маппинг полей jOOQ Record напрямую в ваш Java Record Master
          .fetch(record -> new Master(
              record.get(MASTERS.ALIAS),
              record.get(MASTERS.FIRST_NAME),
              record.get(MASTERS.LAST_NAME),
              record.get(MASTERS.SPECIALIZATION)
          ));
    } catch (DataAccessException e) {
      // Надежно логируем инфраструктурный сбой с контекстом запроса для быстрой отладки
      log.error("Критический сбой СУБД при подборе квалифицированных мастеров для услуги '{}' в интервале {}-{}",
          serviceName, windowStart, windowEnd, e);

      // Маппим техническую ошибку jOOQ в контролируемое доменное исключение хранения
      throw new StorageInfrastructureException(
          "Не удалось выполнить подбор доступных специалистов из-за внутренней ошибки слоя хранения", e);
    }
  }

  /**
   * Генерирует уникальный, читаемый бизнес-код билета по шаблону: SB-YYMMDD-[КОРОТКИЙ ХЕШ]
   */
  private String generateUniqueTicketCode(LocalDateTime time) {
    String datePart = time.format(DATE_TOKEN_FORMATTER);
    String randomPart = UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    return String.format("SB-%s-%s", datePart, randomPart);
  }

}
