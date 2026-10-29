version = 1
cloudstream {
    description = "Dexter.pw experimental: independent movie and TV catalogue, verified HLS/MP4/MKV candidates, real episode and subtitle links; live Cloudflare access may be blocked."
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
