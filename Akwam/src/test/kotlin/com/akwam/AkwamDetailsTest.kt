package com.akwam

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AkwamDetailsTest {
    @Test fun realHomepageAndReportedFilmRenderWhenServerAllowsRequest() = runBlocking {
        val original = app.baseClient
        app.baseClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val fixture = if (request.url.encodedPath == "/") "/home-cards.html" else "/saqr-details.html"
            val body = javaClass.getResource(fixture)!!.readText()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        try {
            val api = AkwamProvider()
            val home = api.getMainPage(1, MainPageRequest(name="Home", data="/", horizontalImages=false))
            val cards = home.items.flatMap { it.list }
            assertEquals(59, cards.size)
            assertTrue(cards.all { !it.posterUrl.isNullOrBlank() })
            val film = api.load("https://akwam.day/film/فيلم-صقر-وكناريا-2026") as MovieLoadResponse
            assertEquals("فيلم صقر وكناريا 2026",film.name)
            assertFalse(film.posterUrl.isNullOrBlank())
        } finally { app.baseClient = original }
    }
}
