package live.mehiz.mpvkt.network

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics logger that **survives R8 release minify**.
 *
 * proguard-android-optimize strips Log.v / Log.d / Log.i / Log.w / Log.e
 * call sites as side-effect-free. Log.println is not in that list, so
 * ERROR-priority lines still appear in logcat for release APKs.
 *
 * Also mirrors to one or more files (prefer external app files dir so
 * Android/data/<package>/files/logs is visible without root).
 */
object DiagLog {
  @Volatile
  private var sinks: List<File> = emptyList()

  private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
  private val lock = Any()

  /** Absolute paths currently receiving log lines. */
  fun paths(): List<String> = sinks.map { it.absolutePath }

  /**
   * Configure file sinks. [externalPreferred] is usually
   * `context.getExternalFilesDir(null)/logs/segmented-debug.log`
   * (visible under Android/data/<package>/files/logs/).
   * [internalFallback] is cacheDir for when external storage is unavailable.
   */
  @JvmStatic
  fun configure(vararg files: File?) {
    sinks = files.filterNotNull().distinctBy { it.absolutePath }
  }

  /**
   * Standard dual sink: external files dir (browsable) + internal cache.
   * Creates parent dirs. Returns the primary (external if possible) path.
   */
  @JvmStatic
  fun setupDefault(context: Context): String {
    val external = runCatching {
      context.getExternalFilesDir(null)?.let { root ->
        File(root, "logs/segmented-debug.log")
      }
    }.getOrNull()
    val internal = File(context.cacheDir, "segmented-http/segmented-debug.log")
    configure(external, internal)
    val primary = external ?: internal
    runCatching { primary.parentFile?.mkdirs() }
    runCatching { internal.parentFile?.mkdirs() }
    e(
      "DiagLog",
      "log sinks: ${paths().joinToString(" | ")} " +
        "pkg=${context.packageName}",
    )
    return primary.absolutePath
  }

  @JvmStatic
  fun e(tag: String, msg: String, err: Throwable? = null) {
    // Must use println — Log.e is stripped by R8 optimize.
    if (err != null) {
      Log.println(Log.ERROR, tag, msg + '\n' + Log.getStackTraceString(err))
    } else {
      Log.println(Log.ERROR, tag, msg)
    }
    // Always mirror to stderr (visible in some log viewers / run-as dumps).
    System.err.println("$tag: $msg")
    if (err != null) {
      err.printStackTrace(System.err)
    }
    val targets = sinks
    if (targets.isEmpty()) return
    val ts = timeFmt.format(Date())
    val line = buildString {
      append(ts).append(' ').append(tag).append(": ").append(msg).append('\n')
      if (err != null) {
        append(ts).append(' ').append(Log.getStackTraceString(err)).append('\n')
      }
    }
    synchronized(lock) {
      for (target in targets) {
        runCatching {
          target.parentFile?.mkdirs()
          FileOutputStream(target, true).bufferedWriter().use { w ->
            w.write(line)
          }
        }
      }
    }
  }
}
