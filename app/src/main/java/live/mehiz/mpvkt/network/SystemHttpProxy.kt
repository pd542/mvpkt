package live.mehiz.mpvkt.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.os.Build
import android.util.Log
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale

/**
 * Detect Android system / VPN HTTP(S) proxy (e.g. NekoBox "HTTP proxy" / system proxy).
 *
 * Notes:
 * - VPN TUN mode usually has no [ProxyInfo]; traffic is transparent at IP layer.
 * - System HTTP proxy mode is what libmpv does **not** pick up by itself — callers
 *   must pass [mpvHttpProxyUrl] into mpv's `http-proxy` option.
 * - Localhost must never be forced through this proxy (breaks 127.0.0.1 multi-conn).
 */
object SystemHttpProxy {
  private const val TAG = "SystemHttpProxy"

  data class Info(
    val host: String,
    val port: Int,
    /** HTTP CONNECT proxy URL for mpv / FFmpeg (`http://host:port`). */
    val mpvHttpProxyUrl: String,
    val javaProxy: Proxy,
    val exclusionList: List<String>,
  ) {
    fun shouldBypass(url: String): Boolean {
      val host = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.US) ?: return false
      if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) {
        return true
      }
      return exclusionList.any { rule ->
        val r = rule.lowercase(Locale.US).trim()
        if (r.isEmpty()) return@any false
        if (r.startsWith("*.")) {
          host.endsWith(r.removePrefix("*")) || host == r.removePrefix("*.")
        } else {
          host == r || host.endsWith(".$r")
        }
      }
    }
  }

  fun current(context: Context): Info? {
    val fromConnectivity = fromConnectivityManager(context)
    if (fromConnectivity != null) return fromConnectivity
    return fromSystemProperties()
  }

  fun isActive(context: Context): Boolean = current(context) != null

  private fun fromConnectivityManager(context: Context): Info? {
    return runCatching {
      val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
      val proxy: ProxyInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        cm.defaultProxy
      } else {
        @Suppress("DEPRECATION")
        android.net.Proxy.getDefaultHost()?.let { host ->
          @Suppress("DEPRECATION")
          val port = android.net.Proxy.getDefaultPort()
          if (port > 0) ProxyInfo.buildDirectProxy(host, port) else null
        }
      } ?: return null

      val host = proxy.host?.trim().orEmpty()
      val port = proxy.port
      if (host.isEmpty() || port <= 0 || port > 65535) return null
      // PAC-only without host is not usable for libmpv's simple http-proxy.
      if (host.equals("localhost", true) || host == "127.0.0.1") {
        // Some clients publish a local mixed-port proxy — still valid.
      }
      val excl = proxy.exclusionList?.toList().orEmpty()
      Info(
        host = host,
        port = port,
        mpvHttpProxyUrl = "http://$host:$port",
        javaProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)),
        exclusionList = excl,
      ).also { Log.d(TAG, "system proxy ${it.mpvHttpProxyUrl} excl=$excl") }
    }.getOrNull()
  }

  private fun fromSystemProperties(): Info? {
    val host = System.getProperty("http.proxyHost")?.trim().orEmpty()
      .ifEmpty { System.getProperty("https.proxyHost")?.trim().orEmpty() }
    val port = (
      System.getProperty("http.proxyPort")
        ?: System.getProperty("https.proxyPort")
      )?.toIntOrNull() ?: return null
    if (host.isEmpty() || port <= 0) return null
    val excl = System.getProperty("http.nonProxyHosts")
      ?.split("|", ",", ";")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      .orEmpty()
    return Info(
      host = host,
      port = port,
      mpvHttpProxyUrl = "http://$host:$port",
      javaProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)),
      exclusionList = excl,
    )
  }
}
