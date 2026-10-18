package com.efxd29.pterodactyl

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var root: FrameLayout
    private var loadingOverlay: View? = null
    private var hasLoadedPage = false
    private var route = RouteDecision(PanelDiscovery.CLOUD, "Cloudflare")
    private var signature = ""
    private var pending: ValueCallback<Array<Uri>>? = null
    private var reportPage: ScrollView? = null
    private var reportText: TextView? = null
    private var diagnosing = false
    private var lastError = ""
    @Volatile private var lastConsoleRecoveryAtMs = 0L
    @Volatile private var hideNextProgress = false

    private val sessionVault by lazy { SessionVault(applicationContext) }
    private val autoLoginAttemptedHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 17, 20)
        window.navigationBarColor = Color.rgb(16, 17, 20)
        root = FrameLayout(this).apply { setBackgroundColor(PANEL_BODY_COLOR) }
        web = WebView(this).apply {
            setBackgroundColor(PANEL_BODY_COLOR)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(79, 140, 255))
            visibility = View.GONE
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        showLoadingOverlay()
        setContentView(root)
        configureWeb()
        signature = PanelDiscovery.networkSignature(this)
        openBestRoute(true)
    }

    private fun configureWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(NativeBridge(), "PterodactylNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme != "http" && u.scheme != "https") {
                    return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (_: Exception) { true }
                }
                val currentHost = try { Uri.parse(route.url).host } catch (_: Exception) { null }
                val isPanelHost = u.host.equals(currentHost, true) ||
                    u.host.equals("pemai.net", true) || u.host.equals("www.pemai.net", true)
                if (isPanelHost) {
                    val localMode = route.via.contains("lokal", true) || route.via.contains("Wi-Fi", true)
                    if (localMode && (u.host.equals("pemai.net", true) || u.host.equals("www.pemai.net", true))) {
                        val local = Uri.parse(route.url)
                        val port = if (local.port > 0) ":" + local.port else ""
                        val authority = (local.host ?: return false) + port
                        val mapped = Uri.Builder().scheme(local.scheme).encodedAuthority(authority)
                            .encodedPath(u.encodedPath ?: "/").encodedQuery(u.encodedQuery)
                            .encodedFragment(u.encodedFragment).build()
                        v.loadUrl(mapped.toString())
                        return true
                    }
                    return false
                }
                return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (_: Exception) { true }
            }

            override fun onPageStarted(v: WebView, url: String?, icon: android.graphics.Bitmap?) {
                if (!hasLoadedPage && loadingOverlay != null) {
                    progress.visibility = View.GONE
                } else if (hideNextProgress) {
                    hideNextProgress = false
                    progress.visibility = View.GONE
                } else {
                    progress.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(v: WebView, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                if (!url.isNullOrBlank()) {
                    hasLoadedPage = true
                    hideLoadingOverlay()
                }
                if (reportPage != null && !url.isNullOrBlank()) hideReport()
                if (!url.isNullOrBlank() && isPanelUrl(url)) {
                    installSessionHelper()
                    if (isServerConsoleUrl(url)) {
                        installConsoleRecovery()
                    } else {
                        installLiveUpdateMonitor()
                    }
                    web.evaluateJavascript("!!document.querySelector('input[type=password]')") { result ->
                        if (!isFinishing && !isDestroyed && isPanelUrl(url)) {
                            if (result == "false") sessionVault.promotePending()
                            else if (result == "true") sessionVault.clearPending()
                        }
                    }
                }
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(v, request, error)
                if (request.isForMainFrame) {
                    if (isLocalRoute() && !isCloudRoute()) {
                        fallbackToCloudflare("Alamat panel lokal tidak merespons (" + error.errorCode + ").")
                    } else {
                        hideLoadingOverlay()
                        showFailure("Panel tidak bisa dijangkau", "WebView " + error.errorCode + ": " + error.description)
                    }
                }
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                super.onReceivedHttpError(v, request, response)
                if (request.isForMainFrame && response.statusCode >= 400) {
                    if (isLocalRoute() && !isCloudRoute()) {
                        fallbackToCloudflare("Panel lokal membalas HTTP " + response.statusCode + ".")
                    } else if (response.statusCode >= 500) {
                        hideLoadingOverlay()
                        showFailure("Panel membalas HTTP " + response.statusCode,
                            "HTTP " + response.statusCode + " " + (response.reasonPhrase ?: ""))
                    }
                }
            }

            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showFailure("HTTPS tidak aman",
                    "Sertifikat/TLS gagal diverifikasi (" + error.primaryError + "). Aplikasi tidak mengabaikan error sertifikat.")
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                showFailure("Tampilan panel berhenti",
                    "Renderer WebView berhenti (crash=" + detail.didCrash() + "). Ini bukan bukti HP server mati.")
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                pending?.onReceiveValue(null)
                pending = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_PICKER_REQUEST)
                    true
                } catch (_: Exception) {
                    pending?.onReceiveValue(null)
                    pending = null
                    Toast.makeText(this@MainActivity, "Pemilih file tidak tersedia.", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        web.setDownloadListener(DownloadListener { url, userAgent, disposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setTitle(filename(disposition, url))
                request.setDescription("Download dari panel Pterodactyl")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename(disposition, url))
                request.setAllowedOverMetered(true)
                request.setAllowedOverRoaming(true)
                if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let {
                    if (it.isNotBlank()) request.addRequestHeader("Cookie", it)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Download dimulai.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download gagal: " + (e.message ?: "error"), Toast.LENGTH_LONG).show()
            }
        })
    }

    /**
     * Detects changed server-rendered HTML and changed JavaScript/CSS asset versions.
     * All polling is native-app WebView code. It never writes to the panel or server.
     */
    /**
     * Console recovery stays entirely inside this APK. On a power transition, ask the existing
     * panel WebSocket handler to resync via its online event. If no new console row follows,
     * perform one throttled silent page reconnect so the console re-subscribes to log output.
     */
    private fun installConsoleRecovery() {
        val script = """
            (function () {
              try {
                if (window.__pteroConsoleRecoveryTimer) clearInterval(window.__pteroConsoleRecoveryTimer);
                var parts = location.pathname.split('/').filter(Boolean);
                if (parts.length < 2 || parts[0] !== 'server') return;

                var lastLine = '';
                var lastState = '';
                var pending = false;
                var pendingAt = 0;
                var recoveryRequested = false;

                function getLastRow() {
                  var rows = document.querySelectorAll('.xterm-rows > div');
                  for (var i = rows.length - 1; i >= 0; i--) {
                    var line = (rows[i].innerText || rows[i].textContent || '').replace(/\u00a0/g, ' ').trim();
                    if (line) return line;
                  }
                  return '';
                }

                function trySoftResync() {
                  try { window.dispatchEvent(new Event('online')); } catch (_) {}
                  try { document.dispatchEvent(new Event('visibilitychange')); } catch (_) {}
                }

                function inspect() {
                  var line = getLastRow();
                  if (!line) return;

                  if (line !== lastLine) {
                    lastLine = line;
                    var match = line.match(/Server marked as (starting|stopping|running|offline)/i);
                    if (match) {
                      var state = match[1].toLowerCase();
                      if ((state === 'starting' || state === 'stopping') && state !== lastState) {
                        pending = true;
                        pendingAt = Date.now();
                        recoveryRequested = false;
                        setTimeout(trySoftResync, 400);
                      } else if (pending && state !== lastState) {
                        // Keep a single recovery window through the stopping -> starting -> running cycle.
                        pendingAt = Date.now();
                      }
                      lastState = state;
                    } else if (pending) {
                      // A new row after a lifecycle marker means the log stream resumed.
                      pending = false;
                      recoveryRequested = false;
                    }
                  }

                  if (pending && !recoveryRequested && Date.now() - pendingAt >= 9000) {
                    var active = document.activeElement;
                    var commandInput = active && active.matches &&
                      active.matches('input[placeholder*="command" i]');
                    if (commandInput) return;
                    recoveryRequested = true;
                    try {
                      if (window.PterodactylNative && window.PterodactylNative.recoverConsole) {
                        window.PterodactylNative.recoverConsole();
                      }
                    } catch (_) {}
                  }
                }

                window.__pteroConsoleRecoveryTimer = setInterval(inspect, 500);
                setTimeout(inspect, 350);
              } catch (_) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private fun installLiveUpdateMonitor() {
        val script = """
            (function () {
              try {
                if (window.__pteroLiveUpdateTimer) clearInterval(window.__pteroLiveUpdateTimer);
                // Server/console pages must not be polled/reloaded by live-update checks:
                // doing so can tear down the authenticated console WebSocket during power actions.
                var routeParts = location.pathname.split('/').filter(Boolean);
                if (routeParts.length >= 2 && routeParts[0] === 'server') {
                  window.__pteroLiveUpdateTimer = null;
                  return;
                }
                var observedUrl = location.href;
                var baseline = null;
                var running = false;
                var candidate = null;
                var candidateCount = 0;
                var lastAssetCheck = 0;
                var assetCache = '';

                function digest(value) {
                  var hash = 2166136261;
                  for (var i = 0; i < value.length; i++) {
                    hash ^= value.charCodeAt(i);
                    hash = Math.imul(hash, 16777619);
                  }
                  return (hash >>> 0).toString(16);
                }

                async function assetState(doc) {
                  var now = Date.now();
                  if (assetCache && now - lastAssetCheck < 1500) return assetCache;
                  var nodes = Array.from(doc.querySelectorAll('script[src], link[rel~="stylesheet"][href]'));
                  var urls = Array.from(new Set(nodes.map(function (n) { return n.src || n.href; }))).filter(function (u) {
                    try { return new URL(u, location.href).origin === location.origin; } catch (_) { return false; }
                  }).sort();
                  var parts = await Promise.all(urls.map(async function (u) {
                    try {
                      var r = await fetch(u, {
                        method: 'HEAD',
                        cache: 'no-store',
                        credentials: 'include',
                        headers: { 'Cache-Control': 'no-cache', 'Pragma': 'no-cache' }
                      });
                      // Ignore transient/non-success responses instead of mistaking an outage for a deploy.
                      if (!r.ok) return null;
                      var etag = r.headers.get('ETag') || '';
                      var modified = r.headers.get('Last-Modified') || '';
                      var length = r.headers.get('Content-Length') || '';
                      return [u, r.status, etag, modified, length].join('|');
                    } catch (_) {
                      return null;
                    }
                  }));
                  if (parts.some(function (p) { return p === null; })) return null;
                  assetCache = parts.join('\n');
                  lastAssetCheck = now;
                  return assetCache;
                }

                async function getSignature() {
                  var requestUrl = location.href;
                  var response = await fetch(requestUrl, {
                    method: 'GET',
                    cache: 'no-store',
                    credentials: 'include',
                    headers: {
                      'Cache-Control': 'no-cache',
                      'Pragma': 'no-cache',
                      'X-Requested-With': 'Pterodactyl-Android-Live-Check'
                    }
                  });
                  if (!response.ok || !/text\/html/i.test(response.headers.get('Content-Type') || '')) return null;
                  try {
                    // Don't treat auth redirects or navigations as code updates.
                    if (new URL(response.url).pathname !== new URL(requestUrl).pathname) return null;
                  } catch (_) { return null; }
                  var html = await response.text();
                  var doc = new DOMParser().parseFromString(html, 'text/html');
                  // Compare only asset paths and stable validators, never dynamic HTML, CSRF tokens,
                  // per-request markup, or session details. That prevents an endless reload loop.
                  var urls = Array.from(new Set(Array.from(doc.querySelectorAll('script[src], link[rel~="stylesheet"][href]')).map(function (n) {
                    try {
                      var parsed = new URL(n.src || n.href, location.href);
                      if (parsed.origin !== location.origin) return null;
                      return parsed.pathname + parsed.search;
                    } catch (_) { return null; }
                  }).filter(Boolean))).sort().join('\n');
                  var assets = await assetState(doc);
                  if (assets === null) return null;
                  return digest(urls + '\n--asset-validators--\n' + assets);
                }

                function isEditing() {
                  var active = document.activeElement;
                  if (!active) return false;
                  if (active.matches && active.matches('input:not([type="hidden"]), textarea, [contenteditable="true"], [role="textbox"]')) return true;
                  if (active.closest && active.closest('.xterm, .xterm-screen, [data-ptero-console]')) return true;
                  return false;
                }

                function showUpdateBanner() {
                  if (document.getElementById('ptero-app-update-banner')) return;
                  var banner = document.createElement('div');
                  banner.id = 'ptero-app-update-banner';
                  banner.setAttribute('role', 'status');
                  banner.style.cssText = 'position:fixed;z-index:2147483647;top:8px;left:12px;right:12px;padding:12px 14px;border-radius:12px;background:#202733;color:#fff;box-shadow:0 4px 20px #0008;font:14px sans-serif;display:flex;align-items:center;gap:12px;';
                  var text = document.createElement('span');
                  text.textContent = 'Perubahan panel tersedia. Muat versi baru?';
                  text.style.cssText = 'flex:1;line-height:1.35;';
                  var button = document.createElement('button');
                  button.type = 'button';
                  button.textContent = 'Muat';
                  button.style.cssText = 'border:0;border-radius:8px;padding:8px 12px;background:#477fe5;color:white;font-weight:600;';
                  button.addEventListener('click', function () {
                    if (window.PterodactylNative && window.PterodactylNative.applyUpdate) {
                      window.PterodactylNative.applyUpdate();
                    } else {
                      location.reload();
                    }
                  });
                  banner.appendChild(text);
                  banner.appendChild(button);
                  document.documentElement.appendChild(banner);
                }

                async function poll() {
                  if (running || document.visibilityState !== 'visible') return;
                  if (location.href !== observedUrl) {
                    observedUrl = location.href;
                    baseline = null;
                    candidate = null;
                    candidateCount = 0;
                    var oldBanner = document.getElementById('ptero-app-update-banner');
                    if (oldBanner) oldBanner.remove();
                    return;
                  }
                  running = true;
                  try {
                    var next = await getSignature();
                    if (next === null) {
                      candidate = null;
                      candidateCount = 0;
                      return;
                    }
                    if (baseline === null) {
                      baseline = next;
                      return;
                    }
                    if (next === baseline) {
                      candidate = null;
                      candidateCount = 0;
                      return;
                    }
                    // Require the same changed signature twice to filter temporary CDN/header noise.
                    if (candidate === next) candidateCount += 1;
                    else {
                      candidate = next;
                      candidateCount = 1;
                    }
                    if (candidateCount < 2) return;

                    baseline = next;
                    candidate = null;
                    candidateCount = 0;
                    if (isEditing()) {
                      showUpdateBanner();
                      return;
                    }
                    if (window.PterodactylNative && window.PterodactylNative.applyUpdateSilently) {
                      window.PterodactylNative.applyUpdateSilently();
                    } else {
                      // Fallback without native support: mark update only; avoid disruptive reload loops.
                      showUpdateBanner();
                    }
                  } catch (_) {
                    // Network errors don't count as code changes and never trigger a page reload.
                  } finally {
                    running = false;
                  }
                }

                window.__pteroLiveUpdateTimer = setInterval(poll, 1000);
                setTimeout(poll, 250);
              } catch (_) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private fun reloadCurrentPanel(silent: Boolean) {
        runOnUiThread {
            if (!::web.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
            val current = web.url ?: route.url
            if (silent) hideNextProgress = true
            // Clear the in-memory resource cache only. Cookies and local/session storage remain intact.
            web.clearCache(false)
            web.loadUrl(current)
            CookieManager.getInstance().flush()
            if (silent) progress.visibility = View.GONE
        }
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun applyUpdate() {
            reloadCurrentPanel(silent = false)
        }

        @JavascriptInterface
        fun applyUpdateSilently() {
            reloadCurrentPanel(silent = true)
        }

        @JavascriptInterface
        fun stageLoginCredentials(json: String) {
            if (!isTrustedPanelOrigin()) return
            try {
                val obj = JSONObject(json)
                val username = obj.optString("username").trim()
                val password = obj.optString("password")
                if (username.isNotBlank() && password.isNotBlank() &&
                    username.length <= 512 && password.length <= 4096) {
                    sessionVault.stage(username, password)
                }
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun recoverConsole() {
            if (!isTrustedPanelOrigin() || !isServerConsoleUrl(web.url ?: "")) return
            val now = System.currentTimeMillis()
            synchronized(this@MainActivity) {
                if (now - lastConsoleRecoveryAtMs < 45_000L) return
                lastConsoleRecoveryAtMs = now
            }
            reloadCurrentPanel(silent = true)
        }

        @JavascriptInterface
        fun getSavedLoginCredentials(): String {
            if (!isTrustedPanelOrigin()) return ""
            return sessionVault.loadSaved() ?: ""
        }

        @JavascriptInterface
        fun promoteLoginCredentials() {
            if (isTrustedPanelOrigin()) sessionVault.promotePending()
        }

        @JavascriptInterface
        fun beginAutoLogin(): Boolean {
            if (!isTrustedPanelOrigin()) return false
            val host = try { Uri.parse(web.url ?: "").host?.lowercase() } catch (_: Exception) { null } ?: return false
            return autoLoginAttemptedHosts.add(host)
        }

        @JavascriptInterface
        fun clearLoginCredentials() {
            if (!isTrustedPanelOrigin()) return
            sessionVault.clearAll()
            // Let the current logout POST carry its existing session cookie first.
            runOnUiThread {
                web.postDelayed({
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                }, 1500L)
            }
        }
    }

    private fun isPanelUrl(value: String): Boolean {
        val uri = try { Uri.parse(value) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https" && uri.scheme != "http") return false
        return host == "pemai.net" || host == "www.pemai.net" ||
            host == "localhost" || host == "127.0.0.1" || isPrivateIpv4(host)
    }

    private fun isTrustedPanelOrigin(): Boolean = isPanelUrl(web.url ?: "")

    private fun isServerConsoleUrl(value: String): Boolean {
        val uri = try { Uri.parse(value) } catch (_: Exception) { return false }
        val segments = (uri.path ?: "").split('/').filter { it.isNotBlank() }
        return segments.size >= 2 && segments[0] == "server"
    }

    private fun isPrivateIpv4(host: String): Boolean {
        if (!host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false
        val parts = host.split(".").map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    /**
     * Login credentials are encrypted using an Android Keystore AES/GCM key.
     * They are never written to preferences in plaintext, and explicit logout clears them.
     * Android's app sandbox/key lifetime means uninstall or app-data deletion removes them.
     */
    private inner class SessionVault(context: Context) {
        private val prefs = context.getSharedPreferences("pterodactyl_encrypted_session", Context.MODE_PRIVATE)
        private val alias = "pterodactyl_panel_session_v1"

        @Synchronized
        fun stage(username: String, password: String) {
            try {
                val encrypted = encrypt(JSONObject().put("username", username).put("password", password).toString())
                prefs.edit().putString("pending_cipher", encrypted.first)
                    .putString("pending_iv", encrypted.second).apply()
            } catch (_: Exception) {
                clearPending()
            }
        }

        @Synchronized
        fun promotePending() {
            val cipherText = prefs.getString("pending_cipher", null) ?: return
            val iv = prefs.getString("pending_iv", null) ?: return
            prefs.edit().putString("saved_cipher", cipherText).putString("saved_iv", iv)
                .remove("pending_cipher").remove("pending_iv").apply()
            autoLoginAttemptedHosts.clear()
        }

        @Synchronized
        fun clearPending() {
            prefs.edit().remove("pending_cipher").remove("pending_iv").apply()
        }

        @Synchronized
        fun loadSaved(): String? {
            val cipherText = prefs.getString("saved_cipher", null) ?: return null
            val iv = prefs.getString("saved_iv", null) ?: return null
            return try {
                decrypt(cipherText, iv)
            } catch (_: Exception) {
                clearAll()
                null
            }
        }

        @Synchronized
        fun clearAll() {
            prefs.edit().clear().apply()
            autoLoginAttemptedHosts.clear()
        }

        private fun encrypt(value: String): Pair<String, String> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            return Base64.encodeToString(encrypted, Base64.NO_WRAP) to
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }

        private fun decrypt(cipherText: String, encodedIv: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP))
            return String(plain, StandardCharsets.UTF_8)
        }

        private fun getOrCreateKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null)
            if (existing is SecretKey) return existing
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }

    private fun installSessionHelper() {
        val script = """
            (function () {
              try {
                if (window.__pteroSessionHelperInstalled) return;
                window.__pteroSessionHelperInstalled = true;
                var nativeApp = window.PterodactylNative;
                if (!nativeApp) return;

                function getLoginForm() {
                  var forms = Array.from(document.forms || []);
                  for (var i = 0; i < forms.length; i++) {
                    var f = forms[i];
                    var p = f.querySelector('input[type="password"]');
                    var u = f.querySelector('input[type="email"], input[autocomplete="username"], input[name="email"], input[name="username"], input[name="user"]');
                    if (p && u) return { form: f, username: u, password: p };
                  }
                  return null;
                }
                function stage(formInfo) {
                  if (!formInfo || !formInfo.username.value || !formInfo.password.value) return;
                  try {
                    nativeApp.stageLoginCredentials(JSON.stringify({
                      username: formInfo.username.value,
                      password: formInfo.password.value
                    }));
                  } catch (_) {}
                }
                function isLogoutElement(el) {
                  if (!el || !el.closest) return false;
                  var item = el.closest('a,button,[role="button"],input[type="submit"],form');
                  if (!item) return false;
                  var href = item.getAttribute('href') || '';
                  var action = item.getAttribute('action') || (item.form && item.form.action) || '';
                  var text = ((item.innerText || item.value || item.getAttribute('aria-label') || '') + '').trim();
                  return /(^|[/_-])logout([/?#_-]|$)|sign[-_ ]?out|log[-_ ]?out/i.test(href + ' ' + action) ||
                    /^(log\s*out|sign\s*out|keluar|logout|signout)$/i.test(text);
                }
                function clearForLogout() {
                  try { nativeApp.clearLoginCredentials(); } catch (_) {}
                }

                document.addEventListener('submit', function (event) {
                  var form = event.target;
                  if (!form || !form.querySelector) return;
                  var action = form.action || '';
                  if (/logout|sign[-_]?out/i.test(action)) {
                    clearForLogout();
                    return;
                  }
                  var p = form.querySelector('input[type="password"]');
                  var u = form.querySelector('input[type="email"], input[autocomplete="username"], input[name="email"], input[name="username"], input[name="user"]');
                  if (p && u) stage({ form: form, username: u, password: p });
                }, true);

                document.addEventListener('click', function (event) {
                  if (isLogoutElement(event.target)) {
                    clearForLogout();
                    return;
                  }
                  var formInfo = getLoginForm();
                  if (formInfo && formInfo.form.contains(event.target)) {
                    var submit = event.target.closest && event.target.closest('button,input[type="submit"],[role="button"]');
                    if (submit) stage(formInfo);
                  }
                }, true);

                // Also notice SPA-style logout requests initiated by fetch.
                if (window.fetch && !window.__pteroFetchWrapped) {
                  window.__pteroFetchWrapped = true;
                  var originalFetch = window.fetch.bind(window);
                  window.fetch = function (input, init) {
                    var u = typeof input === 'string' ? input : (input && input.url) || '';
                    if (/logout|sign[-_]?out/i.test(u)) clearForLogout();
                    return originalFetch(input, init);
                  };
                }

                var formInfo = getLoginForm();
                if (formInfo && !window.__pteroAutologinTried) {
                  window.__pteroAutologinTried = true;
                  var saved = '';
                  var allowedAttempt = false;
                  try {
                    saved = nativeApp.getSavedLoginCredentials();
                    if (saved) allowedAttempt = nativeApp.beginAutoLogin();
                  } catch (_) {}
                  if (saved && allowedAttempt) {
                    try {
                      var credentials = JSON.parse(saved);
                      if (credentials.username && credentials.password) {
                        formInfo.username.value = credentials.username;
                        formInfo.password.value = credentials.password;
                        ['input', 'change'].forEach(function (name) {
                          formInfo.username.dispatchEvent(new Event(name, { bubbles: true }));
                          formInfo.password.dispatchEvent(new Event(name, { bubbles: true }));
                        });
                        setTimeout(function () {
                          var latest = getLoginForm();
                          if (!latest || !latest.username.value || !latest.password.value) return;
                          stage(latest);
                          if (latest.form.requestSubmit) latest.form.requestSubmit();
                          else latest.form.submit();
                        }, 400);
                      }
                    } catch (_) {}
                  }
                }

                // If an SPA login succeeds without a full navigation, promote the staged secret
                // once the password field disappears; a failed login leaves it pending only.
                var observer = new MutationObserver(function () {
                  if (!getLoginForm()) {
                    try { nativeApp.promoteLoginCredentials(); } catch (_) {}
                  }
                });
                observer.observe(document.documentElement, { childList: true, subtree: true });
              } catch (_) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private fun openBestRoute(force: Boolean = false) {
        signature = PanelDiscovery.networkSignature(this)
        hideReport()
        val previousUrl = web.url
        val decision = PanelDiscovery.choose(this)
        val routeChanged = previousUrl.isNullOrBlank() || !previousUrl.startsWith(decision.url)
        route = decision

        // Route selection contains no DNS, HTTP or IP scan; load the chosen endpoint immediately.
        if (force || routeChanged) {
            if (!hasLoadedPage) showLoadingOverlay()
            progress.visibility = if (hasLoadedPage) View.VISIBLE else View.GONE
            web.loadUrl(decision.url)
        } else {
            progress.visibility = View.GONE
        }
    }

    private fun isLocalRoute(): Boolean =
        route.via.contains("lokal", true) || route.via.contains("Wi-Fi", true)

    private fun isCloudRoute(): Boolean = route.url.startsWith("https://pemai.net", true)

    private fun fallbackToCloudflare(reason: String) {
        if (isFinishing || isDestroyed || isCloudRoute()) {
            hideLoadingOverlay()
            showFailure("Panel tidak bisa dijangkau", reason)
            return
        }
        route = RouteDecision(PanelDiscovery.CLOUD, "Cloudflare fallback")
        if (!hasLoadedPage) showLoadingOverlay()
        progress.visibility = View.GONE
        web.loadUrl(route.url)
    }

    private fun showLoadingOverlay() {
        if (loadingOverlay != null || !::root.isInitialized) return
        val layer = FrameLayout(this).apply {
            setBackgroundColor(PANEL_BODY_COLOR)
            isClickable = true
            isFocusable = true
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
        }
        val title = TextView(this).apply {
            text = "Pterodactyl"
            textSize = 25f
            setTextColor(0xFFD0D7DE.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF4F8CFF.toInt())
        }
        val message = TextView(this).apply {
            text = "Menghubungkan ke panel…"
            textSize = 14f
            setTextColor(0xFFCAD2DA.toInt())
            gravity = Gravity.CENTER
        }
        center.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })
        center.addView(spinner, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER; bottomMargin = dp(16) })
        center.addView(message, LinearLayout.LayoutParams(-1, -2))
        layer.addView(center, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        loadingOverlay = layer
    }

    private fun hideLoadingOverlay() {
        loadingOverlay?.let { root.removeView(it) }
        loadingOverlay = null
    }

    override fun onPause() {
        try { CookieManager.getInstance().flush() } catch (_: Exception) { }
        super.onPause()
    }

    override fun onStop() {
        try { CookieManager.getInstance().flush() } catch (_: Exception) { }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            val now = PanelDiscovery.networkSignature(this)
            if (now != signature) openBestRoute(false)
        }
    }

    override fun onBackPressed() {
        if (reportPage != null) {
            hideReport()
            openBestRoute(true)
        } else if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICKER_REQUEST) {
            val cb = pending
            pending = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        }
    }

    private fun showFailure(title: String, trigger: String) {
        runOnUiThread {
            lastError = trigger
            hideLoadingOverlay()
            progress.visibility = View.GONE
            web.visibility = View.GONE
            var page = reportPage
            if (page == null) {
                page = buildReport()
                reportPage = page
                root.addView(page, FrameLayout.LayoutParams(-1, -1))
            }
            page.findViewWithTag<TextView>("report_title")?.text = title
            reportText?.text = "Memeriksa DNS, HTTP, dan jaringan…"
            if (!diagnosing) {
                diagnosing = true
                Thread {
                    val details = PanelDiscovery.diagnostics(this, route.url, trigger)
                    runOnUiThread {
                        reportText?.text = "Rute: " + route.url + "\nMode: " + route.via + "\n\n" + details
                        diagnosing = false
                    }
                }.start()
            }
        }
    }

    private fun buildReport(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 17, 20)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(25), dp(22), dp(28))
        }
        scroll.addView(box, FrameLayout.LayoutParams(-1, -2))
        box.addView(label("Pterodactyl", 14, 0xFF9AA5B5.toInt(), true))
        val heading = label("Panel tidak dapat diakses", 25, Color.WHITE, true).apply { tag = "report_title" }
        box.addView(heading, spaceParams(8, 12))
        box.addView(label("Laporan ini menampilkan hasil pemeriksaan nyata dari perangkat. Penyebab internal yang tidak bisa diamati tidak akan ditebak.", 14, 0xFFB5BBC5.toInt(), false), spaceParams(0, 18))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFF1D1F25.toInt(), dp(16).toFloat())
        }
        box.addView(card, spaceParams(0, 18))
        card.addView(label("LAPORAN DIAGNOSTIK", 11, 0xFF8EAFFF.toInt(), true))
        reportText = label("Memeriksa…", 13, 0xFFE3E6EC.toInt(), false)
        reportText?.setLineSpacing(dp(4).toFloat(), 1f)
        card.addView(reportText)
        box.addView(button("Coba lagi", true) { openBestRoute(true) }, spaceParams(0, 9))
        box.addView(button("Pakai Cloudflare", false) {
            PanelDiscovery.setCloud(this)
            route = RouteDecision(PanelDiscovery.CLOUD, "Cloudflare dipilih manual")
            hideReport()
            web.loadUrl(route.url)
        }, spaceParams(0, 9))
        box.addView(button("Atur IP lokal", false) { manualAddress() }, spaceParams(0, 9))
        box.addView(button("Salin laporan", false) {
            val value = "Pterodactyl Android\n" + (reportText?.text ?: lastError)
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Diagnosis Pterodactyl", value))
            Toast.makeText(this, "Laporan disalin.", Toast.LENGTH_SHORT).show()
        }, spaceParams(0, 9))
        return scroll
    }

    private fun manualAddress() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://192.168.9.10:8080"
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Alamat panel lokal")
            .setMessage("Masukkan IP lokal panel beserta port bila diperlukan.")
            .setView(input).setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                var value = input.text.toString().trim().trimEnd('/')
                if (value.isNotBlank() && !value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value
                val host = try { Uri.parse(value).host } catch (_: Exception) { null }
                if (host.isNullOrBlank()) {
                    Toast.makeText(this, "URL tidak valid.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                PanelDiscovery.setManual(this, value)
                route = RouteDecision(value, "Alamat lokal manual")
                hideReport()
                web.loadUrl(value)
            }.show()
    }

    private fun hideReport() {
        reportPage?.let { root.removeView(it) }
        reportPage = null
        reportText = null
        diagnosing = false
        if (::web.isInitialized) web.visibility = View.VISIBLE
    }

    private fun button(caption: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = caption
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(if (primary) 0xFF4575DF.toInt() else 0xFF282B33.toInt(), dp(12).toFloat())
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun spaceParams(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        if (top > 0) topMargin = dp(top)
        if (bottom > 0) bottomMargin = dp(bottom)
    }

    private fun filename(disposition: String?, url: String): String {
        val fromHeader = disposition?.substringAfter("filename=", "")?.trim('"')?.takeIf { it.isNotBlank() }
        val fromUrl = try { Uri.parse(url).lastPathSegment } catch (_: Exception) { null }
        return (fromHeader ?: fromUrl ?: "pterodactyl-download")
            .substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
    }

    companion object {
        private const val FILE_PICKER_REQUEST = 3018
        private const val PANEL_BODY_COLOR = 0xFF33414F.toInt()
    }
}
