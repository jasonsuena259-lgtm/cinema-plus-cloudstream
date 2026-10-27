version = 6
cloudstream {
    description = "Cinema Plus — independent provider"
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
dependencies { testImplementation("junit:junit:4.13.2") }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0") }

// CloudStream's Gradle plugin downloads its API jar here before compilation.
// Include it for JVM contract tests that exercise MainAPI responses themselves.
dependencies {
    testImplementation(files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
}

dependencies { testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
