package live.mehiz.mpvkt.network

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
 * Also mirrors to a file under the app cache when [file] is set.
 */

object DiagLog {
  @Volatile
  var file: File? = null

  private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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
    val target = file ?: return
    runCatching {
      target.parentFile?.mkdirs()
      val ts = timeFmt.format(Date())
      FileOutputStream(target, true).bufferedWriter().use { w ->
        w.append(ts).append(' ').append(tag).append(": ").append(msg).append('\n')
        if (err != null) {
          w.append(ts).append(' ').append(Log.getStackTraceString(err)).append('\n')
        }
      }
    }
  }
}
