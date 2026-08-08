-- =====================================================================
-- 1. УНИФИЦИРОВАННАЯ ТАБЛИЦА КЛИЕНТОВ-АККАУНТОВ
-- =====================================================================
CREATE TABLE CLIENTS
(
    ID            BIGSERIAL PRIMARY KEY,
    PLATFORM_TYPE VARCHAR(32) NOT NULL, -- 'TELEGRAM', 'INSTAGRAM'
    PLATFORM_ID   VARCHAR(64) NOT NULL, -- Natural ID from the messenger platform (e.g., chat_id)
    DISPLAY_NAME  VARCHAR(64) NOT NULL, -- Customer name or handle (Fallback: 'Guest')
    BONUS_BALANCE INT         NOT NULL DEFAULT 0,
    CREATED_AT    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Financial safety constraint enforced at the data storage engine layer
    CONSTRAINT CHK_CLIENT_BONUS_BALANCE_MUST_BE_POSITIVE_OR_ZERO CHECK (BONUS_BALANCE >= 0),

    -- Absolute identity isolation rule per messaging environment
    CONSTRAINT UK_CLIENT_SCOPED_TO_PLATFORM_IDENTITY UNIQUE (PLATFORM_TYPE, PLATFORM_ID)
);

CREATE INDEX IDX_CLIENTS_PLATFORM_LOOKUP ON CLIENTS (PLATFORM_TYPE, PLATFORM_ID);


-- =====================================================================
-- 2. ТАБЛИЦА МАСТЕРОВ / СТИЛИСТОВ САЛОНА КРАСОТЫ
-- =====================================================================
CREATE TABLE MASTERS
(
    ID             BIGSERIAL PRIMARY KEY,
    FIRST_NAME     VARCHAR(64)  NOT NULL,
    LAST_NAME      VARCHAR(64)  NOT NULL,
    SPECIALIZATION VARCHAR(128) NOT NULL,              -- Core skill context mapping (e.g., 'Top Colorist')
    CREATED_AT     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- =====================================================================
-- 3. ДИНАМИЧЕСКИЙ РАБОЧИЙ ГРАФИК (СМЕНЫ МАСТЕРОВ НА ДЕНЬ)
-- =====================================================================
CREATE TABLE MASTER_SHIFTS
(
    ID          BIGSERIAL PRIMARY KEY,
    MASTER_ID   BIGINT    NOT NULL,
    SHIFT_START TIMESTAMP NOT NULL, -- Date and time when the specific shift begins
    SHIFT_END   TIMESTAMP NOT NULL, -- Date and time when the specific shift concludes
    CREATED_AT  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Semantic validation rule enforcing strict timeline chronology
    CONSTRAINT CHK_SHIFT_CHRONOLOGY_MUST_BE_VALID CHECK (SHIFT_END > SHIFT_START),

    -- Semantic Foreign Key: Work calendar schedule belongs to a live stylist.
    -- Clears out shifts automatically if a master record profile drops out of existence.
    CONSTRAINT FK_SHIFT_ASSIGNED_TO_SALON_MASTER FOREIGN KEY (MASTER_ID)
        REFERENCES MASTERS (ID) ON DELETE CASCADE
);

-- Compound index optimizing calendar availability queries and scheduling logic sweeps
CREATE INDEX IDX_MASTER_SHIFTS_RANGE ON MASTER_SHIFTS (MASTER_ID, SHIFT_START, SHIFT_END);


-- =====================================================================
-- 4. ТАБЛИЦА СЕАНСОВ ЗАПИСЕЙ (РАСПИСАНИЕ ВИЗИТОВ)
-- =====================================================================
CREATE TABLE APPOINTMENTS
(
    ID               BIGSERIAL PRIMARY KEY,
    CLIENT_ID        BIGINT      NOT NULL,
    MASTER_ID        BIGINT      NOT NULL,
    appointment_time TIMESTAMP   NOT NULL,
    DURATION_MINUTES INT         NOT NULL DEFAULT 60,
    STATUS           VARCHAR(32) NOT NULL, -- 'AI_PENDING', 'APPROVED', 'CANCELED'
    CREATED_AT       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

-- Железный заслон от невалидных строк на уровне движка хранения данных
    CONSTRAINT CHK_APPOINTMENT_STATUS_ENUM_COMPLIANCE
        CHECK (STATUS IN ('AI_PENDING', 'APPROVED', 'CANCELED', 'COMPLETED')),

    -- Semantic Foreign Keys: Links a messaging client account with a salon stylist record.
    -- Blocks a master removal if they have active appointments scheduled (RESTRICT).
    CONSTRAINT FK_APPOINTMENT_BELONGS_TO_CONVERSATIONAL_CLIENT FOREIGN KEY (CLIENT_ID)
        REFERENCES CLIENTS (ID) ON DELETE CASCADE,

    CONSTRAINT FK_APPOINTMENT_ASSIGNED_TO_SALON_MASTER FOREIGN KEY (MASTER_ID)
        REFERENCES MASTERS (ID) ON DELETE RESTRICT
);

CREATE INDEX IDX_APPOINTMENTS_SCHEDULE ON APPOINTMENTS (MASTER_ID, appointment_time);
CREATE INDEX IDX_APPOINTMENTS_CLIENT ON APPOINTMENTS (CLIENT_ID);


-- =====================================================================
-- 5. ПОЛНОСТЬЮ НОРМАЛИЗОВАННЫЙ ЖУРНАЛ АУДИТА ПЕРЕПИСКИ
-- =====================================================================
CREATE TABLE MESSAGE_TRACES
(
    ID           BIGSERIAL PRIMARY KEY,
    CLIENT_ID    BIGINT,               -- Nullable for early Phase 1 trace intercept tracking
    TRACE_ID     VARCHAR(64) NOT NULL, -- Cross-cutting trace logging token (MDC)
    DIRECTION    VARCHAR(16) NOT NULL, -- 'INBOUND', 'OUTBOUND'
    RAW_PAYLOAD  TEXT,                 -- Inbound payload capture frame
    MESSAGE_TEXT TEXT        NOT NULL, -- Sanitized operational text representation
    CREATED_AT   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT FK_TRACE_BELONGS_TO_CONVERSATIONAL_CLIENT FOREIGN KEY (CLIENT_ID)
        REFERENCES CLIENTS (ID) ON DELETE CASCADE
);

CREATE INDEX IDX_MSG_TRACES_TRACE_ID ON MESSAGE_TRACES (TRACE_ID);

-- =====================================================================
-- 6. ТАБЛИЦА ИДЕМПОТЕНТНОСТИ ВХОДЯЩИХ СОБЫТИЙ (СЦЕНАРИЙ 1)
-- Смотри USE_CASES_RU.md Сценарий 1.
-- =====================================================================
CREATE TABLE INBOUND_EVENTS
(
    PLATFORM_TYPE         VARCHAR(32)  NOT NULL, -- 'TELEGRAM', 'INSTAGRAM'
    MESSENGER_MESSAGE_ID  VARCHAR(128) NOT NULL, -- Натуральный ID сообщения от платформы
    CREATED_AT            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Естественный составной первичный ключ обеспечивает максимальную скорость проверки
    CONSTRAINT PK_INBOUND_EVENTS PRIMARY KEY (PLATFORM_TYPE, MESSENGER_MESSAGE_ID)
);

CREATE INDEX IDX_INBOUND_EVENTS_LOOKUP ON INBOUND_EVENTS (PLATFORM_TYPE, MESSENGER_MESSAGE_ID);
