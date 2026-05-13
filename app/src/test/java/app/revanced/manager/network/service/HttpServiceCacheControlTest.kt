package app.revanced.manager.network.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpServiceCacheControlTest {

    @Test
    fun `null and blank values yield null`() {
        assertNull(parseMaxAgeMillis(null))
        assertNull(parseMaxAgeMillis(""))
        assertNull(parseMaxAgeMillis("   "))
    }

    @Test
    fun `max-age in seconds is converted to millis`() {
        assertEquals(120_000L, parseMaxAgeMillis("max-age=120"))
        assertEquals(0L, parseMaxAgeMillis("max-age=0"))
    }

    @Test
    fun `max-age is parsed when mixed with other directives and casing`() {
        assertEquals(60_000L, parseMaxAgeMillis("public, Max-Age=60, must-revalidate"))
    }

    @Test
    fun `negative or unparseable max-age yields null`() {
        assertNull(parseMaxAgeMillis("max-age=-1"))
        assertNull(parseMaxAgeMillis("max-age=abc"))
        assertNull(parseMaxAgeMillis("max-age="))
    }

    @Test
    fun `header without max-age yields null`() {
        assertNull(parseMaxAgeMillis("public, must-revalidate"))
    }
}
