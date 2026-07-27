package salon.api.service;

/**
 * Исходящий порт (Output Port) для отправки уведомлений и ответов пользователям.
 */
public interface NotificationService {
  /**
   * Отправляет текстовый ответ пользователю в указанную платформу мессенджера.
   *
   * @param platformId Идентификатор чата пользователя (например, telegramId).
   * @param message Текст сообщения для отправки.
   */
  void sendResponse(String platformId, String message);
}
