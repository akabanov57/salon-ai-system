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
   * Предварительное резервирование слота времени ИИ-ассистентом.
   * Длительность вычисляется на сервере автоматически по идентификатору услуги.
   *
   * @param clientId        Идентификатор клиента
   * @param masterId        Идентификатор мастера
   * @param serviceId       Идентификатор запрашиваемой услуги из каталога SERVICES
   * @param appointmentTime Желаемое время начала сеанса
   * @return Запись черновика визита, если время успешно заблокировано
   */
  Optional<Appointment> tryAiBooking(Long clientId, Long masterId, Long serviceId, LocalDateTime appointmentTime);

  /**
   * Сценарий для Владельца (Вашей жены): Одобрить запись из Vaadin GUI.
   */
  void approveAppointment(Long appointmentId);

  /**
   * <h3>Бизнес-метод: Получение всех мастеров, работающих в выбранный день</h3>
   * Позволяет ИИ-ассистенту ориентировать клиентов по доступному на сегодня штату специалистов.
   */
  List<Master> getActiveMastersForDate(LocalDateTime date);

  /**
   * <h3>Бизнес-метод: Проверка фактической занятости и доступности мастера</h3>
   * Вычисляет, находится ли мастер на рабочей смене и свободен ли запрашиваемый временной интервал.
   */
  boolean isMasterAvailableAt(Long masterId, LocalDateTime time, int durationMinutes);
}
