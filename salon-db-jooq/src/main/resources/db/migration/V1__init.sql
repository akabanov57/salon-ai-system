-- =====================================================================
-- 1. УНИФИЦИРОВАННАЯ ТАБЛИЦА КЛИЕНТОВ-АККАУНТОВ
-- =====================================================================
CREATE TABLE clients
(
    id            BIGSERIAL PRIMARY KEY,
    platform_type VARCHAR(32) NOT NULL, -- 'TELEGRAM', 'INSTAGRAM'
    platform_id   VARCHAR(64) NOT NULL, -- Натуральный ID мессенджера (chat_id / scoped_user_id)
    display_name  VARCHAR(64) NOT NULL, -- Имя или никнейм, полученный из сети (дефолт: 'Guest')
    bonus_balance INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Ограничение уникальности: аккаунт жестко изолирован внутри своего мессенджера
    CONSTRAINT uk_client_scoped_to_platform_identity UNIQUE (platform_type, platform_id)
);

CREATE INDEX idx_clients_platform_lookup ON clients (platform_type, platform_id);


-- =====================================================================
-- 2. ТАБЛИЦА МАСТЕРОВ / СТИЛИСТОВ САЛОНА КРАСОТЫ
-- =====================================================================
CREATE TABLE masters
(
    id             BIGSERIAL PRIMARY KEY,
    first_name     VARCHAR(64)  NOT NULL,
    last_name      VARCHAR(64)  NOT NULL,
    specialization VARCHAR(128) NOT NULL, -- 'Top Colorist', 'Stylist' Strictly NOT NULL to safeguard AI intent recognition loops
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- =====================================================================
-- 3. ТАБЛИЦА СЕАНСОВ ЗАПИСЕЙ (РАСПИСАНИЕ ВИЗИТОВ)
-- =====================================================================
CREATE TABLE appointments
(
    id               BIGSERIAL PRIMARY KEY,
    client_id        BIGINT      NOT NULL,
    master_id        BIGINT      NOT NULL,
    appointment_time TIMESTAMP   NOT NULL,
    duration_minutes INT         NOT NULL DEFAULT 60,
    status           VARCHAR(32) NOT NULL, -- 'AI_PENDING', 'APPROVED', 'CANCELED'
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- СЕМАНТИЧЕСКИЕ ВНЕШНИЕ КЛЮЧИ:
    -- Каждая запись жестко связывает конкретного гостя чат-бота с выбранным мастером расписания.
    -- Запрещаем случайное удаление мастера (RESTRICT), если к нему уже записаны люди,
    -- но позволяем каскадно удалять записи (CASCADE), если из системы стирается сам анонимный профиль клиента.
    CONSTRAINT fk_appointment_belongs_to_conversational_client FOREIGN KEY (client_id)
        REFERENCES clients (id) ON DELETE CASCADE,

    CONSTRAINT fk_appointment_assigned_to_salon_master FOREIGN KEY (master_id)
        REFERENCES masters (id) ON DELETE RESTRICT
);

-- Индексы для мгновенного поиска накладок времени и построения шахматки расписания
CREATE INDEX idx_appointments_schedule ON appointments (master_id, appointment_time);
CREATE INDEX idx_appointments_client ON appointments (client_id);


-- =====================================================================
-- 4. ПОЛНОСТЬЮ НОРМАЛИЗОВАННЫЙ ЖУРНАЛ АУДИТА ПЕРЕПИСКИ
-- =====================================================================
CREATE TABLE message_traces
(
    id           BIGSERIAL PRIMARY KEY,
    client_id    BIGINT,               -- Может быть NULL для Phase 1 (анонимный входящий POST пакет)
    trace_id     VARCHAR(64) NOT NULL, -- Сквозной диагностический маркер (MDC)
    direction    VARCHAR(16) NOT NULL, -- 'INBOUND', 'OUTBOUND'
    raw_payload  TEXT,                 -- Сырой JSON входящего вебхука (только для INBOUND)
    message_text TEXT        NOT NULL, -- Чистый читаемый текст сообщения
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- СЕМАНТИЧЕСКИЙ ВНЕШНИЙ КЛЮЧ: Каждая строка переписки привязана к истории конкретного аккаунта
    CONSTRAINT fk_trace_belongs_to_conversational_client FOREIGN KEY (client_id)
        REFERENCES clients (id) ON DELETE CASCADE
);

CREATE INDEX idx_msg_traces_trace_id ON message_traces (trace_id);
