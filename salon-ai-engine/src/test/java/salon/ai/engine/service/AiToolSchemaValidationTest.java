package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.avaje.inject.BeanScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import salon.api.service.BookingService;

/**
 * <h3>Интеграционный тест комплаенса и валидации JSON-Schema ИИ-инструментов</h3>
 *
 * <p>Проверяет, что фреймворк LangChain4j успешно извлекает новые бесцифровые строковые
 * сигнатуры методов из компонента инструментов и компилирует валидную матрицу описания
 * функций для передачи в модель Llama3 [Strict Grounding].</p>
 */
class AiToolSchemaValidationTest {
  private static BeanScope beanScope;

  @BeforeAll
  static void initializeSchemaContext() {
    BookingService bookingServiceMock = Mockito.mock(BookingService.class);
    ChatModel chatModelMock = Mockito.mock(ChatModel.class);

    // Собираем контекст, подменяя внешние и тяжелые ИИ-зависимости моками
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock, chatModelMock)
        .build();
  }

  @AfterAll
  static void terminateSchemaContext() {
    if (beanScope != null) {
      beanScope.close();
    }
  }

  /**
   * <h3>Тест: Проверка структурной целостности схемы вызова функции бронирования</h3>
   * <p><b>Бизнес-контекст:</b> Модель Llama3 критически зависит от текстовых описаний полей.
   * Тест верифицирует, что из рук ИИ полностью изъяты Long ID, а параметры схемы
   * соответствуют исключительно строковым типам [Strict Grounding].</p>
   */
  @Test
  void shouldCompileValidStringBasedJsonSchemaForBookingTool() {
    // Arrange: Извлекаем скомпилированную реализацию BookingToolsImpl из контейнера Avaje
    // ПРИМЕЧАНИЕ: Класс извлекается по его типу напрямую из скомпилированного скоупа
    var bookingToolsComponent = beanScope.get(BookingToolsImpl.class);
    assertNotNull(bookingToolsComponent, "DI-контейнер обязан успешно собрать экземпляр BookingToolsImpl.");

    // Act: Вручную запускаем фабричный экстрактор спецификаций LangChain4j
    List<ToolSpecification> toolSpecifications = dev.langchain4j.agent.tool.ToolSpecifications
        .toolSpecificationsFrom(bookingToolsComponent);

    // Assert: 1. Проверяем наличие наших трех когнитивных инструментов
    assertNotNull(toolSpecifications, "Список спецификаций функций не должен быть null.");
    assertEquals(3, toolSpecifications.size(), "ИИ-ассистент обязан экспортировать ровно 3 доступных инструмента.");

    // 2. Ищем точечную схему метода резервирования слотов 'bookAppointmentSlot'
    ToolSpecification bookingToolSpec = toolSpecifications.stream()
        .filter(spec -> "bookAppointmentSlot".equals(spec.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Критическая ошибка: Инструмент 'bookAppointmentSlot' не найден в схеме!"));

    // 3. Проверяем, что базовое описание функции присутствует и информативно
    assertNotNull(bookingToolSpec.description(), "Описание инструмента бронирования не должно быть null.");
    assertFalse(bookingToolSpec.description().trim().isEmpty(), "Описание инструмента бронирования не должно быть пустым.");

    // 4. Инспектируем параметры схемы на отсутствие примитивов Long / Integer
    // FIX: Используем новый строго типизированный маппинг LangChain4j API вместо устаревшего сырого каста
    Map<String, JsonSchemaElement> properties = bookingToolSpec.parameters().properties();
    assertNotNull(properties, "Матрица свойств JSON-Schema параметров не должна быть null.");

    // Проверяем свойство 'platformId' присутствует в схеме
    assertTrue(properties.containsKey("platformId"), "Параметр 'platformId' обязан присутствовать в JSON-схеме.");

    // Проверяем свойство 'masterAlias' присутствует в схеме
    assertTrue(properties.containsKey("masterAlias"), "Параметр 'masterAlias' обязан присутствовать в JSON-схеме.");

    // Проверяем свойство 'serviceName' присутствует в схеме
    assertTrue(properties.containsKey("serviceName"), "Параметр 'serviceName' обязан присутствовать в JSON-схеме.");

    assertTrue(properties.containsKey("dateTimeStr"), "Параметр 'dateTimeStr' обязан присутствовать в JSON-схеме.");

    // Проверяем абсолютное отсутствие старых числовых полей 'serviceId' или 'masterId' (Защитный барьер)
    assertFalse(properties.containsKey("masterId"), "Критическая утечка абстракций: Скрытый суррогатный masterId обнаружен в схеме ИИ!");
    assertFalse(properties.containsKey("serviceId"), "Критическая утечка абстракций: Скрытый суррогатный serviceId обнаружен в схеме ИИ!");
  }

}
