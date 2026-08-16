package salon.ai.engine.config;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.ai.engine.internal.service.BookingTools;
import salon.ai.engine.internal.service.LowLevelAiService;

@Factory
final class AiEngineConfigurationFactory {

  private static final Logger log = LoggerFactory.getLogger(AiEngineConfigurationFactory.class);

  /**
   * Создает бин современной языковой модели ChatModel.
   */
  @Bean
  ChatModel chatModel() {
    final String baseUrl = Config.get("ai.model.url", "http://localhost:11434");
    final String modelName = Config.get("ai.model.name", "llama3");
    final int timeoutSeconds = Config.getInt("ai.model.timeout-seconds", 60);

    log.info("Initializing LangChain4j ChatModel target provider via Ollama [URL: {}, Model: {}]", baseUrl, modelName);

    return OllamaChatModel.builder()
        .baseUrl(baseUrl)
        .modelName(modelName)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .temperature(0.3) // Низкая температура снижает галлюцинации и делает вызовы инструментов точными
        .build();
  }

  /**
   * Собирает высокоуровневый декларативный сервис AiServices с жесткими системными инструкциями.
   */
  @Bean
  LowLevelAiService lowLevelAiService(ChatModel model, BookingTools tools) {
    log.info("Compiling and assembling declarative LangChain4j service instance with injected guardrails...");

    // КОГНИТИВНЫЙ МАНИФЕСТ ПОВЕДЕНИЯ РОБОТА (GUARD BOUNDS)
    final String systemPromptTemplate = """
        Вы — профессиональный ИИ-администратор салона красоты (парикмахерской). Ваша цель — вежливо и эффективно записать клиента на визит.
        
        ПРАВИЛА И ЭТАПЫ РАБОТЫ В ДИАЛОГЕ:
        1. ИДЕНТИФИКАЦИЯ УСЛУГИ: Как только клиент называет процедуру (например, "хочу подстричься", "окрашивание"), вы ОБЯЗАНЫ сразу же вызвать инструмент `searchServices`, передав туда ключевое слово.
           - Из ответа инструмента выберите ОФИЦИАЛЬНОЕ название услуги (Official Service Name) и используйте только его.
           - Никогда не выдумывайте услуги и не подтверждайте запись, пока не проверите наличие услуги в каталоге!
        2. ВЫБОР МАСТЕРА: Вызовите инструмент `getAvailableStylists`, чтобы узнать список работающих специалистов. Предложите их клиенту. Вы обязаны запомнить текстовый псевдоним (Alias) выбранного мастера (например, 'elena_colorist').
        3. СОГЛАСОВАНИЕ ВРЕМЕНИ: Согласуйте дату и время начала визита. Приведите строку времени строго к международному ISO-формату: YYYY-MM-DDTHH:MM (например, '2026-08-25T14:30').
        
        КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО:
        - Использовать, запрашивать или выдумывать числовые ID (идентификаторы) для мастеров или услуг. Работайте только со строковыми именами и псевдонимами Alias!
        - Самостоятельно придумывать длительность или стоимость процедур — сервер выставит их автоматически на основе официального имени услуги.
        - Вызывать инструмент финального бронирования `bookAppointmentSlot`, пока не собраны ВСЕ параметры: platformId клиента, masterAlias, official Service Name и ISO-дата.
        
        Если инструмент бронирования вернул FAILURE (слот занят или у мастера нет квалификации), вежливо объясните причину и предложите клиенту выбрать другое время или другого мастера.
        Пишите ответы коротко, вежливо и дружелюбно.
        """;

    return AiServices.builder(LowLevelAiService.class)
        .chatModel(model)
        .tools(tools)
        .chatMemoryProvider(userId -> MessageWindowChatMemory.withMaxMessages(10))
        // Внедряем промпт-инструкцию для всех сессий чата ассистента
        .systemMessageProvider(userId -> systemPromptTemplate)
        .build();
  }

}
