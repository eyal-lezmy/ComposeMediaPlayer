package io.github.kdroidfilter.composemediaplayer.vlc

import com.sun.jna.Library
import com.sun.jna.Native
import io.github.kdroidfilter.composemediaplayer.util.buildLocalLogger
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogEventListener
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 *
 * **Changing the bundled libVLC version is not a drop-in.** Desktop subtitles reach the picture
 * through a libVLC 3 behaviour that libVLC 4 removed, and the loss is silent — the player still
 * reports the subtitle track as selected. Read
 * `docs/tasks/pending/153-libvlc-4-migration.md` before bumping, and run
 * `:mediaplayer:jvmTest --tests "*VmemSubtitleProbeTest*"` after: it decides on a frame.
 */
object VlcNativeInit {

    private interface LibC : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    @Volatile
    private var factory: MediaPlayerFactory? = null

    /**
     * Receives libVLC's own log messages as `(level, module, message)` — set it **before** the first
     * playback, since it decides how the shared factory is created.
     *
     * libVLC is the only component that knows why a stream stopped: the player API reports a frozen
     * picture and a silent state, while the log says `http: connection failed` or
     * `vout: picture is too late` in as many words. Nothing here reads it; the host application
     * decides what to do with the lines.
     */
    @Volatile
    var nativeLogListener: ((String, String, String) -> Unit)? = null

    /**
     * libVLC args: skip the on-disk plugins cache (we scan VLC_PLUGIN_PATH directly), no title OSD.
     *
     * Verbosity is decided by [nativeLogListener]: `--quiet` sets libVLC's verbosity to -1, which
     * drops messages *before* they reach any log callback, so a listener has to be paired with
     * `--verbose`. Without one, stay quiet — the messages would only go to stderr.
     *
     * `--verbose=1` (errors and warnings), not `2`: the verbosity argument is libVLC's *only*
     * filter, so debug level would push thousands of messages a minute through a JNA callback while
     * video is decoding — perturbing the frame timing that a stall investigation is measuring. The
     * failures worth catching announce themselves at those two levels (`connection failed`,
     * `picture is too late`).
     */
    private val factoryArgs: Array<String>
        get() = listOfNotNull(
            if (nativeLogListener != null) "--verbose=1" else "--quiet",
            "--no-plugins-cache",
            "--no-video-title-show",
            httpProxy()?.let { "--http-proxy=$it" },
        ).toTypedArray()

