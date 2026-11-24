package com.dkrb56.bufferzone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Test

class SearchCancelTest {
    @Test
    fun cancelledSearchDoesNotReturn() = runBlocking {
        var after: Map<String, List<Item>>? = null
        val job = launch {
            after = searchOrEmpty("ba") { withContext(Dispatchers.IO) { Thread.sleep(300); emptyMap() } }
        }
        delay(50)
        job.cancel()
        job.join()
        assertNull("a cancelled search must not return a result", after)
    }
}
