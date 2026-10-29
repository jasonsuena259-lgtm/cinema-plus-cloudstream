version = 1

cloudstream {
    description = "MySeed CloudStream provider"
    authors = listOf("MySeed Provider")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
}
dependencies { testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
