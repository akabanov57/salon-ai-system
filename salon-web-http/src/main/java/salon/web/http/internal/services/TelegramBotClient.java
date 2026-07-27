package salon.web.http.internal.services;

import io.avaje.http.api.Body;
import io.avaje.http.api.Client;
import io.avaje.http.api.Post;
import io.avaje.jsonb.Json;

/**
 * Compile-time declarative HTTP client targeting the outbound Telegram Bot API engine.
 * Automatically marshaled and executed via avaje-http-client.
 */
@Client
public interface TelegramBotClient {

  // Simple structural record mapping Telegram's required payload format
  // ИСПРАВЛЕНО: Пишем по стандартам Java (camelCase), но явно маппим в snake_case для Telegram
  record SendMessageDto(
      @Json.Property("chat_id") String chatId,
      String text
  ) {}

  /**
   * Dispatches a structured JSON payload directly to the bot's sendMessage route.
   * <p>
   * FIX: Removed the invalid parameter-level @Path annotation. The argument name 'token' natively
   * maps to the path placeholder '{token}'.
   */
  @Post("/{token}/sendMessage")
  void sendMessage(String token, @Body SendMessageDto payload);
}
