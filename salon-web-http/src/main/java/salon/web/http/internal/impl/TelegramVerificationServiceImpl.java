package salon.web.http.internal.impl;

import io.avaje.config.Config;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.web.http.internal.services.TelegramVerificationService;

@Singleton
final class TelegramVerificationServiceImpl implements TelegramVerificationService {

  private static final Logger log = LoggerFactory.getLogger(TelegramVerificationServiceImpl.class);

  private final String expectedToken;

  TelegramVerificationServiceImpl() {
    // Читаем токен безопасности, по умолчанию пустая строка для тестов/локальной разработки
    this.expectedToken = Config.get("telegram.webhook.secret-token", "");
  }

  @Override
  public boolean isValidTelegramRequest(String headerToken) {
    if (expectedToken.isBlank()) {
      return true;
    }

    if (headerToken == null || headerToken.isBlank()) {
      log.warn("Атака на вебхук: Обнаружен входящий запрос POST без обязательного секретного токена Telegram.");
      return false;
    }

    // Защита от атак по времени (Timing Attacks) через константное сравнение байт
    return MessageDigest.isEqual(
        expectedToken.getBytes(StandardCharsets.UTF_8),
        headerToken.getBytes(StandardCharsets.UTF_8)
    );
  }
}
