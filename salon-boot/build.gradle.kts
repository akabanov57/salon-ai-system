plugins {
    id("buildlogic.java-application-conventions")
}

group = project.group
version = project.version

dependencies {
    // Connect all operational pluggable feature adapters
    implementation(project(":salon-api"))
    implementation(project(":salon-db-jooq"))
    implementation(project(":salon-ai-engine"))
    implementation(project(":salon-web-http"))
    // implementation(project(":salon-gui-vaadin")) -- Add later when UI module is ready

    // Central application engine server
    implementation(libs.avaje.jex)

    // Compile-time Dependency Injection container
    implementation(libs.avaje.inject)
    annotationProcessor(libs.avaje.inject.generator)
}

application {
    mainModule.set("salon.boot")
    mainClass.set("salon.boot.MainApplication")
    // FIX: Tells the runtime JVM (both for local runs and generated shell start scripts)
    // that salon.ai.engine has absolute authority to read non-modular libraries from the classpath
    applicationDefaultJvmArgs = listOf(
        "--add-reads", "salon.ai.engine=ALL-UNNAMED"
    )
}

// =====================================================================
// PRODUCTION DISTRIBUTION BUILD PACKAGING MECHANICS (The secret/ folder)
// =====================================================================
distributions.main {
    contents {
        // Enforce the automated creation of the external 'secret' directory
        // inside the generated .zip/.tar distribution packages
        into("secret") {
            // Source the file directly from your modular resources workspace block
            from("src/main/resources/certs/salon-keystore.p12")

            // Optional: Copy the main properties file here too if you want to edit it externally!
            //from("src/main/resources/application.properties")
        }
    }
}

