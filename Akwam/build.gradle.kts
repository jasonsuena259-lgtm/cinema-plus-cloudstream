version = 2
cloudstream {
    description = "أكوام — إضافة مستقلة"
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
dependencies { testImplementation("junit:junit:4.13.2") }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0") }

dependencies {
    testImplementation(files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.withType<Test>().configureEach {
    if (System.getenv("AKWAM_LIVE_TEST") == "1") {
        listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { key ->
            System.getProperty(key)?.let { systemProperty(key, it) }
        }
    }
}

// CloudStream initializes its extractor registry even for newExtractorLink.
// These are test-only host application dependencies, not bundled plugin code.
dependencies {
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
}
