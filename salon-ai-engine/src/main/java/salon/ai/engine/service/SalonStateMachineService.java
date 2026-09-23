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
import salon.api.exception.MasterUnavailableException;
import salon.api.exception.ServiceNotFoundException;
import salon.api.model.Appointment;
import salon.api.model.CatalogService;
import salon.api.model.DialogueContext;
import salon.api.model.DialogueResponse;
import salon.api.model.DialogueState;
import salon.api.model.LlamaResponse;
import salon.api.model.Master;
import salon.api.service.BookingService;

/**
 * <h2>Центральное управляющее ядро детерминированной стейт-машины диалога</h2>
 * <p>
 * Рефакторенная версия с единой вложенной switch-структурой состояний и событий
 * (State Transition & Action Matrix), объединяющей переходы и действия в одном месте,
 * в точном соответствии с паттерном {@code OrderStateMachine}.
 * </p>
 */
@Singleton
final class SalonStateMachineService {

  private final BookingService bookingService;
  private final DateTimeParser datetimeParser;

  @Inject
  SalonStateMachineService(
      @External BookingService bookingService,
      DateTimeParser datetimeParser) {
    this.bookingService = bookingService;
    this.datetimeParser = datetimeParser;
  }

  /**
   * Сеалд-интерфейс для типизированных событий диалога (Intents / Events).
   */
  public sealed interface DialogueEvent {
    record CancelOrReset() implements DialogueEvent {}
    record ChangeDatetimeAmbiguous() implements DialogueEvent {}
    record ChooseAnotherDate() implements DialogueEvent {}
    record BookAppointment(LlamaResponse parsedData) implements DialogueEvent {}
  }

  /**
   * Сеалд-результат проверки амбивалентности услуг.
   */
  public sealed interface ServiceResolution {
    record Ambiguous(DialogueResponse response) implements ServiceResolution {}
    record Normalized(DialogueContext updatedContext) implements ServiceResolution {}
    record ExactMatch() implements ServiceResolution {}
  }

  DialogueResponse processTurn(LlamaResponse parsedData, DialogueContext context) {
    DialogueState currentState = context.currentState() != null ? context.currentState() : DialogueState.INIT;
    DialogueEvent event = mapToEvent(parsedData, currentState);

    DialogueContext currentContext = new DialogueContext(
        context.currentState(), context.slots().updateWith(parsedData.slots()), context.metadata().incrementTurn()
    );

    // Единственная вложенная switch-структура состояний и событий (State Transition & Action Matrix)
    return switch (currentState) {
      case INIT, SERVICE_SELECTION -> switch (event) {
        case DialogueEvent.CancelOrReset ignored -> handleCancel(currentContext);
        case DialogueEvent.BookAppointment evt -> executeBookAppointmentInSelection(currentContext);
        default -> defaultResponse(currentContext);
      };

      case AVAILABILITY_MATCH -> switch (event) {
        case DialogueEvent.CancelOrReset ignored -> handleCancel(currentContext);
        case DialogueEvent.BookAppointment evt -> executeBookAppointmentInAvailability(currentContext);
        default -> defaultResponse(currentContext);
      };

      case STYLIST_PREFERENCE -> switch (event) {
        case DialogueEvent.CancelOrReset ignored -> handleCancel(currentContext);
        case DialogueEvent.BookAppointment evt -> executeBookAppointmentInStylist(currentContext);
        default -> defaultResponse(currentContext);
      };

      case CONFIRMATION_PENDING -> switch (event) {
        case DialogueEvent.CancelOrReset ignored -> handleCancel(currentContext);
        case DialogueEvent.ChangeDatetimeAmbiguous ignored -> handleAmbiguousDatetime(currentContext);
        case DialogueEvent.BookAppointment evt -> defaultResponse(currentContext);
        default -> defaultResponse(currentContext);
      };

      case CLARIFY_INTENT -> switch (event) {
        case DialogueEvent.CancelOrReset ignored -> handleCancel(currentContext);
        case DialogueEvent.ChooseAnotherDate ignored -> handleChooseAnotherDate(currentContext);
        case DialogueEvent.BookAppointment evt -> handleClarifyIntent(evt.parsedData(), currentContext);
        default -> handleClarifyIntent(parsedData, currentContext);
      };
    };
  }

  private DialogueResponse defaultResponse(DialogueContext currentContext) {
    return new DialogueResponse(currentContext, "Я вас понял. Продолжаем оформление записи...");
  }

