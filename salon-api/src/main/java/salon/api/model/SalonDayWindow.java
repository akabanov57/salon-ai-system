package salon.api.model;

import java.time.LocalTime;

/**
 * <h3>Доменная модель временного коридора парикмахерской</h3>
 *
 * <p>Неизменяемый талон (Value Object), инкапсулирующий вычисленные хронологические
 * границы работы салона на конкретные календарные сутки [Strict Grounding].</p>
 *
 * <p>Является глобальным мастер-контуром времени в системе. Любые создаваемые
 * рабочие смены мастеров ({@code MASTER_SHIFTS}) и визиты клиентов ({@code APPOINTMENTS})
 * обязаны строго укладываться внутрь данного коридора [Strict Grounding].</p>
 *
 * @param isClosed  Флаг официального выходного дня. Если {@code true}, салон полностью
 *                  закрыт для посещения, а время работы (open/close) игнорируется [Strict Grounding].
 * @param openTime  Официальный астрономический час открытия заведения в выбранные сутки
 *                  (например, {@code 09:00:00}) [Strict Grounding].
 * @param closeTime Официальный астрономический час закрытия заведения в выбранные сутки
 *                  (например, {@code 21:00:00}) [Strict Grounding].
 */
public record SalonDayWindow(
    boolean isClosed,
    LocalTime openTime,
    LocalTime closeTime
) {}
