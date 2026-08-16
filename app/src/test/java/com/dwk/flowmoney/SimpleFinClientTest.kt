package com.dwk.flowmoney

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.cert.Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class SimpleFinClientTest {
    @Test
    fun strictUtf8ReaderAcceptsExactLimitAndRejectsMaxPlusOne() {
        assertEquals("1234", StrictUtf8Reader.read(ByteArrayInputStream("1234".toByteArray()), 4))
        assertThrows(InputTooLargeException::class.java) {
            StrictUtf8Reader.read(ByteArrayInputStream("12345".toByteArray()), 4)
        }
    }

    @Test
    fun strictUtf8ReaderRejectsAdvertisedAndFalseSmallLengths() {
        var closed = false
        val advertisedOversize =
            object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        assertThrows(InputTooLargeException::class.java) {
            StrictUtf8Reader.read(advertisedOversize, 4, advertisedLength = 5)
        }
        assertTrue(closed)
        assertThrows(InputTooLargeException::class.java) {
            StrictUtf8Reader.read(ByteArrayInputStream("12345".toByteArray()), 4, advertisedLength = 1)
        }
    }

    @Test
    fun strictUtf8ReaderHandlesUnknownLengthAndZeroLengthRead() {
        val source =
            object : InputStream() {
                private val delegate = ByteArrayInputStream("ok".toByteArray())
                private var returnZero = true

                override fun read(): Int = delegate.read()

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int =
                    if (returnZero) {
                        0.also { returnZero = false }
                    } else {
                        delegate.read(buffer, offset, length)
                    }
            }
        assertEquals("ok", StrictUtf8Reader.read(source, 2))
    }

    @Test
    fun strictUtf8ReaderRejectsMalformedUtf8AndClosesStream() {
        var closed = false
        val source =
            object : ByteArrayInputStream(byteArrayOf(0xC3.toByte())) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        assertThrows(MalformedUtf8Exception::class.java) { StrictUtf8Reader.read(source, 4) }
        assertTrue(closed)
    }

    @Test
    fun validatesOptionalPayeeAtProviderStringLimit() {
        val client = SimpleFinClient()
        val exactLimitPayee = "x".repeat(16_384)

        assertNull(client.validateProviderPayee(null))
        assertEquals(exactLimitPayee, client.validateProviderPayee(exactLimitPayee))
        val error =
            assertThrows(SimpleFinException::class.java) {
                client.validateProviderPayee(exactLimitPayee + "x")
            }
        assertEquals("SimpleFIN response exceeds allowed limits", error.message)
    }

    @Test
    fun claim403IsTypedAsNonRetryableAuthenticationWithoutReadingBody() =
        runBlocking {
            lateinit var connection: FakeHttpsConnection
            val client =
                SimpleFinClient { url ->
                    FakeHttpsConnection(url, status = 403, body = "secret provider body").also {
                        it.failIfBodyIsRead = true
                        connection = it
                    }
                }

            val failure =
                assertThrows(SimpleFinException::class.java) {
                    runBlocking { client.claim(encodedClaimUrl()) }
                }

            assertEquals(SimpleFinFailureKind.AUTHENTICATION, failure.kind)
            assertFalse(failure.retryable)
            assertFalse(failure.message!!.contains("secret"))
            assertFalse(connection.bodyWasRead)
            assertNull(SimpleFinClient.claimStatusException(400))
        }

    @Test
    fun injectedHttpsConnectionClaimsWithoutLoopbackTls() =
        runBlocking {
            var openedUrl: URL? = null
            val client =
                SimpleFinClient { url ->
                    openedUrl = url
                    FakeHttpsConnection(url, status = 200, body = ACCESS_URL)
                }

            assertEquals(ACCESS_URL, client.claim(encodedClaimUrl()))
            assertEquals(CLAIM_URL, openedUrl.toString())
        }

    @Test
    fun injectedHttpConnectionConfiguresAccountsRequestWithoutLoopbackTls() =
        runBlocking {
            lateinit var connection: FakeHttpConnection
            val client =
                SimpleFinClient { url ->
                    FakeHttpConnection(url, status = 403, body = "secret provider body").also {
                        it.failIfBodyIsRead = true
                        connection = it
                    }
                }

            val failure =
                assertThrows(SimpleFinException::class.java) {
                    runBlocking { client.accounts(ACCESS_URL, 1, 2) }
                }

            assertEquals(SimpleFinFailureKind.AUTHENTICATION, failure.kind)
            assertEquals("GET", connection.requestMethod)
            assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("user:password".toByteArray()),
                connection.getRequestProperty("Authorization"),
            )
            assertFalse(connection.url.toString().contains("password"))
            assertFalse(connection.bodyWasRead)
        }

    @Test
    fun clientPreservesTypedTimeoutCauseAndRedactsItsMessage() =
        runBlocking {
            val timeout = SocketTimeoutException("secret timeout at $CLAIM_URL")
            val client =
                SimpleFinClient { url ->
                    FakeHttpsConnection(url, status = 200, body = ACCESS_URL, responseFailure = timeout)
                }

            val failure =
                assertThrows(SimpleFinException::class.java) {
                    runBlocking { client.claim(encodedClaimUrl()) }
                }

            assertEquals(SimpleFinFailureKind.TIMEOUT, failure.kind)
            assertTrue(failure.retryable)
            assertTrue(failure.cause === timeout)
            assertFalse(failure.message!!.contains("secret"))
            assertFalse(failure.message!!.contains(CLAIM_URL))
        }

    @Test
    fun provider5xxIsTypedWithoutReadingOrExposingBody() =
        runBlocking {
            lateinit var connection: FakeHttpsConnection
            val client =
                SimpleFinClient { url ->
                    FakeHttpsConnection(url, status = 503, body = "secret provider body").also {
                        it.failIfBodyIsRead = true
                        connection = it
                    }
                }

            val failure =
                assertThrows(SimpleFinException::class.java) {
                    runBlocking { client.claim(encodedClaimUrl()) }
                }

            assertEquals(SimpleFinFailureKind.PROVIDER_5XX, failure.kind)
            assertTrue(failure.retryable)
            assertFalse(failure.message!!.contains("secret"))
            assertFalse(connection.bodyWasRead)
        }

    @Test
    fun decodesStandardAndUrlSafeBase64AndRejectsMalformedInputs() {
        val client = SimpleFinClient()
        val bytes = CLAIM_URL.toByteArray(StandardCharsets.UTF_8)
        val standard = Base64.getEncoder().encodeToString(bytes)
        val urlSafe = Base64.getUrlEncoder().encodeToString(bytes)

        assertTrue(standard.contains('+'))
        assertTrue(urlSafe.contains('-'))
        assertEquals(CLAIM_URL, client.decodeSetupToken(standard))
        assertEquals(CLAIM_URL, client.decodeSetupToken(urlSafe))
        assertEquals(
            SimpleFinFailureKind.MALFORMED_TOKEN,
            assertThrows(SimpleFinException::class.java) { client.decodeSetupToken("%%%") }.kind,
        )
        assertEquals(
            SimpleFinFailureKind.MALFORMED_TOKEN,
            assertThrows(SimpleFinException::class.java) {
                client.decodeSetupToken(Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte())))
            }.kind,
        )
    }

    @Test
    fun acceptsHttpsAccessUrlWithUserinfo() {
        SimpleFinClient.validateAccessUrl("https://user:pass@example.com/simplefin/access")
    }

    @Test
    fun claimedAccessUrlAllowsExactLimitAndRejectsOverLimit() {
        val prefix = "https://user:pass@example.com/"
        val exact = prefix + "x".repeat(16_384 - prefix.length)
        assertEquals(exact, SimpleFinClient().claimedAccessUrl(" \n$exact\t "))

        val error =
            assertThrows(SimpleFinException::class.java) {
                SimpleFinClient().claimedAccessUrl(exact + "x")
            }
        assertEquals("SimpleFIN response exceeds allowed limits", error.message)
    }

    @Test
    fun rejectsNonHttpsAccessUrl() {
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.validateAccessUrl("http://user:pass@example.com/simplefin/access")
        }
    }

    @Test
    fun rejectsMissingUserinfoAccessUrl() {
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.validateAccessUrl("https://example.com/simplefin/access")
        }
    }

    @Test
    fun allowsOnlySameOriginHttpsAccountRedirects() {
        val current = URI("https://bridge.simplefin.org/simplefin/accounts?version=2")

        assertTrue(
            SimpleFinClient
                .resolveAccountsRedirect(current, "/redirected/accounts")
                .toString()
                .startsWith("https://bridge.simplefin.org/redirected/accounts"),
        )
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.resolveAccountsRedirect(current, "https://attacker.example/accounts")
        }
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.resolveAccountsRedirect(current, "http://bridge.simplefin.org/accounts")
        }
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.resolveAccountsRedirect(current, "https://user:pass@bridge.simplefin.org/accounts")
        }
    }

    private class FakeHttpConnection(
        url: URL,
        private val status: Int,
        body: String,
        private val responseFailure: IOException? = null,
    ) : HttpURLConnection(url) {
        private val responseBytes = body.toByteArray(StandardCharsets.UTF_8)
        var bodyWasRead = false
        var failIfBodyIsRead = false

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }

        override fun getInputStream(): InputStream {
            bodyWasRead = true
            check(!failIfBodyIsRead)
            return ByteArrayInputStream(responseBytes)
        }

        override fun getErrorStream(): InputStream? {
            bodyWasRead = true
            check(!failIfBodyIsRead)
            return ByteArrayInputStream(responseBytes)
        }

        override fun getContentLengthLong(): Long = responseBytes.size.toLong()
    }

    private class FakeHttpsConnection(
        url: URL,
        private val status: Int,
        body: String,
        private val responseFailure: IOException? = null,
    ) : HttpsURLConnection(url) {
        private val responseBytes = body.toByteArray(StandardCharsets.UTF_8)
        var bodyWasRead = false
        var failIfBodyIsRead = false

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }

        override fun getInputStream(): InputStream {
            bodyWasRead = true
            check(!failIfBodyIsRead)
            return ByteArrayInputStream(responseBytes)
        }

        override fun getErrorStream(): InputStream? {
            bodyWasRead = true
            check(!failIfBodyIsRead)
            return ByteArrayInputStream(responseBytes)
        }

        override fun getContentLengthLong(): Long = responseBytes.size.toLong()

        override fun getCipherSuite(): String = "TLS_FAKE_WITH_AES_128_GCM_SHA256"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private fun encodedClaimUrl(): String = Base64.getEncoder().encodeToString(CLAIM_URL.toByteArray())

    private companion object {
        const val CLAIM_URL = "https://example.com/claim?token=~aa"
        const val ACCESS_URL = "https://user:password@bridge.simplefin.org/simplefin"
    }
}
