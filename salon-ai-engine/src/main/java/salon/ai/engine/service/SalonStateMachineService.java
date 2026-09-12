package salon.ai.engine.service;

import io.avaje.inject.External;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import salon.ai.engine.internal.service.DateTimeParser;
import salon.api.model.Appointment;
import salon.api.model.CatalogService;
import salon.api.model.DialogueContext;
import salon.api.model.DialogueResponse;
import salon.api.model.LlamaResponse;
import salon.api.model.Master;
import salon.api.service.BookingService;

/**
 * <h2>Центральное управляющее ядро детерминированной стейт-машины диалога</h2>
 * <p>
 * Данный сервис является главным оркестратором бизнес-правил, переходов состояний и валидации
 * данных (Slot-Filling) для ИИ-ассистента салона красоты. Он изолирует бизнес-логику от не
 * детерминированного поведения Большой Языковой Модели (LLM Llama3).
 * </p>
 *
 * <h3>Ключевые обязанности компонента:</h3>
 * <ul>
 *   <li><b>Greedy Slot-Filling (Жадное заполнение):</b> Агрегация и обновление слотов на основе
 *       каждого входящего NLP-пакета от LLM без потери ранее накопленных данных диалога.</li>
 *   <li><b>Fast-Track Override (Сквозное заполнение):</b> Мгновенный перевод диалога в финальную
 *       стадию подтверждения, если клиент предоставил все необходимые параметры в одной фразе.</li>
 *   <li><b>Анализ амбивалентности (CLARIFY_INTENT):</b> Введение промежуточных состояний-наводящих
 *       вопросов для разрешения неопределенности в ответах пользователей, исключая потерю контекста.</li>
 * </ul>
 *
 * <p>Все операции выполняются над иммутабельными структурами данных (Java Records), что гарантирует
 * потокобезопасность (Thread-Safety) и отсутствие побочных эффектов (Side-Effects).</p>
 *
 * @see DialogueContext
 * @see LlamaResponse
 * @see BookingService
 */
@Singleton
final class SalonStateMachineService {

  private final BookingService bookingService;
  private final DateTimeParser datetimeParser;

  /**
   * Конструктор для автоматического внедрения зависимостей через компиляционный фабричный слой
   * Avaje Inject.
   *
   * @param bookingService Входящий доменный порт (Use Case) для интеграции с расписанием салона из
   *                       {@code salon-api}.
   * @param datetimeParser Компонент разбора относительных текстовых дат естественного языка в
   *                       {@link LocalDateTime}.
   */
  @Inject
  SalonStateMachineService(@External BookingService bookingService, DateTimeParser datetimeParser) {
    this.bookingService = bookingService;
    this.datetimeParser = datetimeParser;
  }

