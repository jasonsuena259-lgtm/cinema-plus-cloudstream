package com.cimanowvip
import org.junit.Test
import org.junit.Assert.*
class VipWatchLinksTest {
    private val base=VipParser.BASE
    @Test fun acceptsOnlyAnAuthorizedSiteWatchUrl(){
        val links=VipWatchLinks()
        for(url in listOf("https://evil.test/episode/watching/?token=test","http://vip.cimanowinc.com/episode/watching/?token=test","$base/episode/","$base/episode/watching/","$base/episode/watching/?token="))assertFalse(url,links.put(url))
        assertTrue(links.put("$base/episode/watching/?token=test"));assertEquals("$base/episode/watching/?token=test",links.get("$base/episode/"))
    }
    @Test fun cannotMixEpisodesOrImportCookies(){
        val links=VipWatchLinks();assertFalse(links.put("$base/one/watching/?token=test","$base/two/"))
        assertFalse(links.put("Cookie: PHPSESSID=example"));assertNull(links.get("$base/one/"))
    }
    @Test fun linksExpireAndAreNotSharedAcrossInstances(){
        var time=0L;val links=VipWatchLinks({time},100)
        links.put("$base/one/watching/?token=test");assertNotNull(links.get("$base/one/"));assertNull(VipWatchLinks().get("$base/one/"))
        time=100;assertNull(links.get("$base/one/"))
    }
    @Test fun percentEncodingCaseDoesNotChangeEpisodeIdentity(){
        val links=VipWatchLinks();assertTrue(links.put("$base/%d9%85/watching/?token=test","$base/%D9%85/"));assertNotNull(links.get("$base/م/"))
    }
    @Test fun boundedMemoryEvictsOldLinks(){
        val links=VipWatchLinks();for(i in 0..16)assertTrue(links.put("$base/$i/watching/?token=test"))
        assertNull(links.get("$base/0/"));assertNotNull(links.get("$base/16/"))
    }
}
