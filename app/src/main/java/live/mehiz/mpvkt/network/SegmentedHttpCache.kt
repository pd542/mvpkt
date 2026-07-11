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
    if (!isAcceleratableUrl(originalUrl)) return originalUrl
    return try {
      val probe = probe(originalUrl)
      if (!probe.supportsRange || probe.contentLength <= 0L) {
        Log.i(TAG, "Range unsupported or unknown length → pass-through")
        return originalUrl
      }
      // Small files: multi-conn overhead not worth it
      if (probe.contentLength < chunkBytes.toLong()) {
        return originalUrl
      }
      val sess = Session(
        originUrl = probe.finalUrl,
        totalSize = probe.contentLength,
        contentType = probe.contentType ?: "application/octet-stream",
        connections = connections.coerceIn(2, 16),
        chunkBytes = chunkBytes.coerceIn(256 * 1024, 8 * 1024 * 1024),
        cacheDir = cacheDir,
        userAgent = userAgent,
      )
      sess.start()
      session = sess
      // Wait briefly for the first chunk so mpv does not hit empty stream immediately.
      sess.store.waitContiguous(0L, min(probe.contentLength, 256 * 1024L), 15_000L)
      sess.localUrl
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start segmented cache: ${e.message}", e)
      close()
      originalUrl
    }
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    session?.close()
    session = null
  }

  companion object {
    private const val TAG = "SegmentedHttpCache"
    private const val DEFAULT_UA =
      "mpvKt-segmented/1.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0"

    fun isAcceleratableUrl(url: String): Boolean {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
      // Adaptive streaming — not handled by byte-range multi-conn
      if (lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains("format=m3u")) return false
      return true
    }

    fun probe(url: String, userAgent: String = DEFAULT_UA): ProbeResult {
      var current = url
      // Prefer HEAD; some CDNs block HEAD → fall back to Range GET 0-0
      val head = openConnection(current, userAgent).apply {
        requestMethod = "HEAD"
        instanceFollowRedirects = true
        connectTimeout = 12_000
        readTimeout = 12_000
      }
      try {
        head.connect()
        val code = head.responseCode
        current = head.url.toString()
        val len = head.getHeaderFieldLong("Content-Length", -1L)
        val accept = head.getHeaderField("Accept-Ranges")?.lowercase(Locale.US)
        val type = head.contentType
        head.disconnect()
        if (code in 200..299 && len > 0) {
          // Accept-Ranges: none → no; missing/bytes → confirm with GET range
          if (accept?.contains("none") == true) {
            return ProbeResult(false, len, type, current)
          }
          return confirmRange(current, len, type, userAgent)
        }
      } catch (_: Exception) {
        runCatching { head.disconnect() }
      }
      return confirmRange(url, -1L, null, userAgent)
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
          val cr = conn.getHeaderField("Content-Range") // bytes 0-0/12345
          val total = parseTotalFromContentRange(cr) ?: knownLen
          conn.inputStream?.close()
          conn.disconnect()
          ProbeResult(total > 0, total, type, finalUrl)
        } else {
          val len = conn.getHeaderFieldLong("Content-Length", knownLen)
          conn.disconnect()
          ProbeResult(false, len, type, finalUrl)
        }
      } catch (e: Exception) {
        runCatching { conn.disconnect() }
        ProbeResult(false, knownLen, knownType, url)
      }
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
      if (header.isNullOrBlank()) return null
      // bytes 0-0/12345 or bytes 0-0/*
      val slash = header.lastIndexOf('/')
      if (slash < 0 || slash == header.lastIndex) return null
      val total = header.substring(slash + 1).trim()
      if (total == "*") return null
      return total.toLongOrNull()
    }

    private fun openConnection(url: String, userAgent: String): HttpURLConnection {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.setRequestProperty("User-Agent", userAgent)
      conn.setRequestProperty("Accept", "*/*")
      conn.setRequestProperty("Connection", "keep-alive")
      return conn
    }
  }

  // ── Session ─────────────────────────────────────────────────────────────

  private class Session(
    val originUrl: String,
    val totalSize: Long,
    val contentType: String,
    val connections: Int,
    val chunkBytes: Int,
    cacheDir: File,
    val userAgent: String,
  ) {
    val store = ContiguousStore(totalSize)
    private val file: File = File(cacheDir, "seg_${originUrl.hashCode().toUInt()}_$totalSize.bin")
    private val raf: RandomAccessFile
    private val executor = Executors.newFixedThreadPool(connections)
    private val serverExecutor = Executors.newCachedThreadPool()
    private val futures = mutableListOf<Future<*>>()
    private val running = AtomicBoolean(true)
    private val downloaded = AtomicLong(0)
    private var serverSocket: ServerSocket? = null
    val localUrl: String

    init {
      cacheDir.mkdirs()
      raf = RandomAccessFile(file, "rw")
      raf.setLength(totalSize)
      val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
      serverSocket = ss
      localUrl = "http://127.0.0.1:${ss.localPort}/media"
    }

    fun start() {
      // Accept loop
      serverExecutor.execute {
        val ss = serverSocket ?: return@execute
        while (running.get()) {
          try {
            val socket = ss.accept()
            serverExecutor.execute { handleClient(socket) }
          } catch (_: Exception) {
            if (!running.get()) break
          }
        }
      }

      // Build chunk list; prioritize start of file for playback.
      val chunks = ArrayList<LongRange>()
      var pos = 0L
      while (pos < totalSize) {
        val end = min(totalSize, pos + chunkBytes) - 1
        chunks.add(pos..end)
        pos = end + 1
      }
      // First 2 chunks sequential workers bias: submit head first then rest
      chunks.forEach { range ->
        futures += executor.submit { downloadRange(range.first, range.last) }
      }
      Log.i(TAG, "Started $connections workers, ${chunks.size} chunks, size=$totalSize url=$originUrl")
    }

    private fun downloadRange(start: Long, endInclusive: Long) {
      if (!running.get()) return
      // Skip if already complete
      if (store.isFullyCovered(start, endInclusive + 1)) return
      var attempt = 0
      while (attempt < 3 && running.get()) {
        attempt++
        var conn: HttpURLConnection? = null
        try {
          conn = openConnection(originUrl, userAgent).apply {
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
          val input = BufferedInputStream(conn.inputStream)
          val buf = ByteArray(64 * 1024)
          var writePos = start
          while (running.get()) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(raf) {
              raf.seek(writePos)
              raf.write(buf, 0, n)
            }
            writePos += n
            downloaded.addAndGet(n.toLong())
            store.mark(writePos - n, writePos)
            if (writePos > endInclusive) break
          }
          input.close()
          conn.disconnect()
          return
        } catch (e: Exception) {
          Log.w(TAG, "chunk $start-$endInclusive attempt $attempt failed: ${e.message}")
          runCatching { conn?.disconnect() }
          try {
            Thread.sleep(300L * attempt)
          } catch (_: InterruptedException) {
            return
          }
        }
      }
    }

    private fun handleClient(socket: Socket) {
      try {
        socket.soTimeout = 0
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readHttpRequest(input) ?: run {
          socket.close()
          return
        }
        if (request.method != "GET" && request.method != "HEAD") {
          writeResponse(output, 405, "Method Not Allowed", contentType, 0, emptyMap(), null)
          output.flush()
          socket.close()
          return
        }
        val range = parseRangeHeader(request.headers["range"], totalSize)
        if (range != null) {
          val (from, to) = range
          val length = to - from + 1
          // Wait until this range is available (or timeout → 503)
          val ready = store.waitContiguous(from, length, 60_000L)
          if (!ready && !store.hasAny()) {
            writeResponse(output, 503, "Service Unavailable", contentType, 0, emptyMap(), null)
            output.flush()
            socket.close()
            return
          }
          val headers = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $from-$to/$totalSize",
          )
          writeResponse(
            output,
            206,
            "Partial Content",
            contentType,
            length,
            headers,
            if (request.method == "HEAD") null else BodyReader(from, length),
          )
        } else {
          val headers = mapOf("Accept-Ranges" to "bytes")
          writeResponse(
            output,
            200,
            "OK",
            contentType,
            totalSize,
            headers,
            if (request.method == "HEAD") null else BodyReader(0L, totalSize),
          )
        }
        output.flush()
      } catch (e: Exception) {
        Log.d(TAG, "client error: ${e.message}")
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
          // Ensure contiguous data present
          if (!store.waitContiguous(pos, want.toLong(), 30_000L)) {
            // write what we can
            val avail = store.contiguousFrom(pos).toInt().coerceAtLeast(0)
            if (avail <= 0) break
            val n = min(avail, want)
            synchronized(raf) {
              raf.seek(pos)
              raf.readFully(buf, 0, n)
            }
            out.write(buf, 0, n)
            pos += n
            remaining -= n
            continue
          }
          synchronized(raf) {
            raf.seek(pos)
            raf.readFully(buf, 0, want)
          }
          out.write(buf, 0, want)
          pos += want
          remaining -= want
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
      // keep cache file for possible reuse; delete if incomplete optional
      Log.i(TAG, "Session closed, downloaded=${downloaded.get()}/$totalSize")
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
          val key = line.substring(0, idx).trim().lowercase(Locale.US)
          val value = line.substring(idx + 1).trim()
          headers[key] = value
        }
      }
      return HttpRequest(parts[0].uppercase(Locale.US), parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
      val sb = StringBuilder()
      while (true) {
        val c = input.read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
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

    /** @return pair from-to inclusive, or null for full body */
    private fun parseRangeHeader(header: String?, total: Long): Pair<Long, Long>? {
      if (header.isNullOrBlank()) return null
      // bytes=start-end | bytes=start-
      if (!header.lowercase(Locale.US).startsWith("bytes=")) return null
      val spec = header.substring(6).trim()
      val dash = spec.indexOf('-')
      if (dash < 0) return null
      val startStr = spec.substring(0, dash)
      val endStr = spec.substring(dash + 1)
      if (startStr.isEmpty()) return null // suffix ranges not needed
      val start = startStr.toLongOrNull() ?: return null
      val end = if (endStr.isEmpty()) total - 1 else endStr.toLongOrNull() ?: return null
      if (start < 0 || end >= total || start > end) return null
      return start to end
    }

    private fun openConnection(url: String, userAgent: String): HttpURLConnection {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.setRequestProperty("User-Agent", userAgent)
      conn.setRequestProperty("Accept", "*/*")
      conn.setRequestProperty("Connection", "keep-alive")
      return conn
    }
  }

  /**
   * Tracks completed byte intervals and can wait until a contiguous region is filled.
   */
  class ContiguousStore(private val totalSize: Long) {
    private val lock = Object()
    // merged half-open intervals [start, end)
    private val map = TreeMap<Long, Long>()

    fun mark(start: Long, end: Long) {
      if (end <= start) return
      synchronized(lock) {
        var s = start
        var e = end
        // merge with previous
        val prev = map.floorEntry(s)
        if (prev != null && prev.value >= s) {
          s = prev.key
          e = maxOf(e, prev.value)
          map.remove(prev.key)
        }
        // merge with overlapping next
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

    fun contiguousFrom(pos: Long): Long {
      synchronized(lock) {
        val e = map.floorEntry(pos) ?: return 0L
        if (e.value <= pos) return 0L
        return e.value - pos
      }
    }

    fun isFullyCovered(start: Long, endExclusive: Long): Boolean {
      synchronized(lock) {
        val e = map.floorEntry(start) ?: return false
        return e.value >= endExclusive
      }
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
