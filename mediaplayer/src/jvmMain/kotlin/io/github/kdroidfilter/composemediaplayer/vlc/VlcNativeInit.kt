package io.github.kdroidfilter.composemediaplayer.vlc

import com.sun.jna.Library
import com.sun.jna.Native
import io.github.kdroidfilter.composemediaplayer.util.buildLocalLogger
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.io.File

internal val vlcLogger = buildLocalLogger("VlcNativeInit")

/**
 * Loads the libVLC natives **bundled with the application** — never a user-installed VLC.
 *
 * The vendored libs (`resources/vlc/<platform>/`) reference `@rpath/libvlccore.dylib` but carry no
 * baked `LC_RPATH`, and the host process is `java` (no rpath either), so `@rpath` can't resolve on its
 * own. Fix: extract the libs + plugins to a cache dir, `System.load` **libvlccore first** by absolute
 * path — dyld then registers it under its install name `@rpath/libvlccore.dylib`, which satisfies both
 * libvlc and every plugin — point `jna.library.path` at libvlc for vlcj, and `VLC_PLUGIN_PATH` at the
 * plugins so libvlccore finds them without a system VLC.
 */
object VlcNativeInit {

    private interface LibC : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    @Volatile
    private var factory: MediaPlayerFactory? = null

    /** libVLC args: quiet, and skip the on-disk plugins cache (we scan VLC_PLUGIN_PATH directly). */
    private val factoryArgs = arrayOf("--quiet", "--no-plugins-cache", "--no-video-title-show")

    /** Shared factory, initialised once. Throws with an actionable message if natives are missing. */
    fun factory(): MediaPlayerFactory =
        factory ?: synchronized(this) {
            factory ?: createFactory().also { factory = it }
        }

    private fun createFactory(): MediaPlayerFactory {
        val dir = extractNatives()
        val libDir = File(dir, "lib")

        // 1. Preload libvlccore by absolute path so every @rpath/libvlccore.dylib reference resolves.
        System.load(File(libDir, "libvlccore.dylib").absolutePath)

        // 2. Let vlcj/JNA discover libvlc.dylib from the same dir.
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) libDir.absolutePath else "$existing${File.pathSeparator}${libDir.absolutePath}",
        )

        // 3. Point libvlccore at the bundled plugins (no system VLC, no cache scan of system paths).
        val pluginsDir = File(dir, "plugins").absolutePath
        try {
            Native.load("c", LibC::class.java).setenv("VLC_PLUGIN_PATH", pluginsDir, 1)
        } catch (e: Throwable) {
            vlcLogger.e { "setenv(VLC_PLUGIN_PATH) failed: ${e.message}" }
        }

        vlcLogger.d { "libVLC natives ready at $dir" }
        return MediaPlayerFactory(*factoryArgs)
    }

    /** Extract the vendored libs + plugins for this platform to a stable cache dir (idempotent). */
    private fun extractNatives(): File {
        val platform = currentPlatformDir()
        val base = "/vlc/$platform"
        val cache = File(System.getProperty("java.io.tmpdir"), "composemediaplayer-vlc/$platform")
        val libDir = File(cache, "lib").apply { mkdirs() }
        val pluginsDir = File(cache, "plugins").apply { mkdirs() }

        copyResource("$base/lib/libvlccore.dylib", File(libDir, "libvlccore.dylib"))
        copyResource("$base/lib/libvlc.dylib", File(libDir, "libvlc.dylib"))

        // plugins.index lists every plugin filename shipped for this platform.
        val index = readResourceLines("$base/plugins.index")
        if (index.isEmpty()) error("VLC plugins.index missing/empty for $platform — natives not bundled")
        for (name in index) {
            copyResource("$base/plugins/$name", File(pluginsDir, name))
        }
        return cache
    }

    private fun currentPlatformDir(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "darwin-aarch64"
            os.contains("mac") -> throw UnsupportedOperationException(
                "Bundled libVLC not shipped for macOS $arch yet (only arm64 is bundled)."
            )
            else -> throw UnsupportedOperationException(
                "Bundled libVLC not shipped for $os/$arch yet."
            )
        }
    }

    private fun copyResource(resource: String, target: File) {
        if (target.isFile && target.length() > 0) return // already extracted
        val stream = VlcNativeInit::class.java.getResourceAsStream(resource)
            ?: error("Bundled VLC resource missing: $resource")
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        target.setExecutable(true)
    }

    private fun readResourceLines(resource: String): List<String> =
        VlcNativeInit::class.java.getResourceAsStream(resource)?.use { s ->
            s.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } ?: emptyList()
}