    /**
     * Opt-in diagnostic proxy (e.g. `tools/http_probe_proxy.py`), read from `OKAMP_VLC_HTTP_PROXY`.
     *
     * An **instance** argument, not a per-media `:http-proxy=` option: passed per media it was
     * accepted without complaint and then ignored — the proxy saw no connection at all while
     * playback ran normally (measured 2026-08-16). Off unless the environment asks for it, so
     * nothing ships behind a proxy by accident.
     */
    private fun httpProxy(): String? = System.getenv("OKAMP_VLC_HTTP_PROXY")?.takeIf { it.isNotBlank() }

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
        return MediaPlayerFactory(*factoryArgs).also(::attachNativeLog)
    }

    /**
     * The log and the listener bridging it to [nativeLogListener], kept for the life of the process.
     *
     * **Load-bearing references, not tidiness.** JNA allocates a native trampoline for the listener
     * and keeps only a weak reference to it: once nothing on the Java side holds the listener, the
     * GC frees that trampoline while libVLC still holds its address, and the next message crashes
     * inside `vlc_vaLog` — measured 2026-08-16, `EXC_BAD_ACCESS … possible pointer authentication
     * failure`, about 20 s into playback, with the whole stack in `libvlccore` → `jna…tmp`.
     */
    private var nativeLog: NativeLog? = null
    private var nativeLogBridge: LogEventListener? = null

    /**
     * Forwards libVLC's messages to [nativeLogListener], from `WARNING` up — matching the
     * `--verbose=1` the factory was built with, so nothing is generated only to be dropped here.
     *
     * The listener is called on libVLC's own logging thread, in the middle of whatever module is
     * emitting: it must return immediately and must not throw back into native code.
     */
    private fun attachNativeLog(factory: MediaPlayerFactory) {
        val listener = nativeLogListener ?: return
        try {
            val log = factory.application().newLog() ?: return
            val bridge = LogEventListener { level, module, _, _, _, _, _, message ->
                try {
                    listener(level?.name ?: "?", module ?: "?", message?.trim().orEmpty())
                } catch (_: Throwable) {
                    // An exception crossing back into libVLC's logging thread would take the
                    // process down for a diagnostic line. Losing the line is the cheaper failure.
                }
            }
            log.setLevel(LogLevel.WARNING)
            log.addLogListener(bridge)
            nativeLog = log
            nativeLogBridge = bridge
        } catch (e: Throwable) {
            vlcLogger.e { "could not attach the libVLC log: ${e.message}" }
        }
    }

    /** Extract the vendored libs + plugins for this platform to a stable cache dir (idempotent). */
    internal fun extractNatives(
        cache: File = File(System.getProperty("java.io.tmpdir"), "composemediaplayer-vlc/${currentPlatformDir()}"),
    ): File {
        val platform = currentPlatformDir()
        val base = "/vlc/$platform"
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
        prunePlugins(pluginsDir, index.toSet())
        return cache
    }

    /**
     * Deletes anything in the cache the current bundle no longer ships. libVLC loads every file it
     * finds under `VLC_PLUGIN_PATH`, not the ones [readResourceLines] named, so a plugin dropped
     * from the bundle keeps being loaded on every machine that once extracted it — which would
     * quietly undo a plugin trim (a licence decision, not a size one) on exactly the developer
     * machines that have run the library longest.
     */
    private fun prunePlugins(pluginsDir: File, keep: Set<String>) {
        pluginsDir.listFiles().orEmpty()
            .filter { it.name !in keep }
            .forEach {
                vlcLogger.d { "removing stale bundled plugin ${it.name}" }
                it.delete()
            }
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

    /**
     * Extracts [resource] to [target], reusing what is already there **only when it is the size the
     * bundle ships**.
     *
     * "The file exists and isn't empty" is not enough: a copy cut short (the process killed
     * mid-extraction) and a leftover from an older bundle both pass that test and then stay forever,
     * because the cache dir is stable across versions. libVLC reports such a file as nothing at all
     * — a truncated `libts_plugin.dylib` becomes `no demux modules matched`, i.e. every MPEG-TS
     * stream failing with "VLC is unable to open the MRL", with the plugin named only in a
     * `-vv` warning nobody sees.
     *
     * The copy itself goes through a temp file and an atomic rename, so a kill mid-write leaves the
     * previous file (or nothing) rather than a new truncated one.
     */
    private fun copyResource(resource: String, target: File) {
        val expected = resourceSize(resource)
        val cached = target.length()
        if (target.isFile && (if (expected >= 0) cached == expected else cached > 0)) return
        if (target.isFile) {
            vlcLogger.d { "re-extracting ${target.name}: $cached bytes on disk, $expected expected" }
        }
        val stream = VlcNativeInit::class.java.getResourceAsStream(resource)
            ?: error("Bundled VLC resource missing: $resource")
        val partial = File(target.parentFile, "${target.name}.partial")
        stream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        partial.setExecutable(true)
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Size [resource] occupies in the bundle, or `-1` when the packaging doesn't declare one. */
    private fun resourceSize(resource: String): Long {
        val url = VlcNativeInit::class.java.getResource(resource) ?: return -1L
        return runCatching {
            when (val connection = url.openConnection()) {
                is JarURLConnection -> connection.jarEntry.size
                else -> connection.contentLengthLong
            }
        }.getOrDefault(-1L)
    }

    private fun readResourceLines(resource: String): List<String> =
        VlcNativeInit::class.java.getResourceAsStream(resource)?.use { s ->
            s.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } ?: emptyList()
}
