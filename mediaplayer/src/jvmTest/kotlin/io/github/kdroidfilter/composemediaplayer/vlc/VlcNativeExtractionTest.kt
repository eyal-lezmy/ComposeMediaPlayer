package io.github.kdroidfilter.composemediaplayer.vlc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extraction cache is reused across runs *and* across bundle versions, so what it is allowed to
 * keep is the whole correctness question: a truncated plugin makes libVLC report "no demux modules
 * matched" and every MPEG-TS stream fail, naming nothing.
 *
 * Runs only where the natives actually ship (macOS arm64 today); elsewhere `extractNatives` throws
 * `UnsupportedOperationException` by design and there is nothing to assert.
 */
class VlcNativeExtractionTest {

    private fun bundledPlatformOrSkip(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val supported = os.contains("mac") && (arch == "aarch64" || arch == "arm64")
        if (!supported) println("no bundled libVLC for $os/$arch — skipping extraction test")
        return supported
    }

    private fun extractInto(dir: File): File = VlcNativeInit.extractNatives(dir)

    @Test
    fun replacesATruncatedPlugin() {
        if (!bundledPlatformOrSkip()) return
        val cache = createTempDir()
        try {
            extractInto(cache)
            val plugin = File(cache, "plugins").listFiles().orEmpty().first { it.name.endsWith(".dylib") }
            val fullSize = plugin.length()
            assertTrue(fullSize > 0, "extraction produced an empty ${plugin.name}")

            // What a killed process leaves behind: a file that exists and is non-empty, but short.
            plugin.writeBytes(plugin.readBytes().copyOf((fullSize / 2).toInt()))
            extractInto(cache)

            assertEquals(fullSize, plugin.length(), "${plugin.name} was kept truncated")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun removesAPluginTheBundleNoLongerShips() {
        if (!bundledPlatformOrSkip()) return
        val cache = createTempDir()
        try {
            extractInto(cache)
            val stale = File(File(cache, "plugins"), "libgpl_tainted_plugin.dylib")
            stale.writeBytes(ByteArray(16))

            extractInto(cache)

            assertFalse(stale.exists(), "a plugin dropped from the bundle survived in the cache")
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("vlc-extract-test").toFile()
}
