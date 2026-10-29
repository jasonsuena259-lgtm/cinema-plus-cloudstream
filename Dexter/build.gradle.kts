version = 3
cloudstream {
    description = "Dexter.pw v3: API-backed streams, real Arabic VTT subtitle lookup and fallback movie/series posters; Cloud3 compatibility testing."
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "en"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jspecify:jspecify:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
}
