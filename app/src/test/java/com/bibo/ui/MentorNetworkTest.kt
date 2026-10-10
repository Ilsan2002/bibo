package com.bibo.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class MentorNetworkTest {
    @Test fun cancellationClosesAStalledCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        val network = MentorNetwork(client)
        val job = launch(Dispatchers.IO) { network.request(Request.Builder().url(server.url("/")).build()) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue("Cancellation must reach the actual OkHttp call", cancelled.await(2, TimeUnit.SECONDS))
        server.shutdown()
    }
    @Test fun cancellationClosesAStalledResponseBody() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("a body that never arrives promptly").setBodyDelay(2, TimeUnit.SECONDS))
        server.start()
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() { override fun canceled(call: Call) { cancelled.countDown() } }).build()
        val job = launch(Dispatchers.IO) { MentorNetwork(client).request(Request.Builder().url(server.url("/")).build()) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        delay(100)
        job.cancelAndJoin()
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        server.shutdown()
    }
    @Test fun providerErrorsPreserveStatusWithoutLeakingBody() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(401).setBody("private upstream text")); server.start()
        try { MentorNetwork().request(Request.Builder().url(server.url("/")).build()); fail("Expected HTTP error") }
        catch (e: MentorHttpException) { assertEquals(401, e.code); assertFalse(e.message.orEmpty().contains("private")) }
        finally { server.shutdown() }
    }
    @Test fun pausedConnectionCannotQueueNewCallsUntilResumed() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("resumed")); server.start()
        val network = MentorNetwork(); network.pause()
        try {
            try { network.request(Request.Builder().url(server.url("/")).build()); fail("Paused request must fail locally") }
            catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("paused")) }
            assertEquals(0, server.requestCount)
            network.resume()
            assertEquals("resumed", network.request(Request.Builder().url(server.url("/")).build()))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
