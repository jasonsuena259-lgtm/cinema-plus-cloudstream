package com.cimanowvip

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Each source publishes independently; slow sources cannot extend the whole request forever. */
internal object VipDispatcher {
    suspend fun <T> run(items:List<T>, totalMillis:Long=25000, sourceMillis:Long=18000,
                        concurrency:Int=6, block:suspend (T)->Unit) {
        val gate=Semaphore(concurrency)
        withTimeoutOrNull(totalMillis) {
            coroutineScope {
                items.map { item -> launch {
                    gate.withPermit {
                        withTimeoutOrNull(sourceMillis) {
                            try { block(item) }
                            catch(e:CancellationException) { throw e }
                            catch(_:Exception) { }
                            catch(_:LinkageError) { }
                        }
                    }
                }}.joinAll()
            }
        }
    }
}
