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
    ALIAS          VARCHAR(64)  NOT NULL, -- Уникальный разговорный псевдоним для ИИ ('elena_colorist')
    FIRST_NAME     VARCHAR(64)  NOT NULL,
    LAST_NAME      VARCHAR(64)  NOT NULL,
    SPECIALIZATION VARCHAR(128) NOT NULL, -- Core skill context mapping (e.g., 'Top Colorist')
    CREATED_AT     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT UQ_MASTER_ALIAS UNIQUE (ALIAS)
);

CREATE INDEX IDX_MASTERS_ALIAS ON MASTERS (ALIAS);


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
-- 3.1. ТАБЛИЦА ПЕРЕРЫВОВ ВНУТРИ СМЕНЫ МАСТЕРА
-- =====================================================================
CREATE TABLE MASTER_SHIFT_BREAKS
(
    ID          BIGSERIAL PRIMARY KEY,
    SHIFT_ID    BIGINT    NOT NULL, -- Связь с родительской сменой из MASTER_SHIFTS
    BREAK_START TIMESTAMP NOT NULL, -- Время начала перерыва (например, обед в 13:00)
    BREAK_END   TIMESTAMP NOT NULL, -- Время окончания перерыва (например, конец обеда в 14:00)
    CREATED_AT  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Проверка хронологии: перерыв должен иметь валидные границы
    CONSTRAINT CHK_BREAK_CHRONOLOGY_MUST_BE_VALID CHECK (BREAK_END > BREAK_START),

    -- Внешний ключ: перерыв жестко привязан к конкретной смене
    CONSTRAINT FK_BREAK_BELONGS_TO_MASTER_SHIFT FOREIGN KEY (SHIFT_ID)
        REFERENCES MASTER_SHIFTS (ID) ON DELETE CASCADE
);

CREATE INDEX IDX_MASTER_SHIFT_BREAKS_LOOKUP ON MASTER_SHIFT_BREAKS (SHIFT_ID, BREAK_START, BREAK_END);

-- =====================================================================
-- 1.1. СПРАВОЧНИК УСЛУГ ПАРИКМАХЕРСКОЙ (КАТАЛОГ)
-- =====================================================================
CREATE TABLE SERVICES
(
    ID               BIGSERIAL PRIMARY KEY,
    NAME             VARCHAR(128)   NOT NULL, -- Официальное наименование (уникальный бизнес-ключ)
    DURATION_MINUTES INT            NOT NULL, -- Объективное нормативное время услуги
    PRICE            NUMERIC(10, 2) NOT NULL,
    CREATED_AT       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT UQ_SERVICE_NAME UNIQUE (NAME),
    CONSTRAINT CHK_SERVICE_DURATION_MUST_BE_POSITIVE CHECK (DURATION_MINUTES > 0),
    CONSTRAINT CHK_SERVICE_PRICE CHECK (PRICE >= 0)
);

-- =====================================================================
-- 1.2. МАТРИЦА КОМПЕТЕНЦИЙ МАСТЕРОВ (СВЯЗЬ МНОГИЕ-КО-МНОГИМ)
-- =====================================================================
CREATE TABLE MASTER_SERVICES
(
    MASTER_ID  BIGINT    NOT NULL,
    SERVICE_ID BIGINT    NOT NULL,
    CREATED_AT TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Составной первичный ключ гарантирует уникальность пары
    PRIMARY KEY (MASTER_ID, SERVICE_ID),

    CONSTRAINT FK_MASTER_COMPETENCE FOREIGN KEY (MASTER_ID)
        REFERENCES MASTERS (ID) ON DELETE CASCADE,

    CONSTRAINT FK_SERVICE_COMPETENCE FOREIGN KEY (SERVICE_ID)
        REFERENCES SERVICES (ID) ON DELETE CASCADE
);

CREATE INDEX IDX_MASTER_SERVICES_LOOKUP ON MASTER_SERVICES (MASTER_ID, SERVICE_ID);

