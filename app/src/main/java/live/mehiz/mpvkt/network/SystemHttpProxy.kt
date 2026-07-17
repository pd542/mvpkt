@file:Suppress("ReturnCount", "ComplexCondition", "TooManyFunctions")

package live.mehiz.mpvkt.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.os.Build
import android.util.Log
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale

/**
 * Detect Android system / VPN networking for playback.
 *
 * Two different modes must not be mixed:
 * - **VPN / TUN (NekoBox 全局 VPN)**: traffic is already transparent at the IP layer.
 *   There is usually no useful app-layer HTTP proxy, or NekoBox may still expose a
 *   local mixed-port [ProxyInfo]. Injecting that into libmpv as `http-proxy` would
 *   **double-hop** and break Emby / media-server streams. Callers must treat VPN as
 *   "application direct connect".
 * - **System HTTP proxy only** (no VPN): libmpv does not read [ProxyInfo] by itself —
 *   callers pass [Info.mpvHttpProxyUrl] into mpv's `http-proxy`.
 * - **No proxy / no VPN**: always direct (`http-proxy` empty, Java sockets without Proxy).
 *
 * Localhost must never be forced through an HTTP proxy (breaks 127.0.0.1 multi-conn).
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
      val host = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.US)
        ?: return false
      if (isLoopbackOrLocalHost(host)) return true
      return matchesExclusion(host, exclusionList)
    }
  }

  /**
   * True when an active network is a VPN (NekoBox / Clash TUN 全局代理, system VPN, etc.).
   * Transparent — app sockets should stay "direct"; the OS routes them.
   */
  fun isVpnActive(context: Context): Boolean {
    return runCatching { detectVpn(context) }.getOrDefault(false)
  }

  /**
   * App-layer HTTP(S) proxy only.
   * Returns null when:
   * - preference callers skip us,
   * - no system proxy is configured,
   * - **or a VPN is active** (transparent mode — do not double-proxy).
   */
  fun current(context: Context): Info? {
    if (isVpnActive(context)) {
      Log.d(TAG, "VPN active — skip app-layer HTTP proxy (transparent)")
      return null
    }
    return fromConnectivityManager(context) ?: fromSystemProperties()
  }

  fun isActive(context: Context): Boolean = current(context) != null

  private fun detectVpn(context: Context): Boolean {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
      as? ConnectivityManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val network = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(network) ?: return false
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } else {
      isLegacyVpnActive(cm)
    }
  }

  @Suppress("DEPRECATION")
  private fun isLegacyVpnActive(cm: ConnectivityManager): Boolean {
    return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_VPN
  }

  private fun fromConnectivityManager(context: Context): Info? {
    return runCatching {
      val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
      val proxy = cm?.let { readDefaultProxy(it) } ?: return@runCatching null
      buildInfo(proxy.host?.trim().orEmpty(), proxy.port, proxy.exclusionList?.toList().orEmpty())
    }.getOrNull()
  }

  private fun readDefaultProxy(cm: ConnectivityManager): ProxyInfo? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return cm.defaultProxy
    }
    return readLegacyDefaultProxy()
  }

  @Suppress("DEPRECATION")
  private fun readLegacyDefaultProxy(): ProxyInfo? {
    val host = android.net.Proxy.getDefaultHost()
    val port = android.net.Proxy.getDefaultPort()
    return if (!host.isNullOrBlank() && port > 0) {
      ProxyInfo.buildDirectProxy(host, port)
    } else {
      null
    }
  }

  private fun fromSystemProperties(): Info? {
    val host = System.getProperty("http.proxyHost")?.trim().orEmpty()
      .ifEmpty { System.getProperty("https.proxyHost")?.trim().orEmpty() }
    val port = (
      System.getProperty("http.proxyPort")
        ?: System.getProperty("https.proxyPort")
      )?.toIntOrNull()
    val excl = System.getProperty("http.nonProxyHosts")
      ?.split("|", ",", ";")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      .orEmpty()
    return buildInfo(host, port ?: 0, excl)
  }

  private fun buildInfo(host: String, port: Int, exclusionList: List<String>): Info? {
    if (host.isEmpty() || port <= 0 || port > 65535) return null
    return Info(
      host = host,
      port = port,
      mpvHttpProxyUrl = "http://$host:$port",
      javaProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)),
      exclusionList = exclusionList,
    ).also { Log.d(TAG, "system proxy ${it.mpvHttpProxyUrl} excl=$exclusionList") }
  }

  private fun isLoopbackOrLocalHost(host: String): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")

  private fun matchesExclusion(host: String, exclusionList: List<String>): Boolean {
    return exclusionList.any { rule ->
      val r = rule.lowercase(Locale.US).trim()
      when {
        r.isEmpty() -> false
        r.startsWith("*.") -> {
          val suffix = r.removePrefix("*")
          host.endsWith(suffix) || host == r.removePrefix("*.")
        }
        else -> host == r || host.endsWith(".$r")
      }
    }
  }
}