  private DialogueResponse executeBookAppointmentInSelection(DialogueContext currentContext) {
    DialogueContext.Slots slots = currentContext.slots();
    if (slots.service() != null && slots.confirmedDatetime() == null) {
      ServiceResolution resolution = resolveService(currentContext);
      if (resolution instanceof ServiceResolution.Ambiguous(DialogueResponse response)) {
        return response;
      }
      if (resolution instanceof ServiceResolution.Normalized(DialogueContext updatedContext)) {
        currentContext = updatedContext;
        slots = currentContext.slots();
      }
    }
    if (slots.service() != null && slots.datetimeRaw() != null && slots.stylist() == null) {
      return handleMastersOffer(currentContext);
    }
    if (slots.service() != null && slots.datetimeRaw() == null && slots.stylist() == null) {
      return new DialogueResponse(currentContext.withState(DialogueState.AVAILABILITY_MATCH),
          String.format("Отлично, услуга '%s' выбрана. На какой день и время вам подобрать свободные окна?", slots.service()));
    }
    if (slots.service() != null && slots.datetimeRaw() != null) {
      Optional<DialogueResponse> bookingResponse = tryFastTrackBooking(currentContext);
      if (bookingResponse.isPresent()) {
        return bookingResponse.get();
      }
    }
    return defaultResponse(currentContext);
  }

  private DialogueResponse executeBookAppointmentInAvailability(DialogueContext currentContext) {
    DialogueContext.Slots slots = currentContext.slots();
    if (slots.service() != null && slots.datetimeRaw() != null && slots.stylist() == null) {
      return handleMastersOffer(currentContext);
    }
    if (slots.service() != null && slots.datetimeRaw() != null) {
      Optional<DialogueResponse> bookingResponse = tryFastTrackBooking(currentContext);
      if (bookingResponse.isPresent()) {
        return bookingResponse.get();
      }
    }
    return defaultResponse(currentContext);
  }

  private DialogueResponse executeBookAppointmentInStylist(DialogueContext currentContext) {
    DialogueContext.Slots slots = currentContext.slots();
    if (slots.stylist() != null && !"ANY".equalsIgnoreCase(slots.stylist())) {
      Optional<DialogueResponse> errorResponse = validateMasterSchedule(currentContext);
      if (errorResponse.isPresent()) {
        return errorResponse.get();
      }
    }
    if (slots.service() != null && slots.datetimeRaw() != null) {
      Optional<DialogueResponse> bookingResponse = tryFastTrackBooking(currentContext);
      if (bookingResponse.isPresent()) {
        return bookingResponse.get();
      }
    }
    return defaultResponse(currentContext);
  }

  private DialogueEvent mapToEvent(LlamaResponse parsedData, DialogueState currentState) {
    if ("cancel_or_reset".equals(parsedData.intent())) {
      return new DialogueEvent.CancelOrReset();
    }
    if (currentState == DialogueState.CONFIRMATION_PENDING && "change_datetime_ambiguous".equals(parsedData.intent())) {
      return new DialogueEvent.ChangeDatetimeAmbiguous();
    }
    if (currentState == DialogueState.CLARIFY_INTENT && "choose_another_date".equals(parsedData.intent())) {
      return new DialogueEvent.ChooseAnotherDate();
    }
    return new DialogueEvent.BookAppointment(parsedData);
  }

  private DialogueResponse handleCancel(DialogueContext context) {
    return new DialogueResponse(DialogueContext.createNew(context.metadata().userId()), "Запись отменена.");
  }

  private DialogueResponse handleAmbiguousDatetime(DialogueContext context) {
    return new DialogueResponse(context.withState(DialogueState.CLARIFY_INTENT),
        "Вы хотите выбрать другую дату для этой записи или полностью отменить её?");
  }

  private DialogueResponse handleChooseAnotherDate(DialogueContext context) {
    DialogueContext.Slots clearedDates = new DialogueContext.Slots(
        context.slots().service(),
        context.slots().stylist(),
        null,
        null
    );
    return new DialogueResponse(context.withSlots(clearedDates).withState(DialogueState.AVAILABILITY_MATCH),
        "На какую дату и время вам подобрать окна?");
  }

  private ServiceResolution resolveService(DialogueContext ctx) {
    String rawServiceInput = ctx.slots().service();
    List<CatalogService> foundServices = bookingService.searchServicesInCatalog(rawServiceInput);

    if (foundServices.isEmpty()) {
      return new ServiceResolution.Ambiguous(new DialogueResponse(
          ctx.withSlots(clearServiceSlot(ctx)).withState(DialogueState.SERVICE_SELECTION),
          String.format("Услуга '%s' не найдена в каталоге. Какая именно процедура вас интересует?", rawServiceInput)
      ));
    }
    if (foundServices.size() > 1) {
      String catalogOptionsStr = foundServices.stream().map(CatalogService::name).reduce((a, b) -> a + ", " + b).orElse("");
      return new ServiceResolution.Ambiguous(new DialogueResponse(
          ctx.withSlots(clearServiceSlot(ctx)).withState(DialogueState.SERVICE_SELECTION),
          String.format("У нас есть несколько видов этой услуги: %s. Уточните, какая именно процедура вам необходима?", catalogOptionsStr)
      ));
    }

    CatalogService singleMatch = foundServices.getFirst();
    if (!singleMatch.name().equalsIgnoreCase(rawServiceInput.trim())) {
      DialogueContext.Slots normalizedSlots = ctx.slots().updateWith(new LlamaResponse.Slots(singleMatch.name(), null, null));
      return new ServiceResolution.Normalized(ctx.withSlots(normalizedSlots));
    }

    return new ServiceResolution.ExactMatch();
  }

