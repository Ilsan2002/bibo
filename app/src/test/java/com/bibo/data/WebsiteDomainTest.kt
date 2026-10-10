package com.bibo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebsiteDomainTest {
    @Test fun stripsCredentialsPortPathAndFragmentWithoutRetainingSensitiveUrlText() {
        assertEquals("example.com", extractDomain("https://person:secret@www.Example.com:8443/private?token=secret#section"))
        assertEquals("example.com", extractDomain("example.com#private"))
    }
    @Test fun validatesUrlLikeTextAndRejectsInternalPagesAndSearchQueries() {
        listOf("search terms.com", "chrome://newtab", "about:blank", "mailto:user@example.com", "localhost", "127.0.0.1", "-bad.example", "example..com").forEach { assertNull(it, extractDomain(it)) }
    }
    @Test fun handlesInternationalDomainNamesAndTrailingDnsDot() {
        assertEquals("xn--bcher-kva.de", extractDomain("https://bücher.de/"))
        assertEquals("example.com", extractDomain("EXAMPLE.COM."))
    }
}