  /**
   * <h3>Главный цикл обработки реплики и вычисления шага диалога</h3>
   * <p>
   * Метод принимает текущий снимок контекста разговора, сливает его с новыми распознанными слотами
   * от Llama3, инкрементирует внутренние счетчики ходов и вычисляет следующее детерминированное
   * состояние графа автомата.
   * </p>
   *
   * <h4>Алгоритм обработки шага:</h4>
   * <ol>
   *   <li>Проверка глобальной команды сброса контекста (интент {@code cancel_or_reset}).</li>
   *   <li>Жадный апдейт текущей сессии новыми не-null значениями от ИИ (Стрижка, Мастер, Дата).</li>
   *   <li>Проверка нахождения в состоянии {@code CLARIFY_INTENT} и обработка выбора наводящего вопроса.</li>
   *   <li>Оценка готовности к сквозному бронированию (Fast-Track) — если собраны все 3 сущности,
   *       система выполняет атомарную попытку резервирования через {@link BookingService#tryAiBooking}.</li>
   *   <li>Перехват неоднозначных реакций отказа (например, "в другой день") на этапе {@code CONFIRMATION_PENDING}.</li>
   *   <li>Маршрутизация в стандартную пошаговую цепочку (Fallback), если данных недостаточно.</li>
   * </ol>
   *
   * @param parsedData Объект-результат NLP-анализа от Llama3, содержащий текущий распознанный
   *                   интент и новые слоты. Не должен быть {@code null}.
   * @param context    Текущий иммутабельный снимок контекста сессии диалога, извлеченный из СУБД.
   *                   Не должен быть {@code null}.
   * @return Вычисленный {@link DialogueResponse}, содержащий обновленный иммутабельный рекордовый
   * контекст и готовое текстовое сообщение для отправки клиенту в мессенджер.
   */
  DialogueResponse processTurn(LlamaResponse parsedData, DialogueContext context) {
    DialogueContext currentContext = new DialogueContext(
        context.currentState(), context.slots().updateWith(parsedData.slots()), context.metadata().incrementTurn()
    );

    if ("cancel_or_reset".equals(parsedData.intent())) {
      return new DialogueResponse(DialogueContext.createNew(currentContext.metadata().userId()), "Запись отменена.");
    }

    // =================================================================================
    // ПЕРЕНЕСЕНО НАВЕРХ: Перехват размытого переноса времени на этапе подтверждения
    // =================================================================================
    if ("CONFIRMATION_PENDING".equals(currentContext.currentState()) && "change_datetime_ambiguous".equals(parsedData.intent())) {
//      log.info("AI Hub: Перехвачен интент размытого переноса записи для пользователя '{}'. Переход в CLARIFY_INTENT.", currentContext.metadata().userId());
      return new DialogueResponse(currentContext.withState("CLARIFY_INTENT"),
          "Вы хотите выбрать другую дату для этой записи или полностью отменить её?");
    }

    if ("CLARIFY_INTENT".equals(currentContext.currentState())) {
      return handleClarifyIntent(parsedData, currentContext);
    }

    DialogueContext.Slots slots = currentContext.slots();

    // Шаг 1: Глобальный защитный барьер амбивалентности категорий услуг с поддержкой авто-нормализации
    if (slots.service() != null && slots.confirmedDatetime() == null) {
      try {
        Optional<DialogueContext> normalizedContextOpt = checkServiceAmbiguity(currentContext);

        // ФИКС: Перезаписываем ссылку на контекст нормализованным слепком!
        if (normalizedContextOpt.isPresent()) {
          currentContext = normalizedContextOpt.get();
          slots = currentContext.slots(); // Синхронизируем локальные слоты для следующих шагов
        }
      } catch (AmbiguityExceptionWrapper ambiguityException) {
        // Если сработал жесткий блок (размер > 1), прерываем выполнение и возвращаем оффер вариантов
        return ambiguityException.getResponse();
      }
    }

    // Шаг 2: Предложение свободных мастеров (Сработает, только если стилист == null)
    if (slots.service() != null && slots.datetimeRaw() != null && slots.stylist() == null) {
      return handleMastersOffer(currentContext);
    }

    // Шаг 2.5: Точная услуга выбрана, но клиент забыл указать дату/время
    if (slots.service() != null && slots.datetimeRaw() == null && slots.stylist() == null) {
      return new DialogueResponse(currentContext.withState("AVAILABILITY_MATCH"),
          String.format("Отлично, услуга '%s' выбрана. На какой день и время вам подобрать свободные окна?", slots.service()));
    }

    // Шаг 3: Валидация смены мастера (Стилист гарантированно не null благодаря return выше)
    if ("STYLIST_PREFERENCE".equals(currentContext.currentState()) && !"ANY".equalsIgnoreCase(slots.stylist())) {
      Optional<DialogueResponse> errorResponse = validateMasterSchedule(currentContext);
      if (errorResponse.isPresent()) return errorResponse.get();
    }

    // Шаг 4: Атомарный Fast-Track захват слота времени
    if (slots.service() != null && slots.datetimeRaw() != null) {
      Optional<DialogueResponse> bookingResponse = tryFastTrackBooking(currentContext);
      if (bookingResponse.isPresent()) return bookingResponse.get();
    }

    return new DialogueResponse(currentContext, "Я вас понял. Продолжаем оформление записи...");
  }

