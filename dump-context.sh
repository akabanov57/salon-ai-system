#!/bin/bash
# dump-context.sh
OUTPUT="AI_CONTEXT.md"
echo "# АКТУАЛЬНЫЙ КОНТЕКСТ ПРОЕКТА: SALON-AI-SYSTEM" > $OUTPUT
echo "Сгенерировано: $(date)" >> $OUTPUT

echo -e "\n## 1. ДОКУМЕНТАЦИЯ И ТРЕБОВАНИЯ" >> $OUTPUT
if [ -f "USE_CASES_RU.md" ]; then
    echo "### Use Cases:" >> $OUTPUT
    cat USE_CASES_RU.md >> $OUTPUT
fi

echo -e "\n## 2. СХЕМА БАЗЫ ДАННЫХ (jOOQ Source)" >> $OUTPUT
# Ищем V1__init.sql в модуле БД
find salon-db-jooq -name "V1__init.sql" -exec echo "Файл: {}" \; -exec cat {} >> $OUTPUT \;

echo -e "\n## 3. СЕТЕВОЙ СЛОЙ И КОНТРОЛЛЕРЫ" >> $OUTPUT
find salon-web-http -name "WebhookController.java" -exec echo "### WebhookController:" >> $OUTPUT \; -exec cat {} >> $OUTPUT \;
find salon-api -name "TelegramUpdateDto.java" -exec echo "### TelegramUpdateDto:" >> $OUTPUT \; -exec cat {} >> $OUTPUT \;

echo -e "\n## 4. СТРУКТУРА СБОРКИ (settings.gradle.kts)" >> $OUTPUT
cat settings.gradle.kts >> $OUTPUT

echo "Готово! Контекст сохранен в $OUTPUT. Передайте его содержимое ИИ."
