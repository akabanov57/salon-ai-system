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
  public ChatModel chatModel() {
    String baseUrl = Config.get("ai.model.url", "http://localhost:11434");
    String modelName = Config.get("ai.model.name", "llama3");
    int timeoutSeconds = Config.getInt("ai.model.timeout-seconds", 60);

    log.info("Initializing LangChain4j ChatModel target provider via Ollama [URL: {}, Model: {}]", baseUrl, modelName);

    return OllamaChatModel.builder()
        .baseUrl(baseUrl)
        .modelName(modelName)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .temperature(0.3)
        .build();
  }

  /**
   * Собирает высокоуровневый декларативный сервис AiServices.
   */
  @Bean
  public LowLevelAiService lowLevelAiService(ChatModel model, BookingTools tools) {
    log.info("Compiling and assembling declarative LangChain4j service instance...");
    return AiServices.builder(LowLevelAiService.class)
        .chatModel(model)
        .tools(tools)
        .chatMemoryProvider(userId -> MessageWindowChatMemory.withMaxMessages(10))
        .build();
  }

}
