package com.akwcam
import org.junit.Assert.*
import org.junit.Test
class AkwCamParserTest{
 private fun fixture(n:String)=AkwCamParser.doc(javaClass.getResource("/akwcam/$n.html")!!.readText(),"https://akw.cam/")
 @Test fun homeCards(){val c=AkwCamParser.cards(fixture("home"));assertTrue(c.size>10);assertEquals(c.size,c.map{it.url}.distinct().size);assertTrue(c.all{it.title.isNotBlank()})}
 @Test fun seriesCards(){val c=AkwCamParser.cards(fixture("shows"));assertTrue(c.isNotEmpty());assertTrue(c.all{it.series&&it.url.contains("/series/")})}
 @Test fun films(){assertTrue(AkwCamParser.cards(fixture("films")).all{!it.series})}
 @Test fun allEpisodes(){val e=AkwCamParser.episodes(fixture("series"));assertEquals(19,e.size);assertTrue(e.all{it.season==2});assertEquals(15,e.first().number);assertEquals(34,e.last().number);assertFalse(e.any{it.number==23});assertTrue(e.all{it.url.contains("lizzie-mcguire")})}
 @Test fun allSources(){assertEquals(7,AkwCamParser.sources(fixture("watch")).size);assertEquals(19,AkwCamParser.sources(fixture("download")).size)}
 @Test fun pagination(){assertTrue(AkwCamParser.next(fixture("films"),1));assertFalse(AkwCamParser.next(fixture("films"),17))}
 @Test fun missingMarkup(){val d=AkwCamParser.doc("<html>changed</html>",AkwCamParser.BASE);assertTrue(AkwCamParser.cards(d).isEmpty());assertTrue(AkwCamParser.sources(d).isEmpty());assertTrue(AkwCamParser.episodes(d).isEmpty())}
 @Test fun domainAndRelative(){assertEquals("https://akw.cam/films",AkwCamParser.site("/films"));assertNull(AkwCamParser.site("https://akw.cam.evil.test/films"));assertNull(AkwCamParser.site("javascript:alert(1)"))}
 @Test fun seasonNumbers(){assertEquals(1,AkwCamParser.season("الموسم الأول"));assertEquals(12,AkwCamParser.season("الموسم 12"));assertNull(AkwCamParser.season("غير معروف"))}
 @Test fun mediaDiscovery(){assertEquals(listOf("https://cdn.example/v.m3u8?token=a"),AkwCamParser.media("file:'//cdn.example/v.m3u8?token=a'",AkwCamParser.BASE))}
 @Test fun mirrorInventory(){val raw=javaClass.getResource("/akwcam/mirrors.json")!!.readText();val m=AkwCamParser.mirrors(raw,"https://share4max.org/iframe/id");assertEquals(21,m.size);assertTrue(m.any{it.name.contains("720p")});assertTrue(m.all{it.url.startsWith("https://")})}
 @Test fun malformedMirrors(){assertTrue(AkwCamParser.mirrors("not json",AkwCamParser.BASE).isEmpty())}
}
