@file:Suppress(
  "ReturnCount",
  "LongParameterList",
  "CyclomaticComplexMethod",
  "NestedBlockDepth",
  "LoopWithTooManyJumpStatements",
  "LongMethod",
  "TooManyFunctions",
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Multi-connection HTTP(S) progressive download accelerator (IDM / browser-style).
 *
 * 1. Probes whether the origin supports `Range`
 * 2. Downloads into a sparse cache file with N parallel connections
 * 3. Serves `http://127.0.0.1:<port>/` so mpv can play while segments fill in
 *
 * Does **not** accelerate HLS/DASH (m3u8) — those need segment-level logic.
 * Falls back to the original URL when Range is unsupported.
 */
class SegmentedHttpCache(
  private val cacheDir: File,
  private val connections: Int,
  private val chunkBytes: Int,
  private val userAgent: String = DEFAULT_UA,
) {
  private val closed = AtomicBoolean(false)
  private var session: Session? = null

  data class ProbeResult(
    val supportsRange: Boolean,
    val contentLength: Long,
    val contentType: String?,
    val finalUrl: String,
  )

  /**
   * @return local proxy URL if acceleration started, otherwise [originalUrl]
   */
  fun open(originalUrl: String): String {
    if (!isAcceleratableUrl(originalUrl)) {
      return originalUrl
    }
    return runCatching { startSession(originalUrl) }.getOrElse { error ->
      Log.e(TAG, "Failed to start segmented cache: ${error.message}", error)
      close()
      originalUrl
    }
  }

  private fun startSession(originalUrl: String): String {
    val probe = probe(originalUrl)
    val tooSmall = probe.contentLength in 1 until chunkBytes.toLong()
    if (!probe.supportsRange || probe.contentLength <= 0L || tooSmall) {
      Log.i(TAG, "Range unsupported / unknown / small → pass-through")
      return originalUrl
    }
    val sess = Session(
      config = SessionConfig(
        originUrl = probe.finalUrl,
        totalSize = probe.contentLength,
        contentType = probe.contentType ?: "application/octet-stream",
        connections = connections.coerceIn(2, 16),
        chunkBytes = chunkBytes.coerceIn(MIN_CHUNK, MAX_CHUNK),
        userAgent = userAgent,
      ),
      cacheDir = cacheDir,
    )
    sess.start()
    session = sess
    // Wait briefly for the first chunk so mpv does not hit an empty stream.
    sess.store.waitContiguous(0L, min(probe.contentLength, FIRST_WAIT_BYTES), FIRST_WAIT_MS)
    return sess.localUrl
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    session?.close()
    session = null
  }

  companion object {
    private const val TAG = "SegmentedHttpCache"
    private const val DEFAULT_UA =
      "mpvKt-segmented/1.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0"
    private const val MIN_CHUNK = 256 * 1024
    private const val MAX_CHUNK = 8 * 1024 * 1024
    private const val FIRST_WAIT_BYTES = 256 * 1024L
    private const val FIRST_WAIT_MS = 15_000L

    fun isAcceleratableUrl(url: String): Boolean {
      val lower = url.lowercase(Locale.US)
      val isHttp = lower.startsWith("http://") || lower.startsWith("https://")
      val isAdaptive = lower.contains(".m3u8") ||
        lower.contains(".mpd") ||
        lower.contains("format=m3u")
      return isHttp && !isAdaptive
    }

    fun probe(url: String, userAgent: String = DEFAULT_UA): ProbeResult {
      val headResult = runCatching { probeWithHead(url, userAgent) }.getOrNull()
      if (headResult != null) {
        return headResult
      }
      return confirmRange(url, -1L, null, userAgent)
    }

    private fun probeWithHead(url: String, userAgent: String): ProbeResult? {
      val head = openConnection(url, userAgent).apply {
        requestMethod = "HEAD"
        instanceFollowRedirects = true
        connectTimeout = 12_000
        readTimeout = 12_000
      }
      return try {
        head.connect()
        val code = head.responseCode
        val current = head.url.toString()
        val len = head.getHeaderFieldLong("Content-Length", -1L)
        val accept = head.getHeaderField("Accept-Ranges")?.lowercase(Locale.US)
        val type = head.contentType
        head.disconnect()
        if (code !in 200..299 || len <= 0) {
          null
        } else if (accept?.contains("none") == true) {
          ProbeResult(false, len, type, current)
        } else {
          confirmRange(current, len, type, userAgent)
        }
      } catch (_: Exception) {
        runCatching { head.disconnect() }
        null
      }
    }

    private fun confirmRange(
      url: String,
      knownLen: Long,
      knownType: String?,
      userAgent: String,
    ): ProbeResult {
      val conn = openConnection(url, userAgent).apply {
        requestMethod = "GET"
        setRequestProperty("Range", "bytes=0-0")
        instanceFollowRedirects = true
        connectTimeout = 12_000
        readTimeout = 12_000
      }
      return try {
        conn.connect()
        val code = conn.responseCode
        val finalUrl = conn.url.toString()
        val type = conn.contentType ?: knownType
        if (code == HttpURLConnection.HTTP_PARTIAL) {
          val cr = conn.getHeaderField("Content-Range")
          val total = parseTotalFromContentRange(cr) ?: knownLen
          conn.inputStream?.close()
          conn.disconnect()
          ProbeResult(total > 0, total, type, finalUrl)
        } else {
          val len = conn.getHeaderFieldLong("Content-Length", knownLen)
          conn.disconnect()
          ProbeResult(false, len, type, finalUrl)
        }
      } catch (_: Exception) {
        runCatching { conn.disconnect() }
        ProbeResult(false, knownLen, knownType, url)
      }
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
      if (header.isNullOrBlank()) {
        return null
      }
      val slash = header.lastIndexOf('/')
      if (slash < 0 || slash == header.lastIndex) {
        return null
      }
      val total = header.substring(slash + 1).trim()
      return total.takeUnless { it == "*" }?.toLongOrNull()
    }

    private fun openConnection(url: String, userAgent: String): HttpURLConnection {
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
    private val file: File =
      File(cacheDir, "seg_${config.originUrl.hashCode().toUInt()}_${config.totalSize}.bin")
    private val raf: RandomAccessFile
    private val executor = Executors.newFixedThreadPool(config.connections)
    private val serverExecutor = Executors.newCachedThreadPool()
    private val futures = mutableListOf<Future<*>>()
    private val running = AtomicBoolean(true)
    private val downloaded = AtomicLong(0)
    private var serverSocket: ServerSocket? = null
    val localUrl: String

    init {
      cacheDir.mkdirs()
      raf = RandomAccessFile(file, "rw")
      raf.setLength(config.totalSize)
      val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
      serverSocket = ss
      localUrl = "http://127.0.0.1:${ss.localPort}/media"
    }

    fun start() {
      serverExecutor.execute { acceptLoop() }
      val chunks = buildChunks(config.totalSize, config.chunkBytes)
      chunks.forEach { range ->
        futures += executor.submit { downloadRange(range.first, range.last) }
      }
      Log.i(
        TAG,
        "Started ${config.connections} workers, ${chunks.size} chunks, " +
          "size=${config.totalSize} url=${config.originUrl}",
      )
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

    private fun buildChunks(totalSize: Long, chunkBytes: Int): List<LongRange> {
      val chunks = ArrayList<LongRange>()
      var pos = 0L
      while (pos < totalSize) {
        val end = min(totalSize, pos + chunkBytes) - 1
        chunks.add(pos..end)
        pos = end + 1
      }
      return chunks
    }

    private fun downloadRange(start: Long, endInclusive: Long) {
      if (!running.get() || store.isFullyCovered(start, endInclusive + 1)) {
        return
      }
      repeat(3) { attempt ->
        if (!running.get()) return
        if (tryDownloadOnce(start, endInclusive)) return
        Log.w(TAG, "chunk $start-$endInclusive attempt ${attempt + 1} failed")
        runCatching { Thread.sleep(300L * (attempt + 1)) }
      }
    }

    private fun tryDownloadOnce(start: Long, endInclusive: Long): Boolean {
      var conn: HttpURLConnection? = null
      return try {
        conn = openConnection(config.originUrl, config.userAgent).apply {
          requestMethod = "GET"
          setRequestProperty("Range", "bytes=$start-$endInclusive")
          instanceFollowRedirects = true
          connectTimeout = 15_000
          readTimeout = 30_000
        }
        conn.connect()
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
          throw IOException("HTTP $code for range $start-$endInclusive")
        }
        writeStreamToFile(conn.inputStream, start, endInclusive)
        true
      } catch (_: Exception) {
        false
      } finally {
        runCatching { conn?.disconnect() }
      }
    }

    private fun writeStreamToFile(stream: InputStream, start: Long, endInclusive: Long) {
      val input = BufferedInputStream(stream)
      val buf = ByteArray(64 * 1024)
      var writePos = start
      while (running.get()) {
        val n = input.read(buf)
        if (n < 0) break
        synchronized(raf) {
          raf.seek(writePos)
          raf.write(buf, 0, n)
        }
        val from = writePos
        writePos += n
        downloaded.addAndGet(n.toLong())
        store.mark(from, writePos)
        if (writePos > endInclusive) break
      }
      input.close()
    }

    private fun handleClient(socket: Socket) {
      try {
        socket.soTimeout = 0
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readHttpRequest(input)
        if (request == null) {
          socket.close()
          return
        }
        serveRequest(request, output)
        output.flush()
      } catch (e: Exception) {
        Log.d(TAG, "client error: ${e.message}")
      } finally {
        runCatching { socket.close() }
      }
    }

    private fun serveRequest(request: HttpRequest, output: OutputStream) {
      if (request.method != "GET" && request.method != "HEAD") {
        writeResponse(output, ResponseSpec(405, "Method Not Allowed", config.contentType, 0), null)
        return
      }
      val range = parseRangeHeader(request.headers["range"], config.totalSize)
      val isHead = request.method == "HEAD"
      if (range != null) {
        servePartial(range.first, range.second, isHead, output)
      } else {
        serveFull(isHead, output)
      }
    }

    private fun servePartial(from: Long, to: Long, isHead: Boolean, output: OutputStream) {
      val length = to - from + 1
      store.waitContiguous(from, length, 60_000L)
      val headers = mapOf(
        "Accept-Ranges" to "bytes",
        "Content-Range" to "bytes $from-$to/${config.totalSize}",
      )
      val body = if (isHead) null else BodyReader(from, length)
      writeResponse(
        output,
        ResponseSpec(206, "Partial Content", config.contentType, length, headers),
        body,
      )
    }

    private fun serveFull(isHead: Boolean, output: OutputStream) {
      val headers = mapOf("Accept-Ranges" to "bytes")
      val body = if (isHead) null else BodyReader(0L, config.totalSize)
      writeResponse(
        output,
        ResponseSpec(200, "OK", config.contentType, config.totalSize, headers),
        body,
      )
    }

    private inner class BodyReader(private val start: Long, private val length: Long) {
      fun writeTo(out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        var pos = start
        while (remaining > 0 && running.get()) {
          val want = min(buf.size.toLong(), remaining).toInt()
          val ready = store.waitContiguous(pos, want.toLong(), 30_000L)
          val toRead = if (ready) {
            want
          } else {
            store.contiguousFrom(pos).toInt().coerceAtLeast(0).coerceAtMost(want)
          }
          if (toRead <= 0) break
          synchronized(raf) {
            raf.seek(pos)
            raf.readFully(buf, 0, toRead)
          }
          out.write(buf, 0, toRead)
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
      Log.i(TAG, "Session closed, downloaded=${downloaded.get()}/${config.totalSize}")
    }

    private data class HttpRequest(
      val method: String,
      val path: String,
      val headers: Map<String, String>,
    )

    private data class ResponseSpec(
      val code: Int,
      val reason: String,
      val type: String,
      val contentLength: Long,
      val extra: Map<String, String> = emptyMap(),
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
        if (c < 0) {
          return sb.toString().ifEmpty { null }
        }
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
      }
      return sb.toString()
    }

    private fun writeResponse(out: OutputStream, spec: ResponseSpec, body: BodyReader?) {
      val sb = StringBuilder()
      sb.append("HTTP/1.1 ").append(spec.code).append(' ').append(spec.reason).append("\r\n")
      sb.append("Content-Type: ").append(spec.type).append("\r\n")
      sb.append("Content-Length: ").append(spec.contentLength).append("\r\n")
      sb.append("Connection: close\r\n")
      spec.extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
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

    private fun openConnection(url: String, userAgent: String): HttpURLConnection {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.setRequestProperty("User-Agent", userAgent)
      conn.setRequestProperty("Accept", "*/*")
      conn.setRequestProperty("Connection", "keep-alive")
      return conn
    }
  }

  /** Tracks completed byte intervals and can wait until a contiguous region is filled. */
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

    fun hasAny(): Boolean = synchronized(lock) { map.isNotEmpty() }

    fun waitContiguous(pos: Long, length: Long, timeoutMs: Long): Boolean {
      val needEnd = min(totalSize, pos + length)
      val deadline = System.currentTimeMillis() + timeoutMs
      synchronized(lock) {
        while (true) {
          val avail = contiguousFrom(pos)
          if (pos + avail >= needEnd) return true
          val remaining = deadline - System.currentTimeMillis()
          if (remaining <= 0) return pos + avail > pos
          lock.wait(min(remaining, 200L))
        }
      }
    }
  }
}
