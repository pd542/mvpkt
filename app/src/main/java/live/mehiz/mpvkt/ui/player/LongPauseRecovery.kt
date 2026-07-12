package live.mehiz.mpvkt.ui.player

import android.os.SystemClock
import android.util.Log
import `is`.xyz.mpv.MPVLib
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared long-pause recovery for network streams.
 *
 * After idle pause, HTTP keep-alive / CDN sockets often die. Simply setting
 * pause=false leaves libmpv stuck on a dead stream. We:
 * 1. Always enable conservative FFmpeg reconnect in [MPVView]
 * 2. After [LONG_PAUSE_RECOVER_MS], seek to the current position on unpause
 *    so the demuxer re-opens the network path.
 *
 * Pause timestamps are process-wide so UI pause + notification resume share state.
 */
object LongPauseRecovery {
  private const val TAG = "mpvKt"
  private const val LONG_PAUSE_RECOVER_MS = 30_000L

  private val pausedAtElapsedMs = AtomicLong(0L)

  fun markPaused() {
    // Only stamp the first transition into pause so nested pause calls keep the original idle time.
    pausedAtElapsedMs.compareAndSet(0L, SystemClock.elapsedRealtime())
  }

  fun markPlaying() {
    pausedAtElapsedMs.set(0L)
  }

  /**
   * Call immediately before unpausing. Safe no-op for short pauses / local files.
   */
  fun recoverIfNeeded() {
    val recovery = resolveRecovery() ?: return
    Log.i(TAG, "Long-pause recovery after ${recovery.idleMs}ms pos=${recovery.pos} path=${recovery.path}")
    runCatching {
      MPVLib.command(
        "seek",
        String.format(Locale.US, "%.3f", recovery.pos.coerceAtLeast(0.0)),
        "absolute+exact",
      )
    }.onFailure {
      Log.w(TAG, "Long-pause seek recovery failed: ${it.message}")
    }
  }

  private data class RecoveryTarget(
    val idleMs: Long,
    val pos: Double,
    val path: String,
  )

  private fun resolveRecovery(): RecoveryTarget? {
    val started = pausedAtElapsedMs.get()
    val idleMs = if (started > 0L) SystemClock.elapsedRealtime() - started else 0L
    if (started <= 0L || idleMs < LONG_PAUSE_RECOVER_MS) return null

    val path = MPVLib.getPropertyString("path").orEmpty()
    val pos = MPVLib.getPropertyDouble("time-pos")
    return pos
      ?.takeIf { isNetworkLikePath(path) }
      ?.let { RecoveryTarget(idleMs = idleMs, pos = it, path = path) }
  }

  private fun isNetworkLikePath(path: String): Boolean {
    val lower = path.lowercase(Locale.US)
    return lower.startsWith("http://") ||
      lower.startsWith("https://") ||
      lower.startsWith("rtmp://") ||
      lower.startsWith("rtsp://") ||
      lower.startsWith("hls://") ||
      lower.startsWith("dash://")
  }
}