-- =====================================================================
-- 4. ТАБЛИЦА СЕАНСОВ ЗАПИСЕЙ (РАСПИСАНИЕ ВИЗИТОВ)
-- =====================================================================
-- АРХИТЕКТУРНОЕ ОБОСНОВАНИЕ СУРРОГАТНОГО КЛЮЧА (ID):
-- Использование естественного составного ключа (CLIENT_ID, MASTER_ID, APPOINTMENT_TIME)
-- намеренно отклонено в пользу суррогатного ID по следующим причинам:
-- 1. Мутабельность (Переносы визитов): Время сеанса может изменяться администратором через Vaadin UI.
--    Изменение полей, входящих в Primary Key, ломает индексацию и идентичность сущности в ORM/DataProviders.
-- 2. Ссылочная целостность (FK): ID служит легковесным неизменяемым якорем для будущих дочерних
--    таблиц (PAYMENTS, APPOINTMENT_SERVICES, FEEDBACK_LOGS), исключая раздувание составных внешних ключей.
-- 3. Семантика уникальности: Составной ключ по тройке полей математически не защищает от овербукинга
--    (параллельная запись разных клиентов к одному мастеру на одно время), что в любом случае требует
--    двухэтапной интервальной проверки на уровне бизнес-логики (tryAiBooking).
-- =====================================================================
CREATE TABLE APPOINTMENTS
(
    ID               BIGSERIAL PRIMARY KEY,   -- Внутренний суррогатный ключ для персистентного слоя и быстрых связей в СУБД
    TICKET_CODE      VARCHAR(32)    NOT NULL, -- Уникальный публичный бизнес-код записи визита (например, 'SB-20260813-A7X')
    CLIENT_ID        BIGINT         NOT NULL, -- Скрытый внешний ключ связи с родителем в таблице CLIENTS
    MASTER_ID        BIGINT         NOT NULL, -- Скрытый внешний ключ связи с родителем в таблице MASTERS
    SERVICE_ID       BIGINT         NOT NULL, -- FIX: Прямая жесткая привязка к каталогу услуг
    APPOINTMENT_TIME TIMESTAMP      NOT NULL, -- Дата и точное время начала сеанса визита
    DURATION_MINUTES INT            NOT NULL, -- Копируется из SERVICES для стабильности исторического аудита
    PRICE            NUMERIC(10, 2) NOT NULL, -- FIX: Фиксация исторической стоимости на дату записи
    STATUS           VARCHAR(32)    NOT NULL, -- 'AI_PENDING', 'APPROVED', 'CANCELED'
    CREATED_AT       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Ограничение уникальности публичного бизнес-кода билета визита
    CONSTRAINT UQ_APPOINTMENT_TICKET_CODE
        UNIQUE (TICKET_CODE),

    -- Железный заслон от невалидных строк на уровне движка хранения данных
    CONSTRAINT CHK_APPOINTMENT_STATUS_ENUM_COMPLIANCE
        CHECK (STATUS IN ('AI_PENDING', 'APPROVED', 'CANCELED', 'COMPLETED')),

    -- Semantic Foreign Keys: Links a messaging client account with a salon stylist record.
    -- Blocks a master removal if they have active appointments scheduled (RESTRICT).
    CONSTRAINT FK_APPOINTMENT_BELONGS_TO_CONVERSATIONAL_CLIENT FOREIGN KEY (CLIENT_ID)
        REFERENCES CLIENTS (ID) ON DELETE CASCADE,

    CONSTRAINT FK_APPOINTMENT_ASSIGNED_TO_SALON_MASTER FOREIGN KEY (MASTER_ID)
        REFERENCES MASTERS (ID) ON DELETE RESTRICT,

    CONSTRAINT FK_APPOINTMENT_LINKS_TO_SERVICE FOREIGN KEY (SERVICE_ID) REFERENCES SERVICES (ID) ON DELETE RESTRICT
);

-- Индексы для обеспечения максимального быстродействия поисковых операций
CREATE UNIQUE INDEX IDX_APPOINTMENTS_TICKET_CODE ON APPOINTMENTS (TICKET_CODE);
CREATE INDEX IDX_APPOINTMENTS_LOOKUP_COMPLEX ON APPOINTMENTS (MASTER_ID, STATUS, APPOINTMENT_TIME);
CREATE INDEX IDX_APPOINTMENTS_CLIENT ON APPOINTMENTS (CLIENT_ID);
CREATE INDEX IDX_APPOINTMENTS_SERVICE_ID ON APPOINTMENTS (SERVICE_ID);


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
    PLATFORM_TYPE        VARCHAR(32)  NOT NULL, -- 'TELEGRAM', 'INSTAGRAM'
    MESSENGER_MESSAGE_ID VARCHAR(128) NOT NULL, -- Натуральный ID сообщения от платформы
    CREATED_AT           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Естественный составной первичный ключ обеспечивает максимальную скорость проверки
    CONSTRAINT PK_INBOUND_EVENTS PRIMARY KEY (PLATFORM_TYPE, MESSENGER_MESSAGE_ID)
);

CREATE INDEX IDX_INBOUND_EVENTS_LOOKUP ON INBOUND_EVENTS (PLATFORM_TYPE, MESSENGER_MESSAGE_ID);

