package com.shilapi.xcertplay.network

import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBonjourFallbackTest {
    @Test fun kitkatAndModernAndroidUseBundledMdns() {
        assertTrue(useBundledCarPlayMdns(19))
        assertTrue(useBundledCarPlayMdns(20))
        assertFalse(useBundledCarPlayMdns(21))
        assertFalse(useBundledCarPlayMdns(28))
        assertTrue(useBundledCarPlayMdns(29))
    }

    @Test fun oneFamilyBindFailurePreservesOtherFamily() {
        val ipv4 = InetAddress.getByName("192.168.43.1")
        val ipv6 = InetAddress.getByName("fe80::2")
        val failures = mutableListOf<InetAddress>()
        val bound = bindAvailableMdnsFamilies<String>(
            listOf(ipv4, ipv6),
            bind = { addr ->
                if (addr == ipv6) throw IOException("EADDRINUSE")
                "bound-${addr.hostAddress}"
            },
            onFailure = { addr, _ -> failures += addr },
        )
        assertEquals(listOf(ipv4), bound.map { it.first })
        assertEquals(listOf(ipv6), failures)
    }

    @Test fun bothFamiliesFailReturnsEmptyAndBothReasons() {
        val addresses = listOf(
            InetAddress.getByName("192.168.43.1"),
            InetAddress.getByName("fe80::2"),
        )
        val failures = mutableListOf<InetAddress>()
        val bound = bindAvailableMdnsFamilies<String>(
            addresses,
            bind = { _: InetAddress -> throw IOException("port unavailable") },
            onFailure = { address, _ -> failures += address },
        )
        assertTrue(bound.isEmpty())
        assertEquals(addresses, failures)
    }

    /** The port held by the previous instance's close frees within the retry budget: bind succeeds. */
    @Test fun anAllFailedBindRetriesUntilThePortIsFree() {
        val delays = mutableListOf<Long>()
        var calls = 0
        val bound = bindMdnsWithRetry(
            maxAttempts = 4,
            retryDelayMillis = 750L,
            sleep = { delays += it },
            attempt = {
                calls += 1
                if (calls < 3) emptyList() else listOf("bound")
            },
        )
        assertEquals(listOf("bound"), bound)
        assertEquals(3, calls)
        assertEquals(listOf(750L, 750L), delays)
    }

    @Test fun anAllFailedBindGivesUpAfterTheAttemptBudget() {
        val delays = mutableListOf<Long>()
        val bound = bindMdnsWithRetry<String>(
            maxAttempts = 4,
            retryDelayMillis = 750L,
            sleep = { delays += it },
            attempt = { emptyList() },
        )
        assertTrue(bound.isEmpty())
        assertEquals("no delay precedes the first attempt", listOf(750L, 750L, 750L), delays)
    }

    @Test fun aFirstAttemptSuccessDoesNotRetry() {
        val delays = mutableListOf<Long>()
        var calls = 0
        val bound = bindMdnsWithRetry(
            maxAttempts = 4,
            retryDelayMillis = 750L,
            sleep = { delays += it },
            attempt = { calls += 1; listOf("bound") },
        )
        assertEquals(listOf("bound"), bound)
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }
}
