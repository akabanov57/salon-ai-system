package salon.api.service;

/**
 * Высокоуровневый бизнес-ориентированный порт (Use Case) для взаимодействия с ИИ-ассистентом.
 * Доступен для вызова из контроллеров вебхуков (salon-web-http) и административной панели
 * (salon-gui-vaadin).
 */
public interface AiAssistantService {

  /**
   * Передает входящий текст пользователя в оркестрационный ИИ-контекст.
   *
   * @param telegramId  Идентификатор чата пользователя в мессенджере.
   * @param userMessage Сырой текст сообщения от клиента.
   * @return Текстовый ответ модели, готовый для отправки обратно в мессенджер.
   */
  String processChat(String telegramId, String userMessage);
}
