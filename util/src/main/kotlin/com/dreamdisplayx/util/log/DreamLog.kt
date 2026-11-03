package com.dreamdisplayx.util.log

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The fork's single logging entry point.
 *
 * Everything the mod logs goes through [DreamLog]: it forwards to slf4j (so the line lands in the
 * game's own `latest.log` with the same timestamp/thread/level shape as vanilla, and therefore in
 * the launcher's log viewer), and it can additionally mirror selected channels into
 * `<gameDir>/logs/dreamdisplayx-<channel>.log`, next to `latest.log`, rotated the way Minecraft
 * rotates its own logs.
 *
 * ## Why not `LoggerFactory.getLogger(...)` directly
 *
 * There were ~385 direct call sites across the modules, each inventing its own channel name, and no
 * way to (a) find them all, (b) route one subsystem's noisy diagnostics into a separate file, or
 * (c) install a file sink without the game running (unit tests, headless probes). Call sites now use
 * `DreamLog.of("Channel")` and get all three for free.
 *
 * ## File mirroring
 *
 * Mirroring is off by default: only channels registered through [mirrorToFile] are written to disk,
 * because the mod is chatty and a second copy of every line is pure noise. [configure] installs the
 * base directory (normally `<gameDir>/logs`), which the client initializer resolves from Minecraft's
 * own game directory so the file really does sit beside `latest.log`.
 *
 * The whole thing is failure-tolerant by construction: a broken log directory must never stop the
 * game, so every file operation is wrapped and downgraded to a one-shot warning.
 */
object DreamLog {

    /** Prefix shared by every channel, kept stable so `latest.log` greps keep working. */
    const val PREFIX: String = "DreamDisplaysX"

    /** Directory the mirrored logs live in; `null` until [configure] runs. */
    @Volatile
    private var logDirectory: File? = null

    /** Channels that also write `<logDirectory>/dreamdisplayx-<channel>.log`. */
    private val mirrored = CopyOnWriteArrayList<String>()

    @Volatile
    private var sink: FileSink? = null

    private val configureFailureReported = AtomicBoolean(false)

    /**
     * Installs the directory mirrored logs are written to.
     *
     * Called once from the client initializer with `<gameDir>/logs`, i.e. the same directory that
     * holds `latest.log`. Repeated calls with the same path are a no-op; a different path rebuilds
     * the sink, which is what a unit test needs to point at a temp directory.
     */
    fun configure(directory: File?) {
        synchronized(this) {
            if (directory == null) return
            if (logDirectory?.absolutePath == directory.absolutePath && sink != null) return
            sink?.close()
            sink = null
            logDirectory = directory
            if (mirrored.isNotEmpty()) openSink(directory)
        }
    }

    /** The directory mirrored logs are written to, or `null` when no file sink is installed. */
    fun logDirectory(): File? = logDirectory

    /**
     * Starts mirroring [channel] into `<logDirectory>/dreamdisplayx-<channel>.log`.
     *
     * Idempotent. A no-op for the file part when [configure] has not run, so unit tests and the
     * dedicated-server paths that never resolve a game directory keep working unchanged.
     */
    fun mirrorToFile(channel: String) {
        if (mirrored.contains(channel)) return
        mirrored += channel
        logDirectory?.let { dir -> synchronized(this) { openSink(dir) } }
    }

    /** Channels currently mirrored to disk; exposed for diagnostics and tests. */
    fun mirroredChannels(): List<String> = mirrored.toList()

    /** A logger whose channel becomes `DreamDisplaysX/<name>` and whose lines reach `latest.log`. */
    fun of(name: String): Channel = Channel(name)

    /** Backwards-compatible short form: `DreamLog.logger("LibVlc")`. */
    fun logger(name: String): Channel = of(name)

    /** Flushes and closes the file sink. Safe to call at any time; logging keeps working after. */
    fun shutdown() {
        synchronized(this) {
            sink?.close()
            sink = null
        }
    }

    /**
     * Drops every piece of state this object holds: the sink, the directory and the mirrored set.
     *
     * [DreamLog] is a process-wide singleton, so a test that configures a temporary directory would
     * otherwise leak it into the next test — and into the real client if a test ever ran in the same
     * JVM as a launch.
     */
    internal fun resetForTest() {
        synchronized(this) {
            sink?.close()
            sink = null
            logDirectory = null
            mirrored.clear()
            configureFailureReported.set(false)
        }
    }

    private fun openSink(directory: File) {
        val created = runCatching {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw java.io.IOException("unable to create ${directory.absolutePath}")
            }
            FileSink(directory, mirrored.toList())
        }.getOrElse { error ->
            if (configureFailureReported.compareAndSet(false, true)) {
                LoggerFactory.getLogger("$PREFIX/DreamLog")
                    .warn("File log mirroring disabled ({}): {}", directory.absolutePath, error.message)
            }
            null
        }
        sink = created
    }

    /**
     * One channel's logger: an slf4j facade for forwarding plus the optional file mirror.
     *
     * Deliberately implements only the slf4j methods the mod actually calls (`debug`/`info`/`warn`/
     * `error`, with and without throwables and varargs) instead of implementing the full `Logger`
     * interface, whose ~50 members would be dead weight on every one of the ~385 call sites.
     */
    class Channel internal constructor(private val name: String) {
        /** Channel name without the shared prefix, e.g. `LibVlc`. */
        val channel: String get() = name

        /** The slf4j logger lines are forwarded to. */
        val delegate: Logger = LoggerFactory.getLogger("$PREFIX/$name")

        val isDebugEnabled: Boolean get() = delegate.isDebugEnabled
        val isInfoEnabled: Boolean get() = delegate.isInfoEnabled

        fun debug(message: String) {
            if (delegate.isDebugEnabled) delegate.debug(message)
            mirror("D", message, null)
        }

        fun debug(format: String, arg: Any?) {
            if (delegate.isDebugEnabled) delegate.debug(format, arg)
            mirror("D", substitute(format, arg), null)
        }

        fun debug(format: String, vararg args: Any?) {
            if (delegate.isDebugEnabled) delegate.debug(format, *args)
            mirror("D", substitute(format, *args), null)
        }

        fun info(message: String) {
            delegate.info(message)
            mirror("I", message, null)
        }

        fun info(format: String, arg: Any?) {
            delegate.info(format, arg)
            mirror("I", substitute(format, arg), null)
        }

        fun info(format: String, vararg args: Any?) {
            delegate.info(format, *args)
            mirror("I", substitute(format, *args), null)
        }

        fun warn(message: String) {
            delegate.warn(message)
            mirror("W", message, null)
        }

        fun warn(format: String, arg: Any?) {
            delegate.warn(format, arg)
            mirror("W", substitute(format, arg), null)
        }

        fun warn(format: String, vararg args: Any?) {
            delegate.warn(format, *args)
            mirror("W", substitute(format, *args), null)
        }

        fun warn(message: String, error: Throwable?) {
            delegate.warn(message, error)
            mirror("W", message, error)
        }

        fun error(message: String) {
            delegate.error(message)
            mirror("E", message, null)
        }

        fun error(format: String, arg: Any?) {
            delegate.error(format, arg)
            mirror("E", substitute(format, arg), null)
        }

        fun error(format: String, vararg args: Any?) {
            delegate.error(format, *args)
            mirror("E", substitute(format, *args), null)
        }

        fun error(message: String, error: Throwable?) {
            delegate.error(message, error)
            mirror("E", message, error)
        }

        private fun mirror(level: String, message: String, error: Throwable?) {
            val active = sink ?: return
            active.write(name, level, message, error)
        }

        /**
         * `{}` substitution, mirroring slf4j's contract closely enough for the file copy.
         *
         * The slf4j line is produced by slf4j itself; this only fills the plain-text mirror, so the
         * exact escaping rules matter less than never throwing from a logging call.
         */
        private fun substitute(format: String, vararg args: Any?): String {
            if (args.isEmpty() || !format.contains("{}")) return format
            val out = StringBuilder(format.length + 32)
            var argIndex = 0
            var cursor = 0
            while (true) {
                val token = format.indexOf("{}", cursor)
                if (token < 0) break
                out.append(format, cursor, token)
                if (argIndex < args.size) {
                    out.append(args[argIndex]?.toString() ?: "null")
                    argIndex++
                } else {
                    out.append("{}")
                }
                cursor = token + 2
            }
            out.append(format, cursor, format.length)
            return out.toString()
        }
    }

    /**
     * Appends mirror lines to one file per mirrored channel, rotating at startup.
     *
     * Mirrors vanilla's layout: the live file is `dreamdisplayx-<channel>.log`, and the previous run
     * becomes a `.gz` archive beside it, pruned to [LogRotation.DEFAULT_MAX_ARCHIVES].
     */
    private class FileSink(directory: File, channels: List<String>) {
        private val stampFormat: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        private val writers = HashMap<String, PrintWriter>()
        private val writeFailureReported = AtomicBoolean(false)

        init {
            for (channel in channels) {
                val file = fileFor(directory, channel)
                runCatching {
                    LogRotation.rotate(file)
                    PrintWriter(
                        OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
                        true,
                    )
                }.onSuccess { writers[channel] = it }
                    .onFailure { reportOnce(directory, it) }
            }
        }

        fun write(channel: String, level: String, message: String, error: Throwable?) {
            val writer = writers[channel] ?: return
            runCatching {
                synchronized(writer) {
                    writer.println("[${stampFormat.format(Instant.now())}] [$level] $message")
                    if (error != null) error.printStackTrace(writer)
                }
            }.onFailure { reportOnce(null, it) }
        }

        fun close() {
            for (writer in writers.values) runCatching { writer.close() }
            writers.clear()
        }

        private fun reportOnce(directory: File?, error: Throwable) {
            if (!writeFailureReported.compareAndSet(false, true)) return
            LoggerFactory.getLogger("$PREFIX/DreamLog")
                .warn("Mirrored log unavailable ({}): {}", directory?.absolutePath ?: "?", error.message)
        }

        companion object {
            /** Mirrored file name for [channel]; also the prefix [LogRotation] prunes by. */
            fun fileFor(directory: File, channel: String): File =
                File(directory, "dreamdisplayx-${channel.lowercase().replace(' ', '-')}.log")
        }
    }
}