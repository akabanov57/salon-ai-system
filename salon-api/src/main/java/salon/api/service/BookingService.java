package salon.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import salon.api.model.Appointment;
import salon.api.model.Master;
import salon.api.model.ProcessMessageCommand;

/**
 * Высокоуровневый бизнес-ориентированный порт (Use Case) для управления процессами салона.
 * Единственная точка входа для ИИ-движка, вебхуков и GUI.
 */
public interface BookingService {

  /**
   * Идентифицирует клиента по Telegram ID или создает новый профиль, если он пишет впервые.
   * Реализует атомарный шаг Use Case №1.
   */
  void processMessage(ProcessMessageCommand command);

  /**
   * Возвращает список всех работающих мастеров для ИИ-подсказок или сетки GUI.
   */
  List<Master> getAvailableStylists();

  /**
   * Пытается забронировать предварительное время (AI_PENDING).
   * Автоматически проверяет наложение окон (Overlapping) на уровне бизнес-логики.
   *
   * @return Запись, если бронь успешна, или Optional.empty(), если время уже занято.
   */
  Optional<Appointment> tryAiBooking(Long clientId, Long masterId, LocalDateTime time, int durationMinutes);

  /**
   * Сценарий для Владельца (Вашей жены): Одобрить запись из Vaadin GUI.
   */
  void approveAppointment(Long appointmentId);
}
