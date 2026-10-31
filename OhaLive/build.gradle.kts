version = 1
cloudstream {
    description = "OHA Live — قنوات مباشرة عبر واجهة oha.to الجديدة (تجريبي)"
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Live")
    language = "ar"
}

// CloudStream's live-stream load-response helper is compiled for JVM 11.
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
