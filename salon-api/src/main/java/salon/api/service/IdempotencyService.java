package salon.api.service;

import salon.api.model.PlatformType;

/**
 * Архитектурный контракт для защиты сетевых границ от эхо-повторов (Retries).
 */

public interface IdempotencyService {

  /**
   * Пытается атомарно захватить уникальный сетевой замок в СУБД.
   *
   * @param platformType         Тип мессенджера (TELEGRAM, INSTAGRAM)
   * @param messengerMessageId  Натуральный ID сообщения от платформы
   * @return true - если пакет уникальный (впервые в системе),
   *         false - если обнаружен сетевой дубликат.
   */
  boolean tryAcquireLock(PlatformType platformType, String messengerMessageId);
}
