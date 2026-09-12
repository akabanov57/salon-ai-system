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
import salon.ai.engine.internal.service.LowLevelAiService;

@Factory
final class AiEngineConfigurationFactory {

  private static final Logger log = LoggerFactory.getLogger(AiEngineConfigurationFactory.class);

  /**
   * <h3>Инициализация базового сетевого клиента LLM Llama3</h3>
   * <p>
   * Конструирует низкоуровневое соединение с моделью Ollama, извлекая
   * параметры рантайма из системной конфигурации салона.
   * </p>
   */
  @Bean
  ChatModel chatModel() {
    final String baseUrl = Config.get("ai.model.url", "http://docker.home.org:11434");
    final String modelName = Config.get("ai.model.name", "llama3.2:3b");
    final int timeoutSeconds = Config.getInt("ai.model.timeout-seconds", 240);

    log.info("Initializing LangChain4j ChatModel target provider via Ollama [URL: {}, Model: {}]", baseUrl, modelName);

    return OllamaChatModel.builder()
        .baseUrl(baseUrl)
        .modelName(modelName)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .temperature(0.0) // Низкая температура снижает галлюцинации и делает вызовы инструментов точными
        .build();
  }

  /**
   * <h3>Сборка изолированного сервиса NLP-экстракции слотов</h3>
   * <p>
   * Метод конструирует легковесный, детерминированный прокси-сервер для разбора текста.
   * Зависимость от {@code BookingTools} полностью удалена, так как модель больше
   * не использует агентные инструменты.
   * </p>
   *
   * @param model Системный клиент подключения к LLM ядру Llama3 (Ollama / OpenAI).
   * @return Динамически сгенерированная LangChain4j реализация интерфейса {@link LowLevelAiService}.
   */
  @Bean // Регистрирует возвращаемый прокси-объект в глобальном контексте Avaje Inject
  public LowLevelAiService lowLevelAiService(ChatModel model) {
    log.info("AI Factory: Запуск компиляции легковесного структурированного парсера LowLevelAiService...");

    // Чистый, изолированный, предсказуемый NLP-парсер без побочных эффектов и лишних зависимостей
    return AiServices.builder(LowLevelAiService.class)
        .chatModel(model)
        // Логика фильтрации и памяти полностью ушла в Java-код
        .build();
  }

}
