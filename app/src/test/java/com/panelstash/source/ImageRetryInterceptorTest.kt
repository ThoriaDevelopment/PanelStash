package com.panelstash.source

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The reader has no other retry: Coil caches successes, never failures, and nothing re-issued a
 * request until the chapter was reopened. So this interceptor is the difference between a page
 * that fills in a moment later and a page that stays black, and its limits matter — retrying a
 * 404 for ever would only slow a genuinely missing page down.
 */
class ImageRetryInterceptorTest {

    private val request = Request.Builder().url("https://cdn.example.com/page-1.webp").build()

    private fun response(code: Int): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("stub")
        .body("x".toResponseBody())
        .build()

    /** One step per network attempt, in order: a response to return, or a throw. */
    private fun chainOf(vararg steps: () -> Response): Interceptor.Chain {
        val call = mockk<Call>()
        every { call.isCanceled() } returns false
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.call() } returns call
        val remaining = steps.toMutableList()
        every { chain.proceed(any()) } answers { remaining.removeFirst().invoke() }
        return chain
    }

    private val interceptor = Network.ImageRetryInterceptor(attempts = 3, baseDelayMs = 1)

    @Test
    fun `a rate limited response is retried until it succeeds`() {
        val chain = chainOf({ response(429) }, { response(503) }, { response(200) })

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        verify(exactly = 3) { chain.proceed(any()) }
    }

    @Test
    fun `a dropped connection is retried`() {
        val chain = chainOf({ throw IOException("unexpected end of stream") }, { response(200) })

        assertEquals(200, interceptor.intercept(chain).code)
        verify(exactly = 2) { chain.proceed(any()) }
    }

    @Test
    fun `a missing image is not retried`() {
        val chain = chainOf({ response(404) })

        assertEquals(404, interceptor.intercept(chain).code)
        verify(exactly = 1) { chain.proceed(any()) }
    }

    @Test
    fun `retrying stops after the last attempt`() {
        val chain = chainOf({ response(503) }, { response(503) }, { response(503) })

        assertEquals(503, interceptor.intercept(chain).code)
        verify(exactly = 3) { chain.proceed(any()) }
    }

    @Test
    fun `a connection that keeps failing is reported, not retried for ever`() {
        val chain = chainOf(
            { throw IOException("timeout") },
            { throw IOException("timeout") },
            { throw IOException("timeout") },
        )

        assertThrows(IOException::class.java) { interceptor.intercept(chain) }
        verify(exactly = 3) { chain.proceed(any()) }
    }
}
