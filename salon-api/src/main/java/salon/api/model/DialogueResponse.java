package salon.api.model;

import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.Valid;

/**
 * <h2>Иммутабельный контейнер результата шага стейт-машины (Dialogue Step Output)</h2>
 * <p>
 * Данный рекорд выступает в роли результирующего DTO (Data Transfer Object), который
 * возвращается детерминированным движком {@code SalonStateMachineService} после обработки реплики.
 * </p>
 *
 * <p>Он решает задачу атомарного связывания измененного состояния контекста с текстом,
 * предназначенным для отправки в мессенджер.</p>
 *
 * @param updatedContext Новый иммутабельный снимок сессии диалога {@link DialogueContext}
 *                       с обновленным статусом автомата и жадно заполненными слотами.
 *                       Используется оркестратором для персистентного сохранения в СУБД.
 *                       Не может быть {@code null}.
 * @param replyMessage   Очищенный, готовый к отправке текстовый ответ для клиента
 *                       (например, форма подтверждения или наводящий вопрос).
 *                       Не может быть {@code null} или пустым.
 */
@Valid // Сигнализирует процессору аннотаций avaje-validation о необходимости генерации адаптера
public record DialogueResponse(

    @NotNull(message = "Обновленный контекст ИИ не может быть null")
    DialogueContext updatedContext,

    @NotBlank(message = "Текстовое сообщение ответа не может быть пустым")
    String replyMessage
) {}
