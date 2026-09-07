plugins {
    id("buildlogic.java-library-conventions")
    // Подключаем плагин кодогенерации jOOQ из нашего TOML каталога версий
    alias(libs.plugins.jooq.codegen)
}

group = project.group
version = project.version

dependencies {
    implementation(project(":salon-api"))
    implementation(libs.avaje.validator.constraints)
    implementation(libs.avaje.jsonb)
    implementation(libs.jooq.core)
    jooqCodegen(libs.jooq.meta.extensions)

    // FIX: Provide missing compile-time metadata targets to the compiler
    compileOnly(libs.api.jakarta.xml.bind)
    compileOnly(libs.api.javax.annotation)
    // Adds the missing When.MAYBE types
    compileOnly(libs.api.jsr305)

    // В продакшене используем ТОЛЬКО реальный PostgreSQL
    //runtimeOnly(libs.db.postgres)

    // H2 доступен ИСКЛЮЧИТЕЛЬНО во время запуска тестов
    //testRuntimeOnly(libs.db.h2)
    runtimeOnly(libs.db.h2)

    implementation(libs.db.hikari)

    // Добавляем в слой данных для чтения проперти
    implementation(libs.avaje.config)

    // Avaje DI
    implementation(libs.avaje.inject)
    annotationProcessor(libs.avaje.inject.generator)
    testImplementation(libs.avaje.inject.test)
}

// ЗАФИКСИРУЕМ ПУТЬ К ГЕНЕРАЦИИ В ПЕРЕМЕННУЮ
val jooqGeneratedDir = "build/generated-sources/jooq"

// Конфигурация генератора jOOQ из DDL (SQL) файлов миграции
jooq {
    configuration {
        generator {
            name = "org.jooq.codegen.JavaGenerator"
            database {
                // Указываем jOOQ собирать метаданные из SQL-скриптов, а не из живой БД
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "${projectDir}/src/main/resources/db/migration"
                    }
                    property {
                        key = "sort"
                        value = "semantic"
                    }
                }
                forcedTypes {
                    forcedType {
                        // Указываем полное имя вашего Java-enum из модуля salon-api
                        userType = "salon.api.model.AppointmentStatus"
                        // Указываем стандартный конвертер jOOQ для строк
                        converter = "org.jooq.impl.EnumConverter"
                        // Применяем это правило строго к полю STATUS таблицы APPOINTMENTS
                        includeExpression = "APPOINTMENTS\\.STATUS"
                    }
                }

            }
            target {
                // Куда складывать сгенерированные Java-классы таблиц
                packageName = "salon.db.jooq.generated"
                directory = jooqGeneratedDir // Используем переменную
            }
        }
    }
}

// =====================================================================
// ИСПРАВЛЕНИЕ ОШИБКИ СБОРКИ (Source Sets & Task Ordering)
// =====================================================================

sourceSets {
    main {
        java {
            // Говорим компилятору Java включить сгенерированную jOOQ папку в Module Path
            srcDir(jooqGeneratedDir)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Гарантируем, что jOOQ сгенерирует файлы ДО того, как начнется компиляция Java
    dependsOn(tasks.named("jooqCodegen"))
}
