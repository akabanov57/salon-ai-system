package salon.ai.engine.internal.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import salon.api.model.LlamaResponse;

/**
 * <h2>DESIGN INTENT: Declarative LangChain4j AI Orchestration Agent</h2>
 * This interface represents the low-level technical boundary where the LLM engine, prompt templates,
 * session memory trackers, and function-calling tools are compiled into a unified runtime component.
 *
 * <p><b>WHY IT EXISTS:</b>
 * LangChain4j uses the <i>Gateway/Agent Pattern</i>. We declare the high-level conversational contract,
 * and the framework automatically generates a type-safe runtime proxy implementation. This proxy handles
 * the underlying complexities of managing system prompts, mapping conversational state records, and
 * intercepting tool execution cycles.</p>
 *
 * <p><b>ARCHITECTURAL ROLE:</b>
 * This component acts as an internal pipeline node. It is wrapped inside the public-facing
 * {@code AiAssistantServiceImpl} and is kept completely hidden from the rest of the application.
 * This ensures that if we change AI frameworks in the future, the change stays isolated inside this module.</p>
 */
public interface LowLevelAiService {

  /**
   * <h3>Бизнес-метод: Изолированный NLP-парсинг текста в JSON-слоты</h3>
   * <p>
   * Вызывается оркестратором бэкенда для превращения неструктурированной реплики
   * пользователя в строго типизированный Record для детерминированной стейт-машины.
   * </p>
   * <p>Этот метод является атомарным и полностью изолирован от Chat Memory.</p>
   *
   * @param userMessage Очищенная реплика пользователя из мессенджера.
   * @return Заполненный объект LlamaResponse (Интент + Текстовые Слоты).
   */
  @SystemMessage("""
        Вы — интеллектуальный детерминированный модуль распознавания интентов и извлечения слотов для салона красоты.
        Ваша задача — проанализировать реплику пользователя и вернуть строго валидный JSON-объект без преамбул и объяснений.

        СТРУКТУРА ВЫХОДНОГО JSON:
        {
          "intent": "СТРОКА_ИНТЕНТА",
          "slots": {
            "service": "название_услуги_или_null",
            "stylist": "имя_мастера_или_null",
            "datetimeRaw": "строка_времени_или_null"
          }
        }

        ПРАВИЛА ИЗВЛЕЧЕНИЯ ИНТЕНТОВ (intent):
        - "book_appointment": Клиент явно хочет записаться, обновить параметры или продолжить оформление.
        - "cancel_or_reset": Клиент пишет слова отмены ("отмени", "сбрось", "начни сначала").
        - "change_datetime_ambiguous": Клиент выражает желание перенести запись на размытый срок ("в другой день", "позже").
        - "general_chat": Обычные приветствия, вопросы про цены или сторонние разговоры.

        ПРАВИЛА ИЗВЛЕЧЕНИЯ СЛОТОВ (slots):
        - service: Ключевое слово процедуры (например: "стрижка", "окрашивание", "маникюр").
        - stylist: Псевдоним или имя желаемого мастера (например: "Сара", "Елена").
        - datetimeRaw: Любое текстовое упоминание времени или интервала целиком (например: "завтра в 14:00", "в среду с 12 до 15"). Если клиент назвал время — этот слот обязан быть заполнен.
        """)
  LlamaResponse extractIntentAndSlots(@UserMessage String userMessage);
}
