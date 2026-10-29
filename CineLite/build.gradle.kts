version = 4
cloudstream {
    description = "CineLite v4 — proxy HLS ext query recognition, embedded player fallback and subtitle capture; playback still needs live verification"
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "en"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Jsoup exposes JSpecify nullability annotations on Element.parent()/selectFirst().
    implementation("org.jspecify:jspecify:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
}
