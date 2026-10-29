version = 3
cloudstream {
    description = "Akwam.ss v3 — independent movie, TV, episode, quality, verified MP4 and player-source provider (experimental)"
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "ar"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jspecify:jspecify:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
}
