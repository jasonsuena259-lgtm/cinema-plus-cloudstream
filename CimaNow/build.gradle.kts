version = 1
cloudstream {
    description = "CimaNow العادي — دومين وبروكسي اختياري، نسخة اختبار"
    authors = listOf("Cinema Plus community")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
}
