package com.rhshourav.tenchar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayAddressTest {
    private val onrender = "https://tenchar-relay.onrender.com"

    @Test
    fun defaultRelayIsAlreadyInCanonicalForm() {
        assertEquals(Constants.DEFAULT_RELAY_URL, RelayAddress.normalise(Constants.DEFAULT_RELAY_URL))
        assertEquals(onrender, Constants.DEFAULT_RELAY_URL)
    }

    @Test
    fun fallbackRelayIsCanonicalAndAcceptsATrailingSlash() {
        assertEquals(Constants.FALLBACK_RELAY_URL, RelayAddress.normalise(Constants.FALLBACK_RELAY_URL))
        assertEquals(Constants.FALLBACK_RELAY_URL, RelayAddress.normalise("https://tenchar-relay.tenchar.workers.dev/"))
    }

    @Test
    fun cleansUpWhatPeopleTypeOrPaste() {
        assertEquals(onrender, RelayAddress.normalise("$onrender/"))
        assertEquals(onrender, RelayAddress.normalise("  HTTPS://TenChar-Relay.OnRender.com/  "))
        assertEquals(onrender, RelayAddress.normalise("tenchar-relay.onrender.com"))
    }

    @Test
    fun keepsAnUnusualPortButDropsTheDefaultOne() {
        assertEquals("https://relay.example.com:8443", RelayAddress.normalise("https://relay.example.com:8443"))
        assertEquals("https://relay.example.com", RelayAddress.normalise("https://relay.example.com:443"))
    }

    @Test
    fun acceptsIpv6Literals() {
        assertEquals("https://[2001:db8::1]", RelayAddress.normalise("https://[2001:db8::1]"))
        assertEquals("https://[2001:db8::1]:8443", RelayAddress.normalise("https://[2001:db8::1]:8443"))
    }

    @Test
    fun refusesAnythingThatIsNotPlainHttps() {
        assertNull(RelayAddress.normalise("http://relay.example.com"))
        assertNull(RelayAddress.normalise("ftp://relay.example.com"))
        assertNull(RelayAddress.normalise("javascript:alert(1)"))
    }

    @Test
    fun refusesCredentialsPathsQueriesAndFragments() {
        assertNull(RelayAddress.normalise("https://user:pw@relay.example.com"))
        // The classic trick: the real host is what follows the "@".
        assertNull(RelayAddress.normalise("https://tenchar-relay.onrender.com@evil.example.com"))
        assertNull(RelayAddress.normalise("https://relay.example.com/v1"))
        assertNull(RelayAddress.normalise("https://relay.example.com?x=1"))
        assertNull(RelayAddress.normalise("https://relay.example.com#a"))
    }

    @Test
    fun refusesBadHostsAndPorts() {
        assertNull(RelayAddress.normalise("https://localhost"))
        assertNull(RelayAddress.normalise("https://relay_x.example.com"))
        assertNull(RelayAddress.normalise("https://r\u00e9lay.example.com"))
        assertNull(RelayAddress.normalise("https://relay.example.com."))
        assertNull(RelayAddress.normalise("https://-bad.example.com"))
        assertNull(RelayAddress.normalise("https://relay.example.com:0"))
        assertNull(RelayAddress.normalise("https://relay.example.com:99999"))
        assertNull(RelayAddress.normalise("https://relay.example.com:abc"))
    }

    @Test
    fun refusesEmptyWhitespaceAndOverlongInput() {
        assertNull(RelayAddress.normalise(""))
        assertNull(RelayAddress.normalise("   "))
        assertNull(RelayAddress.normalise("https://"))
        assertNull(RelayAddress.normalise("https://exa mple.com"))
        assertNull(RelayAddress.normalise("https://" + "a".repeat(300) + ".com"))
    }

    @Test
    fun isValidMatchesNormalise() {
        assertTrue(RelayAddress.isValid(onrender))
        assertFalse(RelayAddress.isValid("http://relay.example.com"))
    }
}