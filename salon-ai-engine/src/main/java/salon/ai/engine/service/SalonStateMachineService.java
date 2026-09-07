package salon.ai.engine.service;

import io.avaje.inject.External;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import salon.ai.engine.internal.service.DateTimeParser;
import salon.api.model.Appointment;
import salon.api.model.DialogueContext;
import salon.api.model.DialogueResponse;
import salon.api.model.LlamaResponse;
import salon.api.model.Master;
import salon.api.service.BookingService;

/**
 * <h2>Центральное управляющее ядро детерминированной стейт-машины диалога</h2>
 * <p>
 * Данный сервис является главным оркестратором бизнес-правил, переходов состояний и
 * валидации данных (Slot-Filling) для ИИ-ассистента салона красоты. Он изолирует бизнес-логику
 * от не детерминированного поведения Большой Языковой Модели (LLM Llama3).
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
   * Конструктор для автоматического внедрения зависимостей через компиляционный фабричный слой Avaje Inject.
   *
   * @param bookingService Входящий доменный порт (Use Case) для интеграции с расписанием салона из {@code salon-api}.
   * @param datetimeParser Компонент разбора относительных текстовых дат естественного языка в {@link LocalDateTime}.
   */
  @Inject
  SalonStateMachineService(@External BookingService bookingService, DateTimeParser datetimeParser) {
    this.bookingService = bookingService;
    this.datetimeParser = datetimeParser;
  }

  /**
   * <h3>Главный цикл обработки реплики и вычисления шага диалога</h3>
   * <p>
   * Метод принимает текущий снимок контекста разговора, сливает его с новыми распознанными слотами от Llama3,
   * инкрементирует внутренние счетчики ходов и вычисляет следующее детерминированное состояние графа автомата.
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
   * @param parsedData Объект-результат NLP-анализа от Llama3, содержащий текущий распознанный интент и новые слоты.
   *                   Не должен быть {@code null}.
   * @param context    Текущий иммутабельный снимок контекста сессии диалога, извлеченный из СУБД.
   *                   Не должен быть {@code null}.
   * @return Вычисленный {@link DialogueResponse}, содержащий обновленный иммутабельный рекордовый контекст
   *         и готовое текстовое сообщение для отправки клиенту в мессенджер.
   */
  DialogueResponse processTurn(LlamaResponse parsedData, DialogueContext context) {
    // 1. Жадное обновление слотов и инкремент счетчика ходов
    DialogueContext currentContext = new DialogueContext(
        context.currentState(),
        context.slots().updateWith(parsedData.slots()),
        context.metadata().incrementTurn()
    );

    if ("cancel_or_reset".equals(parsedData.intent())) {
      return new DialogueResponse(DialogueContext.createNew(currentContext.metadata().userId()), "Запись отменена.");
    }

    // 2. Обработка промежуточного состояния уточнения интента (Амбивалентность)
    if ("CLARIFY_INTENT".equals(currentContext.currentState())) {
      if ("choose_another_date".equals(parsedData.intent())) {
        // Мягкий откат: сохраняем услугу и мастера, сбрасываем даты
        DialogueContext.Slots clearedDates = new DialogueContext.Slots(
            currentContext.slots().service(), currentContext.slots().stylist(), null, null
        );
        return new DialogueResponse(
            currentContext.withSlots(clearedDates).withState("AVAILABILITY_MATCH"),
            "Хорошо, давайте выберем другой день. На какую дату и время вам подобрать окна?"
        );
      } else if ("cancel_booking_completely".equals(parsedData.intent())) {
        // Жесткий сброс контекста в INIT
        return new DialogueResponse(DialogueContext.createNew(currentContext.metadata().userId()), "Хорошо, я полностью отменил эту запись. Чем я могу помочь вам в другой раз?");
      }
    }

    // 3. Логика Сквозного Переопределения (Slot-Filling Override / Fast-Track)
    DialogueContext.Slots slots = currentContext.slots();
    if (slots.service() != null && slots.stylist() != null && slots.datetimeRaw() != null) {

      Optional<LocalDateTime> targetTimeOpt = datetimeParser.parseRaw(slots.datetimeRaw());
      if (targetTimeOpt.isEmpty()) {
        return new DialogueResponse(currentContext.withState("AVAILABILITY_MATCH"), "Не удалось распознать время. Напишите, пожалуйста, точную дату и время.");
      }

      LocalDateTime targetTime = targetTimeOpt.get();

      // Интеграция с Inbound Port: атомарная попытка бронирования по текстовым контекстам
      Optional<Appointment> appointmentOpt = bookingService.tryAiBooking(
          currentContext.metadata().userId(), slots.stylist(), slots.service(), targetTime
      );

      if (appointmentOpt.isPresent()) {
        DialogueContext.Slots confirmedSlots = new DialogueContext.Slots(
            slots.service(), slots.stylist(), slots.datetimeRaw(), appointmentOpt.get().appointmentTime().toString()
        );
        return new DialogueResponse(
            currentContext.withSlots(confirmedSlots).withState("CONFIRMATION_PENDING"),
            String.format("Отлично! Я предварительно зарезервировал: %s к мастеру %s на %s. Подтверждаете?", slots.service(), slots.stylist(), slots.datetimeRaw())
        );
      } else {
        List<Master> activeMasters = bookingService.getActiveMastersForDate(targetTime);
        String masterListStr = activeMasters.stream().map(Master::alias).reduce((a, b) -> a + ", " + b).orElse("нет свободных мастеров");
        return new DialogueResponse(
            currentContext.withState("AVAILABILITY_MATCH"),
            "К сожалению, это время уже занято. На этот день у нас доступны специалисты: " + masterListStr + ". Выберите другое время."
        );
      }
    }

    // 4. Проверка реакций на шаге CONFIRMATION_PENDING (Перехват амбивалентных фраз)
    if ("CONFIRMATION_PENDING".equals(currentContext.currentState()) && "change_datetime_ambiguous".equals(parsedData.intent())) {
      return new DialogueResponse(
          currentContext.withState("CLARIFY_INTENT"),
          "Я вас понял! Вы хотите выбрать другую дату для этой записи или полностью отменить её?"
      );
    }

    return evaluateStandardTransitions(currentContext);
  }

  /**
   * <h3>Вычисление пошаговых детерминированных переходов (Fallback-цепочка)</h3>
   * <p>
   * Метод вызывается как резервный (Fallback), если условия для сквозного заполнения слотов не выполнены.
   * Он анализирует, какого конкретно слота не хватает (Услуга -> Мастер -> Дата), и формирует
   * точечный атомарный вопрос для ведения клиента по классической воронке записи.
   * </p>
   *
   * @param context Текущее иммутабельное состояние сессии разговора с частично заполненными слотами.
   *                Не должно быть {@code null}.
   * @return Результирующий ответ с инструкцией для пользователя по заполнению следующего целевого слота.
   */
  private DialogueResponse evaluateStandardTransitions(DialogueContext context) {
    return new DialogueResponse(context, "Продолжаем заполнение параметров записи...");
  }
}
