package app.revanced.manager.network.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiResponseCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now: Long = 1_000_000L
    private val clock: () -> Long get() = { now }

    private fun newCache(defaultFreshnessMillis: Long = ApiResponseCache.DEFAULT_FRESHNESS_MILLIS) =
        ApiResponseCache(
            root = tmp.root,
            defaultFreshnessMillis = defaultFreshnessMillis,
            clock = clock,
        )

    @Test
    fun `read returns null when no entry exists`() = runTest {
        val cache = newCache()
        assertNull(cache.read("v5/about"))
    }

    @Test
    fun `read returns fresh entry within freshness window`() = runTest {
        val cache = newCache(defaultFreshnessMillis = 60_000L)
        cache.write("v5/about", "{\"ok\":true}")
        val entry = cache.read("v5/about")
        assertNotNull(entry)
        assertEquals("{\"ok\":true}", entry!!.body)
        assertTrue("entry must be fresh", entry.isFresh)
    }

    @Test
    fun `read returns stale entry once freshness window expires`() = runTest {
        val cache = newCache(defaultFreshnessMillis = 60_000L)
        cache.write("v5/about", "{\"ok\":true}")
        now += 60_001L
        val entry = cache.read("v5/about")
        assertNotNull(entry)
        assertEquals("{\"ok\":true}", entry!!.body)
        assertFalse("entry must be marked stale", entry.isFresh)
        assertTrue("ageMillis must be positive", entry.ageMillis >= 60_001L)
    }

    @Test
    fun `default freshness is 5 minutes`() {
        assertEquals(5L * 60L * 1000L, ApiResponseCache.DEFAULT_FRESHNESS_MILLIS)
    }

    @Test
    fun `caller-supplied freshForMillis is honoured`() = runTest {
        val cache = newCache(defaultFreshnessMillis = 60_000L)
        cache.write("v5/about", "{}", freshForMillis = 10_000L)
        now += 9_000L
        assertTrue("still within 10s window", cache.read("v5/about")!!.isFresh)
        now += 2_000L
        assertFalse("past 10s window", cache.read("v5/about")!!.isFresh)
    }

    @Test
    fun `cache file names are hashed and contain no path separators`() = runTest {
        val cache = newCache()
        cache.write("v5/patches/history/prerelease", "[]")
        val names = tmp.root.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(setOf(true), names.map { !it.contains('/') && !it.contains('\\') }.toSet())
        assertTrue(names.any { it.endsWith(".body") })
        assertTrue(names.any { it.endsWith(".meta") })
        val prefixes = names.map { it.substringBeforeLast('.') }.toSet()
        assertEquals(1, prefixes.size)
        assertEquals(64, prefixes.single().length)
    }

    @Test
    fun `different keys map to different files`() = runTest {
        val cache = newCache()
        cache.write("v5/about", "a")
        cache.write("v5/announcements", "b")
        val prefixes = tmp.root.listFiles().orEmpty()
            .map { it.name.substringBeforeLast('.') }.toSet()
        assertEquals(2, prefixes.size)
    }

    @Test
    fun `same key maps to same file across writes and overwrites body`() = runTest {
        val cache = newCache()
        cache.write("v5/about", "first")
        cache.write("v5/about", "second")
        val prefixes = tmp.root.listFiles().orEmpty()
            .map { it.name.substringBeforeLast('.') }.toSet()
        assertEquals(1, prefixes.size)
        assertEquals("second", cache.read("v5/about")!!.body)
    }

    @Test
    fun `empty body file is treated as missing`() = runTest {
        val cache = newCache()
        cache.write("v5/about", "")
        assertNull(cache.read("v5/about"))
    }

    @Test
    fun `hashed name does not leak the raw key`() = runTest {
        val cache = newCache()
        cache.write("v5/about", "x")
        val names = tmp.root.listFiles()!!.map { it.name }
        names.forEach { name ->
            assertNotEquals("v5_about.json", name)
            assertFalse("hashed name must not contain raw key fragment", name.contains("about"))
        }
    }

    @Test
    fun `body write is atomic - no torn reads`() = runTest {
        val cache = newCache()
        cache.write("v5/about", "complete")
        val tmpFiles = tmp.root.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("no .tmp files should remain after a successful write", tmpFiles.isEmpty())
        assertEquals("complete", cache.read("v5/about")!!.body)
    }
}