  // =================================================================================
  // ПРИВАТНЫЕ СУБ-ОБРАБОТЧИКИ ДЛЯ ОПТИМИЗАЦИИ ОТОБРАЖЕНИЯ (SUB-HANDLERS)
  // =================================================================================

  private Optional<DialogueContext> checkServiceAmbiguity(DialogueContext ctx) {
    String rawServiceInput = ctx.slots().service();
    List<CatalogService> foundServices = bookingService.searchServicesInCatalog(rawServiceInput);

    if (foundServices.isEmpty()) {
      throw new AmbiguityExceptionWrapper(new DialogueResponse(ctx.withSlots(clearServiceSlot(ctx)).withState("SERVICE_SELECTION"),
          String.format("Услуга '%s' не найдена в каталоге. Какая именно процедура вас интересует?", rawServiceInput)));
    }
    if (foundServices.size() > 1) {
      String catalogOptionsStr = foundServices.stream().map(CatalogService::name).reduce((a, b) -> a + ", " + b).orElse("");
      throw new AmbiguityExceptionWrapper(new DialogueResponse(ctx.withSlots(clearServiceSlot(ctx)).withState("SERVICE_SELECTION"),
          String.format("У нас есть несколько видов этой услуги: %s. Уточните, какая именно процедура вам необходима?", catalogOptionsStr)));
    }

    CatalogService singleMatch = foundServices.getFirst();
    if (!singleMatch.name().equalsIgnoreCase(rawServiceInput.trim())) {
//      log.info("AI Hub: Успешная авто-нормализация. Изменено с '{}' на официальное '{}'", rawServiceInput, singleMatch.name());

      // ФИКС: Создаем и возвращаем измененный контекст наружу для перезаписи указателя
      DialogueContext.Slots normalizedSlots = ctx.slots().updateWith(new LlamaResponse.Slots(singleMatch.name(), null, null));
      return Optional.of(ctx.withSlots(normalizedSlots));
    }

    return Optional.empty();
  }

  private DialogueResponse handleClarifyIntent(LlamaResponse parsedData, DialogueContext ctx) {
    if ("choose_another_date".equals(parsedData.intent())) {
      DialogueContext.Slots clearedDates = new DialogueContext.Slots(ctx.slots().service(),
          ctx.slots().stylist(), null, null);
      return new DialogueResponse(ctx.withSlots(clearedDates).withState("AVAILABILITY_MATCH"),
          "На какую дату и время вам подобрать окна?");
    }
    return new DialogueResponse(DialogueContext.createNew(ctx.metadata().userId()),
        "Запись полностью отменена.");
  }

  private DialogueResponse handleMastersOffer(DialogueContext ctx) {
    Optional<LocalDateTime> parsedTimeOpt = datetimeParser.parseRaw(ctx.slots().datetimeRaw());
    if (parsedTimeOpt.isEmpty()) {
      return new DialogueResponse(ctx, "Уточните, пожалуйста, дату и время визита.");
    }

    CatalogService targetService = bookingService.searchServicesInCatalog(ctx.slots().service())
        .getFirst();
    LocalDate date = parsedTimeOpt.get().toLocalDate();
    LocalTime from = parsedTimeOpt.get().toLocalTime();

    List<Master> working = bookingService.getAvailableMastersForServiceInterval(
        targetService.name(), date, from, from.plusMinutes(targetService.durationMinutes()));
    if (working.isEmpty()) {
      return new DialogueResponse(ctx.withState("AVAILABILITY_MATCH"),
          "В этот интервал времени нет свободных мастеров для данной услуги.");
    }

    String listStr = working.stream().map(Master::alias).reduce((a, b) -> a + ", " + b).orElse("");
    return new DialogueResponse(ctx.withState("STYLIST_PREFERENCE"),
        String.format("На процедуру '%s' у нас свободны мастера: %s. Кто вам больше подходит?",
            targetService.name(), listStr));
  }

