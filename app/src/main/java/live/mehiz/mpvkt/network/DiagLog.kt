package live.mehiz.mpvkt.network

import android.content.Context
import android.os.Environment
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
 * Mirrors to several files so logs are findable without root:
 * - Android/data/<pkg>/files/logs/segmented-debug.log (primary)
 * - Android/data/<pkg>/cache/logs/segmented-debug.log
 * - internal filesDir / cacheDir copies
 * - public Download/mpvkt-segmented-debug.log when writable
 */
object DiagLog {
  private const val TAG = "DiagLog"
  private const val LOG_NAME = "segmented-debug.log"
  private const val PUBLIC_LOG_NAME = "mpvkt-segmented-debug.log"

  @Volatile
  private var sinks: List<File> = emptyList()

  private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
  private val lock = Any()

  /** Absolute paths currently receiving log lines. */
  fun paths(): List<String> = sinks.map { it.absolutePath }

  @JvmStatic
  fun configure(vararg files: File?) {
    sinks = files.filterNotNull().distinctBy { it.absolutePath }
  }

  /**
   * Create dual/triple sinks as soon as the process starts.
   * Safe to call multiple times. Returns primary external path when available.
   */
  @JvmStatic
  fun setupDefault(context: Context): String {
    val app = context.applicationContext
    val candidates = LinkedHashSet<File>()

    // 1) Official external app files dir → /sdcard/Android/data/<pkg>/files/logs/
    runCatching {
      app.getExternalFilesDir(null)?.let { candidates += File(it, "logs/$LOG_NAME") }
    }
    runCatching {
      app.getExternalFilesDir("logs")?.let { candidates += File(it, LOG_NAME) }
    }
    // 2) External cache → /sdcard/Android/data/<pkg>/cache/logs/
    runCatching {
      app.externalCacheDir?.let { candidates += File(it, "logs/$LOG_NAME") }
    }
    // 3) Internal (always works; needs run-as / root to read)
    candidates += File(app.filesDir, "logs/$LOG_NAME")
    candidates += File(app.cacheDir, "segmented-http/$LOG_NAME")
    // 4) Public Downloads — easiest to open in file managers when allowed
    runCatching {
      val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      if (dl != null) candidates += File(dl, PUBLIC_LOG_NAME)
    }

    val usable = ArrayList<File>()
    for (f in candidates) {
      val ok = runCatching {
        f.parentFile?.mkdirs()
        // Touch file so the directory tree exists even before first log line.
        if (!f.exists()) {
          FileOutputStream(f, true).use { it.write(ByteArray(0)) }
        }
        f.canWrite() || f.exists()
      }.getOrDefault(false)
      if (ok) usable += f
    }

    configure(*usable.toTypedArray())

    val primary = usable.firstOrNull()
      ?: File(app.filesDir, "logs/$LOG_NAME").also {
        runCatching { it.parentFile?.mkdirs() }
        configure(it)
      }

    val pkg = app.packageName
    val extRoot = runCatching { app.getExternalFilesDir(null)?.absolutePath }.getOrNull()
    e(
      TAG,
      "setup pkg=$pkg sinks=${paths().joinToString(" | ")} " +
        "externalFilesDir=$extRoot media=${Environment.getExternalStorageState()}",
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
            w.flush()
          }
        }
      }
    }
  }
}
