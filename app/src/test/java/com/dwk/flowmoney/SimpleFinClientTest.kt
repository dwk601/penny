package com.dwk.flowmoney

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleFinClientTest {
    @Test fun strictUtf8ReaderAcceptsExactLimitAndRejectsMaxPlusOne() {
        assertEquals("1234", StrictUtf8Reader.read(ByteArrayInputStream("1234".toByteArray()), 4))
        assertThrows(InputTooLargeException::class.java) {
            StrictUtf8Reader.read(ByteArrayInputStream("12345".toByteArray()), 4)
        }
    }

    @Test fun strictUtf8ReaderRejectsAdvertisedAndFalseSmallLengths() {
        var closed = false
        val advertisedOversize = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(InputTooLargeException::class.java) { StrictUtf8Reader.read(advertisedOversize, 4, advertisedLength = 5) }
        assertTrue(closed)
        assertThrows(InputTooLargeException::class.java) {
            StrictUtf8Reader.read(ByteArrayInputStream("12345".toByteArray()), 4, advertisedLength = 1)
        }
    }

    @Test fun strictUtf8ReaderHandlesUnknownLengthAndZeroLengthRead() {
        val source = object : InputStream() {
            private val delegate = ByteArrayInputStream("ok".toByteArray())
            private var returnZero = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                if (returnZero) 0.also { returnZero = false } else delegate.read(buffer, offset, length)
        }
        assertEquals("ok", StrictUtf8Reader.read(source, 2))
    }

    @Test fun strictUtf8ReaderRejectsMalformedUtf8AndClosesStream() {
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf(0xC3.toByte())) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        assertThrows(MalformedUtf8Exception::class.java) { StrictUtf8Reader.read(source, 4) }
        assertTrue(closed)
    }

    @Test fun claim403ReturnsNonRetryableOneUseTokenGuidance() {
        val error = SimpleFinClient.claimStatusException(403)!!

        assertTrue(error.message!!.contains("invalid or has already been used"))
        assertTrue(error.message!!.contains("Do not retry it"))
        assertTrue(error.message!!.contains("create a new setup token"))
        assertTrue(error.message!!.contains("possible compromise"))
    }

    @Test fun claim403GuidanceDoesNotAccessResponseBodyErrors() {
        var bodyAccessed = false
        val selected = SimpleFinClient.claimStatusException(403) ?: run {
            bodyAccessed = true
            SimpleFinException("provider body error")
        }

        assertFalse(bodyAccessed)
        assertFalse(selected.message!!.contains("provider body error"))
        assertNull(SimpleFinClient.claimStatusException(400))
    }

    @Test fun acceptsHttpsAccessUrlWithUserinfo() {
        SimpleFinClient.validateAccessUrl("https://user:pass@example.com/simplefin/access")
    }

    @Test fun claimedAccessUrlAllowsExactLimitAndRejectsOverLimit() {
        val prefix = "https://user:pass@example.com/"
        val exact = prefix + "x".repeat(16_384 - prefix.length)
        assertEquals(exact, SimpleFinClient().claimedAccessUrl(" \n$exact\t "))

        val error = assertThrows(SimpleFinException::class.java) {
            SimpleFinClient().claimedAccessUrl(exact + "x")
        }
        assertEquals("SimpleFIN response exceeds allowed limits", error.message)
    }

    @Test fun rejectsNonHttpsAccessUrl() {
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.validateAccessUrl("http://user:pass@example.com/simplefin/access")
        }
    }

    @Test fun rejectsMissingUserinfoAccessUrl() {
        assertThrows(SimpleFinException::class.java) {
            SimpleFinClient.validateAccessUrl("https://example.com/simplefin/access")
        }
    }

    @Test fun allowsOnlySameOriginHttpsAccountRedirects() {
        val current = URI("https://bridge.simplefin.org/simplefin/accounts?version=2")

        assertTrue(
            SimpleFinClient.resolveAccountsRedirect(current, "/redirected/accounts").toString()
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
}