  private DialogueResponse handleClarifyIntent(LlamaResponse parsedData, DialogueContext ctx) {
    if ("choose_another_date".equals(parsedData.intent())) {
      DialogueContext.Slots clearedDates = new DialogueContext.Slots(ctx.slots().service(),
          ctx.slots().stylist(), null, null);
      return new DialogueResponse(contextWithSlots(ctx, clearedDates).withState(DialogueState.AVAILABILITY_MATCH),
          "На какую дату и время вам подобрать окна?");
    }
    return new DialogueResponse(DialogueContext.createNew(ctx.metadata().userId()),
        "Запись полностью отменена.");
  }

  private DialogueContext contextWithSlots(DialogueContext ctx, DialogueContext.Slots slots) {
    return ctx.withSlots(slots);
  }

  private DialogueResponse handleMastersOffer(DialogueContext ctx) {
    Optional<LocalDateTime> parsedTimeOpt = datetimeParser.parseRaw(ctx.slots().datetimeRaw());
    if (parsedTimeOpt.isEmpty()) {
      return new DialogueResponse(ctx, "Уточните, пожалуйста, дату и время визита.");
    }

    List<CatalogService> services = bookingService.searchServicesInCatalog(ctx.slots().service());
    if (services.isEmpty()) {
      return new DialogueResponse(ctx.withState(DialogueState.SERVICE_SELECTION), "Услуга не найдена в каталоге.");
    }
    CatalogService targetService = services.getFirst();
    LocalDate date = parsedTimeOpt.get().toLocalDate();
    LocalTime from = parsedTimeOpt.get().toLocalTime();

    List<Master> working = bookingService.getAvailableMastersForServiceInterval(
        targetService.name(), date, from, from.plusMinutes(targetService.durationMinutes()));
    if (working.isEmpty()) {
      return new DialogueResponse(ctx.withState(DialogueState.AVAILABILITY_MATCH),
          "В этот интервал времени нет свободных мастеров для данной услуги.");
    }

    String listStr = working.stream().map(Master::alias).reduce((a, b) -> a + ", " + b).orElse("");
    return new DialogueResponse(ctx.withState(DialogueState.STYLIST_PREFERENCE),
        String.format("На процедуру '%s' у нас свободны мастера: %s. Кто вам больше подходит?",
            targetService.name(), listStr));
  }

  private Optional<DialogueResponse> validateMasterSchedule(DialogueContext ctx) {
    Optional<LocalDateTime> parsedTimeOpt = datetimeParser.parseRaw(ctx.slots().datetimeRaw());
    if (parsedTimeOpt.isEmpty()) {
      return Optional.empty();
    }

    List<CatalogService> services = bookingService.searchServicesInCatalog(ctx.slots().service());
    if (services.isEmpty()) {
      return Optional.empty();
    }
    CatalogService targetService = services.getFirst();
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
    List<CatalogService> services = bookingService.searchServicesInCatalog(ctx.slots().service());
    if (services.isEmpty()) {
      return Optional.empty();
    }
    CatalogService targetService = services.getFirst();

    try {
      //  ФИКС: Вызываем метод напрямую, ожидая чистый не-null объект Appointment
      final Appointment app = bookingService.tryAiBooking(
          ctx.metadata().userId(), ctx.slots().stylist(), targetService.name(), targetDateTime
      );

      final DialogueContext.Slots confirmed = new DialogueContext.Slots(
          targetService.name(),
          ctx.slots().stylist(),
          ctx.slots().datetimeRaw(),
          app.appointmentTime().toString()
      );

      return Optional.of(new DialogueResponse(ctx.withSlots(confirmed).withState(DialogueState.CONFIRMATION_PENDING),
          String.format("Отлично! Я зарезервировал время %s к мастеру %s. Подтверждаете запись?",
              targetDateTime.toLocalTime(), ctx.slots().stylist())));

    } catch (MasterUnavailableException ex) {
      // Мягко перехватываем бизнес-ошибку занятости слота и просим выбрать другое время
      return Optional.of(new DialogueResponse(ctx.withState(DialogueState.AVAILABILITY_MATCH),
          String.format("Извините, время %s у мастера %s уже занято. Можем подобрать другое время?",
              targetDateTime.toLocalTime(), ctx.slots().stylist())));

    } catch (ServiceNotFoundException ex) {
      // Мягко откатываем пользователя на выбор услуги, если маппинг сорвался
      return Optional.of(new DialogueResponse(ctx.withSlots(clearServiceSlot(ctx)).withState(DialogueState.SERVICE_SELECTION),
          "Произошла ошибка согласования услуги. Какая именно бьюти-процедура вас интересует?"));
    }
  }

  private DialogueContext.Slots clearServiceSlot(DialogueContext ctx) {
    return new DialogueContext.Slots(null, ctx.slots().stylist(), ctx.slots().datetimeRaw(), null);
  }
}
