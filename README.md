# Pterodactyl Android

Private-use Android APK for the panel at https://pemai.net. Built through GitHub Actions; no Termux build step and no server/panel files are modified.

- On Wi-Fi, the app searches the current private IPv4 subnet for a page identified as Pterodactyl on ports 8080, 80, or 8081. If none is identified, it falls back to https://pemai.net.
- On cellular or other non-Wi-Fi networks, it uses https://pemai.net.
- Native diagnostics report observable network, DNS, HTTP, TLS, and WebView failures. An inaccessible client cannot reliably prove whether an internal server watchdog or Android process is stopped without an exposed status endpoint.
- The installable APK contains a native Android Activity, file picker, download manager, and diagnostic screen. The panel content itself is displayed in WebView to preserve the existing responsive Pterodactyl mobile UI and its session/features; this is not a browser shortcut or PWA.
- TLS errors are never bypassed. APK artifact name: `Pterodactyl-Android-Debug`.
