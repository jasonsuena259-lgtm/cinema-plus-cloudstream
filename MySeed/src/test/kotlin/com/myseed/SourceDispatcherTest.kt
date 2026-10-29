package com.myseed

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourceDispatcherTest {
    @Test fun fastSourcePublishesBeforeSlowSourceFinishes()=runBlocking {
        val first=CompletableDeferred<String>();val release=CompletableDeferred<Unit>()
        val work=launch {SourceDispatcher.run(listOf("slow","fast")) { item ->
            if(item=="slow")release.await()
            first.complete(item)
        }}
        assertEquals("fast",withTimeout(1000){first.await()})
        assertTrue(work.isActive)
        release.complete(Unit);work.join()
    }
    @Test fun deadlineCancelsOutstandingSourcesWithoutDiscardingResults()=runBlocking {
        val results=mutableListOf<String>();var cancelled=false
        SourceDispatcher.run(listOf("fast","stuck"),totalMillis=150,sourceMillis=1000) { item ->
            if(item=="stuck")try{awaitCancellation()}finally{cancelled=true}
            results+=item
        }
        assertEquals(listOf("fast"),results);assertTrue(cancelled)
    }
    @Test fun failedSourceDoesNotStopOtherSources()=runBlocking {
        val results=mutableListOf<String>()
        SourceDispatcher.run(listOf("broken","working")) { if(it=="broken")error("offline");results+=it }
        assertEquals(listOf("working"),results)
    }
    @Test fun callerCancellationIsNotSwallowed()=runBlocking {
        val started=CompletableDeferred<Unit>();var reachedEnd=false
        val work=launch {SourceDispatcher.run(listOf(1)){started.complete(Unit);awaitCancellation()};reachedEnd=true}
        started.await();work.cancelAndJoin();assertFalse(reachedEnd)
    }
}
