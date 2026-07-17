@file:Suppress("TooManyFunctions", "ReturnCount")

package live.mehiz.mpvkt.network

import android.content.Context
import android.util.Log
import `is`.xyz.mpv.MPVLib
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Session log for long-playback disconnect debugging.
 *
 * Writes to app-private storage so users can pull via Files / adb:
 * `Android/data/<package>/files/playback-logs/mpvkt-playback-*.log`
 *
 * Also mirrors to logcat under tag [TAG].
 */
object PlaybackSessionLog {
  private const val TAG = "mpvKtPlayback"
  private const val DIR_NAME = "playback-logs"
  private const val MAX_FILES = 12
  private const val MAX_BYTES = 8L * 1024L * 1024L
  private const val HEARTBEAT_INTERVAL_MS = 15_000L

  private val io = Executors.newSingleThreadExecutor { r ->
    Thread(r, "mpvkt-playback-log").apply { isDaemon = true }
  }
  private val active = AtomicBoolean(false)
  private val currentFile = AtomicReference<File?>(null)
  private var writer: BufferedWriter? = null
  private var writtenBytes = 0L
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
  private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

  fun startSession(context: Context, reason: String = "player") {
    io.execute {
      closeLocked("restart")
      val dir = logDir(context).also { it.mkdirs() }
      pruneOldLogs(dir)
      val file = File(dir, "mpvkt-playback-${fileNameFormat.format(Date())}.log")
      runCatching {
        writer = BufferedWriter(
          OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
          8 * 1024,
        )
        currentFile.set(file)
        writtenBytes = 0L
        active.set(true)
        writeLocked("SESSION", "start reason=$reason file=${file.absolutePath}")
        writeLocked(
          "ENV",
          "vpn=${SystemHttpProxy.isVpnActive(context)} " +
            "httpProxy=${SystemHttpProxy.current(context)?.mpvHttpProxyUrl ?: "none"}",
        )
      }.onFailure {
        Log.e(TAG, "failed to open session log: ${it.message}")
        active.set(false)
        currentFile.set(null)
        writer = null
      }
    }
  }

  fun endSession(reason: String = "player-exit") {
    io.execute {
      if (!active.get()) return@execute
      writeLocked("SESSION", "end reason=$reason")
      closeLocked(reason)
    }
  }

  fun currentLogFile(): File? = currentFile.get()

  fun logDir(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, DIR_NAME)
  }

  fun i(tag: String, message: String) = append("I", tag, message)

  fun w(tag: String, message: String) = append("W", tag, message)

  fun e(tag: String, message: String, throwable: Throwable? = null) {
    val extra = throwable?.let { " err=${it.javaClass.simpleName}:${it.message}" }.orEmpty()
    append("E", tag, message + extra)
  }

  fun d(tag: String, message: String) = append("D", tag, message)

  /** Redact common secret query keys before logging URLs. */
  fun redactUrl(url: String): String {
    if (url.isBlank()) return url
    return SECRET_QUERY_REGEX.replace(url) { m ->
      "${m.groupValues[1]}=***"
    }
  }

  fun snapshotPlayback(prefix: String = "STATE") {
    runCatching {
      val path = redactUrl(MPVLib.getPropertyString("path").orEmpty())
      val pos = MPVLib.getPropertyDouble("time-pos")
      val dur = MPVLib.getPropertyDouble("duration")
      val pause = MPVLib.getPropertyBoolean("pause")
      val eof = MPVLib.getPropertyBoolean("eof-reached")
      val pausedForCache = MPVLib.getPropertyBoolean("paused-for-cache")
      val coreIdle = MPVLib.getPropertyBoolean("core-idle")
      val demuxCache = MPVLib.getPropertyDouble("demuxer-cache-duration")
      val cacheSpeed = MPVLib.getPropertyLong("cache-speed")
      val idle = MPVLib.getPropertyBoolean("idle-active")
      i(
        prefix,
        "path=$path pos=$pos dur=$dur pause=$pause eof=$eof " +
          "pausedForCache=$pausedForCache coreIdle=$coreIdle idle=$idle " +
          "demuxCacheSec=$demuxCache cacheSpeed=$cacheSpeed",
      )
    }.onFailure {
      w("STATE", "snapshot failed: ${it.message}")
    }
  }

  fun eventName(eventId: Int): String = when (eventId) {
    MPVLib.mpvEventId.MPV_EVENT_NONE -> "NONE"
    MPVLib.mpvEventId.MPV_EVENT_SHUTDOWN -> "SHUTDOWN"
    MPVLib.mpvEventId.MPV_EVENT_LOG_MESSAGE -> "LOG_MESSAGE"
    MPVLib.mpvEventId.MPV_EVENT_START_FILE -> "START_FILE"
    MPVLib.mpvEventId.MPV_EVENT_END_FILE -> "END_FILE"
    MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED -> "FILE_LOADED"
    MPVLib.mpvEventId.MPV_EVENT_IDLE -> "IDLE"
    MPVLib.mpvEventId.MPV_EVENT_SEEK -> "SEEK"
    MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> "PLAYBACK_RESTART"
    MPVLib.mpvEventId.MPV_EVENT_PROPERTY_CHANGE -> "PROPERTY_CHANGE"
    MPVLib.mpvEventId.MPV_EVENT_QUEUE_OVERFLOW -> "QUEUE_OVERFLOW"
    else -> "EVENT_$eventId"
  }

  private fun append(level: String, tag: String, message: String) {
    val line = message
    when (level) {
      "E" -> Log.e(TAG, "[$tag] $line")
      "W" -> Log.w(TAG, "[$tag] $line")
      "I" -> Log.i(TAG, "[$tag] $line")
      else -> Log.d(TAG, "[$tag] $line")
    }
    if (!active.get()) return
    io.execute { writeLocked(tag, line, level) }
  }

  private fun writeLocked(tag: String, message: String, level: String = "I") {
    val w = writer ?: return
    val ts = dateFormat.format(Date())
    val out = "$ts $level/$tag: $message\n"
    runCatching {
      w.write(out)
      w.flush()
      writtenBytes += out.length
      if (writtenBytes >= MAX_BYTES) {
        writeRawLocked("$ts W/SESSION: log size cap reached ($MAX_BYTES bytes), rotating note only\n")
      }
    }.onFailure {
      Log.e(TAG, "write failed: ${it.message}")
    }
  }

  private fun writeRawLocked(text: String) {
    val w = writer ?: return
    runCatching {
      w.write(text)
      w.flush()
      writtenBytes += text.length
    }
  }

  private fun closeLocked(reason: String) {
    runCatching { writer?.flush() }
    runCatching { writer?.close() }
    writer = null
    active.set(false)
    currentFile.set(null)
    writtenBytes = 0L
    Log.i(TAG, "session log closed ($reason)")
  }

  private fun pruneOldLogs(dir: File) {
    val files = dir.listFiles { f -> f.isFile && f.name.startsWith("mpvkt-playback-") }
      ?.sortedByDescending { it.lastModified() }
      .orEmpty()
    files.drop(MAX_FILES - 1).forEach { runCatching { it.delete() } }
  }

  private val SECRET_QUERY_REGEX = Regex(
    "(?i)(api[_-]?key|access[_-]?token|token|auth|password|X-Emby-Token|MediaBrowserToken)=([^&]*)",
  )

  const val HEARTBEAT_MS = HEARTBEAT_INTERVAL_MS
}
