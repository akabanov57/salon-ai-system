plugins {
    id("buildlogic.java-library-conventions")
}

group = project.group
version = project.version

dependencies {
    implementation(project(":salon-api"))
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.core)
    implementation(libs.langchain4j.ollama)
    // FIX: Clean, catalog-driven Jackson core data-binding engine setup
    runtimeOnly(libs.jackson.core)
    runtimeOnly(libs.jackson.databind)

    implementation(libs.avaje.http.client) // Используем для кастомных интеграций

    // Avaje DI
    implementation(libs.avaje.inject)
    annotationProcessor(libs.avaje.inject.generator)

    // Core utility configurations mapping
    implementation(libs.avaje.config)

    testImplementation(libs.test.mockito)
}

// =====================================================================
// ИСПРАВЛЕНО: Применяем флаговый мост СТРОГО к компиляции основного кода,
// чтобы компилятор тестов (testClasses) не выдавал предупреждение о нехватке модуля.
// =====================================================================
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(listOf(
        "--add-reads", "salon.ai.engine=ALL-UNNAMED"
    ))
}

