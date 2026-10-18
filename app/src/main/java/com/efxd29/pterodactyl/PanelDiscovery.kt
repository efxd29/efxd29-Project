package com.efxd29.pterodactyl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class RouteDecision(val url: String, val via: String)

object PanelDiscovery {
    const val CLOUD = "https://pemai.net"
    private const val PREF = "ptero_network"
    private const val LAST = "last_local"

    fun networkName(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return "Tidak ada jaringan aktif"
        val caps = cm.getNetworkCapabilities(n) ?: return "Jaringan tidak diketahui"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data seluler"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Jaringan lain"
        }
    }

    fun networkSignature(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return "none"
        val lp: LinkProperties? = cm.getLinkProperties(n)
        return networkName(c) + "|" + (lp?.linkAddresses?.joinToString { it.toString() } ?: "")
    }

    fun choose(c: Context): RouteDecision {
        if (networkName(c) != "Wi-Fi") return RouteDecision(CLOUD, "Cloudflare: bukan Wi-Fi")
        val prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = prefs.getString(LAST, null)
        if (!old.isNullOrBlank() && onSubnet(c, old) && isPanel(old, 500, 600)) {
            return RouteDecision(old, "IP lokal tersimpan")
        }
        val found = scan(c)
        if (found != null) {
            prefs.edit().putString(LAST, found).apply()
            return RouteDecision(found, "Panel terdeteksi di Wi-Fi lokal")
        }
        return RouteDecision(CLOUD, "Cloudflare: panel lokal tidak terdeteksi")
    }

    fun setCloud(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(LAST).apply()
    }

    fun setManual(c: Context, url: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(LAST, url.trimEnd('/')).apply()
    }

    fun diagnostics(c: Context, endpoint: String, trigger: String): String {
        val out = mutableListOf<String>()
        val network = networkName(c)
        out += "Jaringan: " + network
        out += "URL: " + endpoint
        out += "Error pemicu: " + (trigger.ifBlank { "Tidak ada detail WebView" })
        if (network == "Tidak ada jaringan aktif") {
            out += "Kesimpulan: perangkat tidak punya jaringan aktif."
            return out.joinToString("\n")
        }
        val host = try { URL(endpoint).host } catch (_: Exception) { "" }
        if (host.isBlank()) return (out + "URL panel tidak valid.").joinToString("\n")
        var dnsFailed = false
        try {
            out += "DNS: " + InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct().joinToString()
        } catch (e: Exception) {
            dnsFailed = true
            out += "DNS gagal: " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }
        var status: Int? = null
        try {
            val conn = URL(endpoint.trimEnd('/') + "/").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            status = conn.responseCode
            out += "HTTP: " + status + " " + (conn.responseMessage ?: "")
            conn.disconnect()
        } catch (e: Exception) {
            out += "HTTP gagal: " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }
        out += when {
            trigger.contains("SSL", true) || trigger.contains("certificate", true) ->
                "Kesimpulan: verifikasi TLS/sertifikat gagal. App tidak melewati error sertifikat."
            status != null && status!! >= 500 ->
                "Kesimpulan: server/proxy merespons HTTP " + status + ". Kode ini belum membuktikan komponen internal mana yang gagal."
            status != null && status!! in 200..399 ->
                "Kesimpulan: host merespons HTTP. Kemungkinan masalah pemuatan halaman, sesi, redirect, atau resource."
            dnsFailed ->
                "Kesimpulan: DNS gagal. Periksa DNS/domain atau koneksi jaringan."
            else ->
                "Kesimpulan: host tidak memberi HTTP yang bisa dipakai. Kemungkinan HP server offline/tidur, service berhenti, LAN/firewall, port, atau proxy. Status watchdog tidak dapat dipastikan dari klien saja."
        }
        out += "Waktu: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())
        return out.joinToString("\n")
    }

    private fun scan(c: Context): String? {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val local = props.linkAddresses.filter { it.address is Inet4Address && privateIp(it.address.hostAddress ?: "") }
        if (local.isEmpty()) return null
        val candidates = linkedSetOf<String>()
        for (link in local) {
            val addr = link.address as Inet4Address
            val b = addr.address
            val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
                ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
            val prefix = maxOf(link.prefixLength, 24).coerceAtMost(30)
            val mask = -1 shl (32 - prefix)
            val base = ip and mask
            val count = 1 shl (32 - prefix)
            for (i in 1 until count - 1) {
                val n = base + i
                val host = listOf((n ushr 24) and 255, (n ushr 16) and 255, (n ushr 8) and 255, n and 255).joinToString(".")
                if (host != addr.hostAddress) candidates.add(host)
            }
        }
        val winner = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(32)
        val jobs = mutableListOf<Future<*>>()
        for (host in candidates) {
            jobs += pool.submit(java.lang.Runnable {
                for (port in listOf(8080, 80, 8081)) {
                    if (winner.get() != null) return@Runnable
                    val url = "http://" + host + ":" + port
                    if (isPanel(url, 350, 500)) {
                        winner.compareAndSet(null, url)
                        return@Runnable
                    }
                }
            })
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
            for (job in jobs) {
                val remain = deadline - System.nanoTime()
                if (remain <= 0) break
                try { job.get(remain, TimeUnit.NANOSECONDS) } catch (_: Exception) { }
                if (winner.get() != null) return winner.get()
            }
        } finally {
            pool.shutdownNow()
        }
        return winner.get()
    }

    private fun isPanel(endpoint: String, connectMs: Int, readMs: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint.trimEnd('/') + "/").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("Accept", "text/html")
            val status = conn.responseCode
            val loc = conn.getHeaderField("Location") ?: ""
            if (status in 300..399 && loc.contains("login", true)) return true
            if (status !in 200..299) return false
            val buffer = ByteArray(8192)
            val len = conn.inputStream.use { it.read(buffer) }.coerceAtLeast(0)
            val html = String(buffer, 0, len, Charsets.UTF_8)
            html.contains("Pterodactyl", true) || html.contains("csrf-token", true)
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun onSubnet(c: Context, endpoint: String): Boolean {
        val host = try { URL(endpoint).host } catch (_: Exception) { return false }
        if (!privateIp(host)) return false
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return false
        val target = try { InetAddress.getByName(host) as Inet4Address } catch (_: Exception) { return false }
        return props.linkAddresses.any { link ->
            val a = link.address
            if (a !is Inet4Address) false else {
                val prefix = maxOf(link.prefixLength, 24).coerceAtMost(30)
                val mask = -1 shl (32 - prefix)
                (toInt(a.address) and mask) == (toInt(target.address) and mask)
            }
        }
    }

    private fun privateIp(s: String): Boolean {
        val p = s.split(".")
        if (p.size != 4) return false
        val a = p.map { it.toIntOrNull() ?: return false }
        if (a.any { it !in 0..255 }) return false
        return a[0] == 10 || (a[0] == 192 && a[1] == 168) || (a[0] == 172 && a[1] in 16..31)
    }

    private fun toInt(b: ByteArray) = ((b[0].toInt() and 255) shl 24) or
        ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
}
