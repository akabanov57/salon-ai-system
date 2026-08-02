package salon.api.model;

import io.avaje.jsonb.Json;

/**
 * <h2>Бизнес-модель: Входящее текстовое сообщение из внешнего канала</h2>
 * Единый межмодульный контракт данных, описывающий структуру сообщений,
 * прибывающих в салон красоты из различных мессенджеров.
 */
@Json
public record IncomingMessageDto(
    @Json.Property("platform_id") String platformId,
    @Json.Property("platform_type") String platformType,
    @Json.Property("first_name") String firstName,
    String text
) {}