-- =====================================================================
-- 7. ИНФРАСТРУКТУРНЫЙ КОНТУР ГРАФИКА РАБОТЫ САЛОНА (ПАРИКМАХЕРСКОЙ)
-- =====================================================================
-- АЛГОРИТМ РАЗРЕШЕНИЯ ВРЕМЕНИ РАБОТЫ (FALLBACK / OVERRIDE PATTERN):
-- При вычислении доступности заведения на конкретную дату (Target Date)
-- доменный провайдер (SalonScheduleProvider) обязан выполнять двухэтапный поиск:
--
-- ШАГ 1 (Приоритет - Календарное исключение):
--   Система выполняет точечный поиск даты в таблице SALON_CALENDAR_EXCEPTIONS.
--   Если строка найдена — используются именно эти часы (праздничный, санитарный
--   или сокращенный день). Это значение имеет высший приоритет и перекрывает шаблон.
--
-- ШАГ 2 (Дефолтный вариант - Шаблон дня недели):
--   Если запись на выбранную дату в таблице исключений отсутствует, система
--   вычисляет день недели для Target Date (в Java: date.getDayOfWeek().name())
--   и извлекает стандартный циклический регламент из таблицы SALON_WEEKLY_SCHEDULE.
-- =====================================================================

-- 7.1. БАЗОВЫЙ ЦИКЛИЧЕСКИЙ ШАБЛОН РАБОТЫ ПО ДНЯМ НЕДЕЛИ
CREATE TABLE SALON_WEEKLY_SCHEDULE
(
    DAY_OF_WEEK VARCHAR(16) PRIMARY KEY, -- 'MONDAY', 'TUESDAY', ... 'SUNDAY'
    IS_CLOSED   BOOLEAN   NOT NULL DEFAULT FALSE,
    OPEN_TIME   TIME      NOT NULL DEFAULT '09:00:00',
    CLOSE_TIME  TIME      NOT NULL DEFAULT '20:00:00',
    CREATED_AT  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Гарантируем валидность временного интервала на уровне ядра СУБД
    CONSTRAINT CHK_SALON_WEEKLY_CHRONOLOGY CHECK (IS_CLOSED = TRUE OR CLOSE_TIME > OPEN_TIME)
);

-- 7.2. ДИНАМИЧЕСКИЕ КАЛЕНДАРНЫЕ ИСКЛЮЧЕНИЯ (ПРАЗДНИКИ, ПЕРЕНОСЫ, МУТАЦИИ ГРАФИКА)
CREATE TABLE SALON_CALENDAR_EXCEPTIONS
(
    CALENDAR_DATE DATE PRIMARY KEY, -- Например, '2026-12-31'. Конкретная дата исключения.
    IS_CLOSED     BOOLEAN   NOT NULL DEFAULT FALSE,
    OPEN_TIME     TIME,
    CLOSE_TIME    TIME,
    CREATED_AT    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Гарантируем валидность временного интервала исключения на уровне ядра СУБД
    CONSTRAINT CHK_SALON_EXCEPTION_CHRONOLOGY CHECK (IS_CLOSED = TRUE OR CLOSE_TIME > OPEN_TIME)
);
-- ============================================
-- ТАБЛИЦА AI_CONVERSATIONAL_CONTEXTS
-- ============================================
-- где под каждого клиента выделена ровно одна строка, обновляемая
-- по принципу Upsert (ON DUPLICATE KEY UPDATE).
--
-- Зачем: Чтобы избежать повторного полного парсинга всей текстовой истории сообщений из
-- таблицы MESSAGE_TRACES при каждом новом ответе пользователя.
-- Попытка восстанавливать состояние стейт-машины из сырого текста логов ресурсоемка
-- (требует огромного окна контекста LLM) и непредсказуема (модель может «галлюцинировать»
-- и забывать старые слоты).
-- Хранение структуры слотов в виде единого текстового JSON-поля (TEXT) вместо плоских
-- колонок обусловлено Фазой 1: требования бизнеса меняются еженедельно. Если завтра
-- добавятся новые слоты (например, client_phone или promo_code), вам не придется писать
-- SQL-миграции Flyway/Liquibase и перегенерировать метамодель jOOQ — достаточно будет
-- просто расширить Java Record.
CREATE TABLE AI_CONVERSATIONAL_CONTEXTS
(
    CLIENT_ID BIGINT PRIMARY KEY, -- Связь 1:1 с таблицей CLIENTS (chat_id / platformId)
    CURRENT_STATE VARCHAR (32) NOT NULL DEFAULT 'INIT',
    SERIALIZED_SLOTS TEXT NOT NULL, -- Иммутабельный JSON-пакет слотов (avaje-jsonb)
    UPDATED_AT TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT FK_CONTEXT_BELONGS_TO_CLIENT FOREIGN KEY (CLIENT_ID)
    REFERENCES CLIENTS (ID) ON DELETE CASCADE
);

CREATE INDEX IX_AI_CONTEXT_UPDATED_AT ON AI_CONVERSATIONAL_CONTEXTS (UPDATED_AT);
