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

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var root: FrameLayout
    private var route = RouteDecision(PanelDiscovery.CLOUD, "Cloudflare")
    private var signature = ""
    private var pending: ValueCallback<Array<Uri>>? = null
    private var reportPage: ScrollView? = null
    private var reportText: TextView? = null
    private var diagnosing = false
    private var lastError = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 17, 20)
        window.navigationBarColor = Color.rgb(16, 17, 20)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16, 17, 20)) }
        web = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(79, 140, 255))
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
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
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(v: WebView, url: String?) {
                progress.visibility = View.GONE
                if (reportPage != null && !url.isNullOrBlank()) hideReport()
                // Poll only from inside this app; no website/server modification is required.
                if (!url.isNullOrBlank() && (url.contains("pemai.net", true) || url.contains("127.0.0.1") || url.contains("192.168.") || url.contains("10.") || url.contains("172."))) {
                    installLiveUpdateMonitor()
                }
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(v, request, error)
                if (request.isForMainFrame) {
                    showFailure("Panel tidak bisa dijangkau", "WebView " + error.errorCode + ": " + error.description)
                }
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                super.onReceivedHttpError(v, request, response)
                if (request.isForMainFrame && response.statusCode >= 500) {
                    showFailure("Panel membalas HTTP " + response.statusCode,
                        "HTTP " + response.statusCode + " " + (response.reasonPhrase ?: ""))
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
    private fun installLiveUpdateMonitor() {
        val script = """
            (function () {
              try {
                if (window.__pteroLiveUpdateTimer) clearInterval(window.__pteroLiveUpdateTimer);
                var observedUrl = location.href;
                var baseline = null;
                var running = false;
                var updateWaiting = false;

                function digest(value) {
                  var hash = 2166136261;
                  for (var i = 0; i < value.length; i++) {
                    hash ^= value.charCodeAt(i);
                    hash = Math.imul(hash, 16777619);
                  }
                  return (hash >>> 0).toString(16);
                }

                function normalize(html) {
                  var doc = new DOMParser().parseFromString(html, 'text/html');
                  doc.querySelectorAll('meta[name*="csrf" i], meta[name*="xsrf" i]').forEach(function (n) { n.remove(); });
                  doc.querySelectorAll('input[type="hidden"]').forEach(function (n) {
                    if (/csrf|_token|xsrf/i.test((n.name || '') + ' ' + (n.id || ''))) n.remove();
                  });
                  doc.querySelectorAll('[nonce]').forEach(function (n) { n.removeAttribute('nonce'); });
                  return doc.documentElement.outerHTML
                    .replace(/(csrf[-_]?token|_token|xsrf[-_]?token)(["']?\s*[:=]\s*["'])[^"']+/gi, '$1$2TOKEN')
                    .replace(/>\s+</g, '><')
                    .replace(/\s+/g, ' ');
                }

                async function assetState(doc) {
                  var nodes = Array.from(doc.querySelectorAll('script[src], link[rel~="stylesheet"][href]'));
                  var urls = Array.from(new Set(nodes.map(function (n) { return n.src || n.href; }))).filter(function (u) {
                    try { return new URL(u, location.href).origin === location.origin; } catch (_) { return false; }
                  });
                  var parts = await Promise.all(urls.map(async function (u) {
                    try {
                      var r = await fetch(u, {
                        method: 'HEAD',
                        cache: 'no-store',
                        credentials: 'include',
                        headers: { 'Cache-Control': 'no-cache', 'Pragma': 'no-cache' }
                      });
                      var value = [
                        u,
                        r.status,
                        r.headers.get('ETag') || '',
                        r.headers.get('Last-Modified') || '',
                        r.headers.get('Content-Length') || ''
                      ].join('|');
                      return value;
                    } catch (_) { return u + '|unavailable'; }
                  }));
                  return parts.sort().join('\n');
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
                    if (new URL(response.url).pathname !== new URL(requestUrl).pathname) return null;
                  } catch (_) { return null; }
                  var html = await response.text();
                  var doc = new DOMParser().parseFromString(html, 'text/html');
                  var stableHtml = normalize(html);
                  var assets = await assetState(doc);
                  return digest(stableHtml + '\n--assets--\n' + assets);
                }

                function isEditing() {
                  var active = document.activeElement;
                  if (!active) return false;
                  if (active.matches && active.matches('input:not([type="hidden"]), textarea, [contenteditable="true"], [role="textbox"]')) {
                    return true;
                  }
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
                    updateWaiting = false;
                    var oldBanner = document.getElementById('ptero-app-update-banner');
                    if (oldBanner) oldBanner.remove();
                    return;
                  }
                  running = true;
                  try {
                    var next = await getSignature();
                    if (next === null) return;
                    if (baseline === null) {
                      baseline = next;
                      return;
                    }
                    if (next !== baseline) {
                      baseline = next;
                      updateWaiting = true;
                      if (isEditing()) {
                        showUpdateBanner();
                      } else if (window.PterodactylNative && window.PterodactylNative.applyUpdate) {
                        window.PterodactylNative.applyUpdate();
                      } else {
                        location.reload();
                      }
                    } else if (updateWaiting && !isEditing()) {
                      if (window.PterodactylNative && window.PterodactylNative.applyUpdate) {
                        window.PterodactylNative.applyUpdate();
                      } else {
                        location.reload();
                      }
                    }
                  } catch (_) {
                    // A temporary network failure is not treated as a content update.
                  } finally {
                    running = false;
                  }
                }

                window.__pteroLiveUpdateTimer = setInterval(poll, 3000);
                setTimeout(poll, 1200);
              } catch (_) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun applyUpdate() {
            runOnUiThread {
                if (!::web.isInitialized) return@runOnUiThread
                val current = web.url ?: route.url
                // Clear only this WebView's HTTP resource cache; cookies and login storage remain.
                web.clearCache(false)
                web.loadUrl(current)
                Toast.makeText(this@MainActivity, "Pembaruan panel dimuat.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBestRoute(force: Boolean = false) {
        signature = PanelDiscovery.networkSignature(this)
        progress.visibility = View.VISIBLE
        hideReport()
        Thread {
            val decision = PanelDiscovery.choose(this)
            runOnUiThread {
                route = decision
                val current = web.url
                if (force || current.isNullOrBlank() || !current.startsWith(route.url) || reportPage != null) {
                    web.loadUrl(route.url)
                } else {
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            val now = PanelDiscovery.networkSignature(this)
            if (now != signature) openBestRoute(true)
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
    }
}
