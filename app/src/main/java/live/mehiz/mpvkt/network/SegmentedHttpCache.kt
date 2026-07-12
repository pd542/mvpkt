@file:Suppress(
  "ReturnCount",
  "LongParameterList",
  "CyclomaticComplexMethod",
  "NestedBlockDepth",
  "LoopWithTooManyJumpStatements",
  "LongMethod",
  "TooManyFunctions",
  "ComplexCondition",
)

package live.mehiz.mpvkt.network

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Multi-connection progressive loader (browser / IDM style).
 *
 * Strategy (playback-safe):
 * 1. Probe Range support
 * 2. Download a contiguous **head** synchronously
 * 3. Return a **local file path** for mpv (`/data/.../cache.bin`) — avoids native
 *    cleartext / localhost HTTP issues with libmpv on Android
 * 4. Background workers fill remaining ranges with N parallel connections
 * 5. A small localhost proxy is also started as fallback URL
 *
 * When mpv (or the proxy) needs a hole, [ensureRange] downloads it on demand so
 * sequential read never hits empty sparse regions for long.
 */
class SegmentedHttpCache(
  private val cacheDir: File,
  private val connections: Int,
  private val chunkBytes: Int,
  private val userAgent: String = DEFAULT_UA,
) {
  private val closed = AtomicBoolean(false)
  private var session: Session? = null

  data class OpenResult(
    /**
     * URL/path for mpv loadfile.
     * Uses localhost HTTP proxy (never a sparse file path — holes are zeros and break demux).
     */
    val playPath: String,
    val usedSegmented: Boolean,
  )

  data class ProbeResult(
    val supportsRange: Boolean,
    val contentLength: Long,
    val contentType: String?,
    val finalUrl: String,
  )

  /**
   * Open segmented session. On failure [OpenResult.playPath] is [originalUrl]
   * and [OpenResult.usedSegmented] is false.
   */
  fun open(originalUrl: String): OpenResult {
    if (!isAcceleratableUrl(originalUrl)) {
      return OpenResult(originalUrl, false)
    }
    return runCatching { startSession(originalUrl) }.getOrElse { e ->
      Log.e(TAG, "segmented open failed → direct: ${e.message}", e)
      shutdownQuietly()
      OpenResult(originalUrl, false)
    }
  }

  private fun startSession(originalUrl: String): OpenResult {
    val probe = probe(originalUrl, userAgent)
    if (!probe.supportsRange || probe.contentLength < MIN_FILE_FOR_ACCEL) {
      Log.i(TAG, "skip segmented range=${probe.supportsRange} len=${probe.contentLength}")
      return OpenResult(originalUrl, false)
    }

    val connCount = connections.coerceIn(2, 16)
    val chunk = chunkBytes.coerceIn(MIN_CHUNK, MAX_CHUNK)
    val headBytes = min(probe.contentLength, HEAD_BYTES)

    val sess = Session(
      config = SessionConfig(
        originUrl = probe.finalUrl,
        totalSize = probe.contentLength,
        contentType = sanitizeContentType(probe.contentType),
        connections = connCount,
        chunkBytes = chunk,
        userAgent = userAgent,
      ),
      cacheDir = cacheDir,
    )

    // Must have real contiguous head before play.
    val headOk = sess.downloadRangeBlocking(0L, headBytes - 1)
    val have = sess.store.contiguousFrom(0L)
    if (!headOk || have < min(headBytes, MIN_HEAD_TO_START)) {
      Log.w(TAG, "head incomplete have=$have need=$headBytes → direct")
      sess.close()
      return OpenResult(originalUrl, false)
    }

    sess.startBackground(afterOffset = have)
    session = sess

    // MUST use the HTTP proxy, not the sparse file path.
    // Unwritten sparse regions read as zeros and break demuxers.
    // The proxy only serves bytes that were actually downloaded (ensureRange).
    Log.i(
      TAG,
      "segmented OK head=$have total=${probe.contentLength} " +
        "workers=$connCount → ${sess.localUrl}",
    )
    return OpenResult(sess.localUrl, true)
  }

  fun close() = shutdownQuietly()

  private fun shutdownQuietly() {
    closed.set(true)
    session?.close()
    session = null
  }

  companion object {
    private const val TAG = "SegmentedHttpCache"
    private const val DEFAULT_UA =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val MIN_CHUNK = 256 * 1024
    private const val MAX_CHUNK = 4 * 1024 * 1024

    /** Contiguous head downloaded before playback starts. */
    private const val HEAD_BYTES = 2L * 1024L * 1024L
    private const val MIN_HEAD_TO_START = 256L * 1024L
    private const val MIN_FILE_FOR_ACCEL = 3L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000

    fun isAcceleratableUrl(url: String): Boolean {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
      val adaptive = lower.contains(".m3u8") ||
        lower.contains(".mpd") ||
        lower.contains("format=m3u") ||
        lower.contains("type=m3u8") ||
        lower.contains("/hls/") ||
        lower.contains("playlist")
      return !adaptive
    }

    fun probe(url: String, userAgent: String = DEFAULT_UA): ProbeResult {
      val conn = openConnection(url, userAgent).apply {
        requestMethod = "GET"
        setRequestProperty("Range", "bytes=0-0")
        instanceFollowRedirects = true
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
      }
      return try {
        conn.connect()
        val code = conn.responseCode
        val finalUrl = conn.url.toString()
        val type = conn.contentType
        if (code == HttpURLConnection.HTTP_PARTIAL) {
          val total = parseTotalFromContentRange(conn.getHeaderField("Content-Range")) ?: -1L
          runCatching { conn.inputStream.close() }
          conn.disconnect()
          ProbeResult(total > 0, total, type, finalUrl)
        } else {
          val len = conn.getHeaderFieldLong("Content-Length", -1L)
          conn.disconnect()
          ProbeResult(false, len, type, finalUrl)
        }
      } catch (e: Exception) {
        Log.w(TAG, "probe error: ${e.message}")
        runCatching { conn.disconnect() }
        ProbeResult(false, -1L, null, url)
      }
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
      if (header.isNullOrBlank()) return null
      val slash = header.lastIndexOf('/')
      if (slash < 0 || slash == header.lastIndex) return null
      val total = header.substring(slash + 1).trim()
      return total.takeUnless { it == "*" }?.toLongOrNull()
    }

    private fun sanitizeContentType(raw: String?): String {
      if (raw.isNullOrBlank()) return "video/mp4"
      // "video/mp4; charset=..." → video/mp4
      return raw.substringBefore(';').trim().ifBlank { "video/mp4" }
    }

    fun openConnection(url: String, userAgent: String): HttpURLConnection {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.setRequestProperty("User-Agent", userAgent)
      conn.setRequestProperty("Accept", "*/*")
      conn.setRequestProperty("Connection", "keep-alive")
      return conn
    }
  }

  private data class SessionConfig(
    val originUrl: String,
    val totalSize: Long,
    val contentType: String,
    val connections: Int,
    val chunkBytes: Int,
    val userAgent: String,
  )

  private class Session(
    private val config: SessionConfig,
    cacheDir: File,
  ) {
    val store = ContiguousStore(config.totalSize)
    val cacheFile: File =
      File(cacheDir, "seg_${config.originUrl.hashCode().toUInt()}_${config.totalSize}.bin")
    private val raf: RandomAccessFile
    private val executor: ThreadPoolExecutor
    private val serverExecutor = Executors.newCachedThreadPool()
    private val futures = mutableListOf<Future<*>>()
    private val running = AtomicBoolean(true)
    private val downloaded = AtomicLong(0)
    private var serverSocket: ServerSocket? = null
    val localUrl: String

    init {
      cacheDir.mkdirs()
      // Fresh file each session
      if (cacheFile.exists()) {
        runCatching { cacheFile.delete() }
      }
      raf = RandomAccessFile(cacheFile, "rw")
      raf.setLength(config.totalSize)
      executor = ThreadPoolExecutor(
        config.connections,
        config.connections,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
      )
      val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
      serverSocket = ss
      localUrl = "http://127.0.0.1:${ss.localPort}/media"
    }

    fun downloadRangeBlocking(start: Long, endInclusive: Long): Boolean {
      return tryDownloadOnce(start, endInclusive, retries = 3)
    }

    /**
     * Ensure [start, endExclusive) is in the cache, downloading missing holes
     * from origin (possibly in parallel pieces).
     */
    fun ensureRange(start: Long, endExclusive: Long, timeoutMs: Long): Boolean {
      val end = min(endExclusive, config.totalSize)
      if (start >= end) return true
      val deadline = System.currentTimeMillis() + timeoutMs
      while (running.get() && System.currentTimeMillis() < deadline) {
        val have = store.contiguousFrom(start)
        if (start + have >= end) return true
        val holeStart = start + have
        val holeEnd = min(end, holeStart + config.chunkBytes) - 1
        // Download this hole now (blocking on caller thread or worker).
        downloadRangeBlocking(holeStart, holeEnd)
      }
      return store.contiguousFrom(start) + start >= end
    }

    fun startBackground(afterOffset: Long) {
      // Optional proxy for debugging / alternate play path.
      serverExecutor.execute { acceptLoop() }

      if (afterOffset >= config.totalSize) return
      val chunks = buildChunks(afterOffset, config.totalSize, config.chunkBytes)
      chunks.forEach { range ->
        futures += executor.submit {
          downloadRangeBlocking(range.first, range.last)
        }
      }
      // Prefetch watchdog: always try to keep ~8MB contiguous from the current tip.
      serverExecutor.execute { prefetchWatchdog() }
      Log.i(TAG, "background ${chunks.size} chunks from $afterOffset")
    }

    private fun prefetchWatchdog() {
      while (running.get()) {
        try {
          val tip = store.contiguousFrom(0L)
          if (tip >= config.totalSize) break
          // Keep extending the contiguous prefix aggressively.
          val target = min(config.totalSize, tip + config.chunkBytes * 2L)
          if (tip < target) {
            ensureRange(tip, target, 30_000L)
          } else {
            Thread.sleep(200)
          }
        } catch (_: Exception) {
          runCatching { Thread.sleep(300) }
        }
      }
    }

    private fun buildChunks(from: Long, total: Long, chunk: Int): List<LongRange> {
      val list = ArrayList<LongRange>()
      var pos = from
      while (pos < total) {
        val end = min(total, pos + chunk) - 1
        list.add(pos..end)
        pos = end + 1
      }
      return list
    }

    private fun tryDownloadOnce(start: Long, endInclusive: Long, retries: Int): Boolean {
      if (!running.get()) return false
      if (store.isFullyCovered(start, endInclusive + 1)) return true
      repeat(retries) { attempt ->
        if (!running.get()) return false
        var conn: HttpURLConnection? = null
        try {
          conn = openConnection(config.originUrl, config.userAgent).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=$start-$endInclusive")
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
          }
          conn.connect()
          val code = conn.responseCode
          if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
            throw IOException("HTTP $code for $start-$endInclusive")
          }
          if (code == HttpURLConnection.HTTP_OK && start != 0L) {
            throw IOException("Range ignored at $start")
          }
          writeStreamToFile(conn.inputStream, start, endInclusive)
          return store.isFullyCovered(start, endInclusive + 1) ||
            store.contiguousFrom(start) > 0
        } catch (e: Exception) {
          Log.w(TAG, "range $start-$endInclusive try ${attempt + 1}: ${e.message}")
          runCatching { Thread.sleep(250L * (attempt + 1)) }
        } finally {
          runCatching { conn?.disconnect() }
        }
      }
      return store.isFullyCovered(start, endInclusive + 1)
    }

    private fun writeStreamToFile(stream: InputStream, start: Long, endInclusive: Long) {
      val input = BufferedInputStream(stream, 64 * 1024)
      val buf = ByteArray(64 * 1024)
      var writePos = start
      while (running.get() && writePos <= endInclusive) {
        val n = input.read(buf)
        if (n < 0) break
        val maxWrite = (endInclusive + 1 - writePos).toInt()
        if (maxWrite <= 0) break
        val w = min(n, maxWrite)
        synchronized(raf) {
          raf.seek(writePos)
          raf.write(buf, 0, w)
        }
        val from = writePos
        writePos += w
        downloaded.addAndGet(w.toLong())
        store.mark(from, writePos)
      }
      // Force metadata for file readers.
      synchronized(raf) {
        runCatching { raf.fd.sync() }
      }
      runCatching { input.close() }
    }

    private fun acceptLoop() {
      val ss = serverSocket ?: return
      while (running.get()) {
        try {
          val socket = ss.accept()
          serverExecutor.execute { handleClient(socket) }
        } catch (_: Exception) {
          if (!running.get()) break
        }
      }
    }

    private fun handleClient(socket: Socket) {
      try {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readHttpRequest(input) ?: run {
          socket.close()
          return
        }
        if (request.method != "GET" && request.method != "HEAD") {
          writeResponse(output, 405, "Method Not Allowed", "text/plain", 0, emptyMap(), null)
          output.flush()
          return
        }
        val range = parseRangeHeader(request.headers["range"], config.totalSize)
        val isHead = request.method == "HEAD"
        if (range != null) {
          val (from, to) = range
          val length = to - from + 1
          // Only ensure a small prefix before headers — BodyReader fills the rest.
          ensureRange(from, min(to + 1, from + 256 * 1024L), 30_000L)
          val extra = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $from-$to/${config.totalSize}",
          )
          writeResponse(
            output,
            206,
            "Partial Content",
            config.contentType,
            length,
            extra,
            if (isHead) null else BodyReader(from, length),
          )
        } else {
          // Full GET — stream entire file, filling on demand (head already present).
          ensureRange(0L, min(config.totalSize, 256 * 1024L), 15_000L)
          writeResponse(
            output,
            200,
            "OK",
            config.contentType,
            config.totalSize,
            mapOf("Accept-Ranges" to "bytes"),
            if (isHead) null else BodyReader(0L, config.totalSize),
          )
        }
        output.flush()
      } catch (e: Exception) {
        Log.d(TAG, "proxy client: ${e.message}")
      } finally {
        runCatching { socket.close() }
      }
    }

    private inner class BodyReader(private val start: Long, private val length: Long) {
      fun writeTo(out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        var pos = start
        while (remaining > 0 && running.get()) {
          val want = min(buf.size.toLong(), remaining).toInt()
          // Block until this slice exists (download on demand).
          val ok = ensureRange(pos, pos + want, 60_000L)
          val avail = store.contiguousFrom(pos).toInt()
          if (!ok && avail <= 0) {
            Log.w(TAG, "BodyReader stall at $pos")
            break
          }
          val toRead = min(want, avail.coerceAtLeast(0))
          if (toRead <= 0) {
            // Tiny wait then retry rather than aborting whole stream.
            runCatching { Thread.sleep(50) }
            continue
          }
          synchronized(raf) {
            raf.seek(pos)
            raf.readFully(buf, 0, toRead)
          }
          out.write(buf, 0, toRead)
          out.flush()
          pos += toRead
          remaining -= toRead
        }
      }
    }

    fun close() {
      running.set(false)
      futures.forEach { it.cancel(true) }
      executor.shutdownNow()
      serverExecutor.shutdownNow()
      runCatching { serverSocket?.close() }
      runCatching { raf.close() }
      Log.i(TAG, "closed downloaded=${downloaded.get()}/${config.totalSize}")
    }

    private data class HttpRequest(
      val method: String,
      val path: String,
      val headers: Map<String, String>,
    )

    private fun readHttpRequest(input: InputStream): HttpRequest? {
      val first = readLine(input) ?: return null
      val parts = first.split(' ')
      if (parts.size < 2) return null
      val headers = HashMap<String, String>()
      while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
          headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
            line.substring(idx + 1).trim()
        }
      }
      return HttpRequest(parts[0].uppercase(Locale.US), parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
      val sb = StringBuilder()
      while (true) {
        val c = input.read()
        if (c < 0) return sb.toString().ifEmpty { null }
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
      }
      return sb.toString()
    }

    private fun writeResponse(
      out: OutputStream,
      code: Int,
      reason: String,
      type: String,
      contentLength: Long,
      extra: Map<String, String>,
      body: BodyReader?,
    ) {
      val sb = StringBuilder()
      sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
      sb.append("Content-Type: ").append(type).append("\r\n")
      sb.append("Content-Length: ").append(contentLength).append("\r\n")
      sb.append("Connection: close\r\n")
      extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
      sb.append("\r\n")
      out.write(sb.toString().toByteArray(Charsets.US_ASCII))
      body?.writeTo(out)
    }

    private fun parseRangeHeader(header: String?, total: Long): Pair<Long, Long>? {
      if (header.isNullOrBlank() || !header.lowercase(Locale.US).startsWith("bytes=")) {
        return null
      }
      val spec = header.substring(6).trim()
      val dash = spec.indexOf('-')
      if (dash < 0) return null
      val start = spec.substring(0, dash).toLongOrNull() ?: return null
      val endStr = spec.substring(dash + 1)
      val end = if (endStr.isEmpty()) total - 1 else endStr.toLongOrNull() ?: return null
      return if (start in 0 until total && end in start until total) start to end else null
    }
  }

  class ContiguousStore(private val totalSize: Long) {
    private val lock = Object()
    private val map = TreeMap<Long, Long>()

    fun mark(start: Long, end: Long) {
      if (end <= start) return
      synchronized(lock) {
        var s = start
        var e = end
        val prev = map.floorEntry(s)
        if (prev != null && prev.value >= s) {
          s = prev.key
          e = maxOf(e, prev.value)
          map.remove(prev.key)
        }
        while (true) {
          val next = map.ceilingEntry(s) ?: break
          if (next.key > e) break
          e = maxOf(e, next.value)
          map.remove(next.key)
        }
        map[s] = e
        lock.notifyAll()
      }
    }

    fun contiguousFrom(pos: Long): Long = synchronized(lock) {
      val entry = map.floorEntry(pos)
      if (entry == null || entry.value <= pos) 0L else entry.value - pos
    }

    fun isFullyCovered(start: Long, endExclusive: Long): Boolean = synchronized(lock) {
      val entry = map.floorEntry(start)
      entry != null && entry.value >= endExclusive
    }

    fun waitContiguous(pos: Long, length: Long, timeoutMs: Long): Boolean {
      val needEnd = min(totalSize, pos + length)
      val deadline = System.currentTimeMillis() + timeoutMs
      synchronized(lock) {
        while (true) {
          val avail = contiguousFrom(pos)
          if (pos + avail >= needEnd) return true
          val remaining = deadline - System.currentTimeMillis()
          if (remaining <= 0) return pos + avail > pos
          lock.wait(min(remaining, 100L))
        }
      }
    }
  }
}