  private Optional<DialogueResponse> validateMasterSchedule(DialogueContext ctx) {
    Optional<LocalDateTime> parsedTimeOpt = datetimeParser.parseRaw(ctx.slots().datetimeRaw());
    if (parsedTimeOpt.isEmpty()) {
      return Optional.empty();
    }

    CatalogService targetService = bookingService.searchServicesInCatalog(ctx.slots().service())
        .getFirst();
    LocalDate date = parsedTimeOpt.get().toLocalDate();
    LocalTime from = parsedTimeOpt.get().toLocalTime();

    List<Master> working = bookingService.getAvailableMastersForServiceInterval(
        targetService.name(), date, from, from.plusMinutes(targetService.durationMinutes()));
    boolean ok = working.stream().anyMatch(m -> m.alias().equalsIgnoreCase(ctx.slots().stylist()));

    if (!ok) {
      DialogueContext.Slots rolled = new DialogueContext.Slots(targetService.name(), null,
          ctx.slots().datetimeRaw(), null);
      String listStr = working.stream().map(Master::alias).reduce((a, b) -> a + ", " + b)
          .orElse("нет мастеров");
      return Optional.of(new DialogueResponse(ctx.withSlots(rolled), String.format(
          "Мастер %s не работает в этот интервал. Свободны: %s. Выберите кого-то из них?",
          ctx.slots().stylist(), listStr)));
    }
    return Optional.empty();
  }

  private Optional<DialogueResponse> tryFastTrackBooking(DialogueContext ctx) {
    Optional<LocalDateTime> targetDateTimeOpt = datetimeParser.parseRaw(ctx.slots().datetimeRaw());
    if (targetDateTimeOpt.isEmpty()) {
      return Optional.empty();
    }

    LocalDateTime targetDateTime = targetDateTimeOpt.get();
    CatalogService targetService = bookingService.searchServicesInCatalog(ctx.slots().service())
        .getFirst();

    Optional<Appointment> app = bookingService.tryAiBooking(ctx.metadata().userId(),
        ctx.slots().stylist(), targetService.name(), targetDateTime);
    if (app.isPresent()) {
      DialogueContext.Slots confirmed = new DialogueContext.Slots(targetService.name(),
          ctx.slots().stylist(), ctx.slots().datetimeRaw(), app.get().appointmentTime().toString());
      return Optional.of(
          new DialogueResponse(ctx.withSlots(confirmed).withState("CONFIRMATION_PENDING"),
              String.format(
                  "Отлично! Я зарезервировал время %s к мастеру %s. Подтверждаете запись?",
                  targetDateTime.toLocalTime(), ctx.slots().stylist())));
    }

    return Optional.of(new DialogueResponse(ctx.withState("AVAILABILITY_MATCH"),
        String.format("Извините, время %s у мастера %s уже занято. Можем подобрать другое время?",
            targetDateTime.toLocalTime(), ctx.slots().stylist())));
  }

  private DialogueContext.Slots clearServiceSlot(DialogueContext ctx) {
    return new DialogueContext.Slots(null, ctx.slots().stylist(), ctx.slots().datetimeRaw(), null);
  }

  /**
   * <h3>Вычисление пошаговых детерминированных переходов (Fallback-цепочка)</h3>
   * <p>
   * Метод вызывается как резервный (Fallback), если условия для сквозного заполнения слотов не
   * выполнены. Он анализирует, какого конкретно слота не хватает (Услуга -> Мастер -> Дата), и
   * формирует точечный атомарный вопрос для ведения клиента по классической воронке записи.
   * </p>
   *
   * @param context Текущее иммутабельное состояние сессии разговора с частично заполненными
   *                слотами. Не должно быть {@code null}.
   * @return Результирующий ответ с инструкцией для пользователя по заполнению следующего целевого
   * слота.
   */
  private DialogueResponse evaluateStandardTransitions(DialogueContext context) {
    return new DialogueResponse(context, "Продолжаем заполнение параметров записи...");
  }

  /**
   * Легковесное внутреннее исключение-обертка для управляемого прерывания
   * выполнения графа при обнаружении множественной амбивалентности.
   */
  private static class AmbiguityExceptionWrapper extends RuntimeException {
    private final DialogueResponse response;
    public AmbiguityExceptionWrapper(DialogueResponse response) { this.response = response; }
    public DialogueResponse getResponse() { return response; }
  }
}
