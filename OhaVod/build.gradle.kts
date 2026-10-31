version = 1
cloudstream {
    description = "OHA Movies & Series — films, seasons and episode servers"
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
