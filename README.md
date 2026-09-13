# Simple Mod (Fabric, MC 1.20.1)

Mod client-side yang nampilin HUD custom di atas hotbar:
- Koordinat X/Y/Z (label putih, angka ijo) + ikon pin lokasi merah
- Day counter (cyan) + ikon jam biru

Posisinya dihitung relatif ke hotbar (bukan koordinat mati), jadi tetep pas biar di resolusi/GUI scale berapa pun. Ikonnya diambil dari resource pack Bedrock "Better Coord And Days" yang lu kasih, terus dipotong jadi texture PNG buat dipake di Java.

Semua ini murni visual overlay (HudRenderCallback) — gak ngubah gameplay apa pun, aman dipake di server manapun karena cuma jalan di client.

## Build tanpa PC (via GitHub Actions)

1. Bikin repo baru di GitHub (bisa lewat browser HP), upload semua isi folder ini ke situ.
2. GitHub bakal otomatis jalanin workflow di `.github/workflows/build.yml` begitu file ke-push.
3. Buka tab **Actions** di repo → klik run yang lagi jalan/udah selesai → scroll ke bagian **Artifacts** → download `simplemod-jar`.
4. Extract zip artifact itu, di dalemnya ada file `.jar` — itu yang ditaro di folder `mods` Minecraft (perlu Fabric Loader + Fabric API juga kepasang di game).

## Build di PC (kalau ada)

1. Extract folder ini, terus buka pakai IntelliJ IDEA (paling gampang) — pilih "Open" ke folder ini, IntelliJ bakal otomatis detect Gradle project.
2. Kalau belum ada Gradle Wrapper (`gradlew` / `gradlew.bat`), generate dulu:
   - Buka terminal di folder ini
   - Jalanin `gradle wrapper --gradle-version 8.8` (butuh Gradle terinstall sekali aja, abis itu wrapper-nya kepake terus)
3. Setelah wrapper ada, jalanin `./gradlew genSources` (opsional, biar bisa liat source Minecraft) lalu `./gradlew build`.
4. Buat testing langsung, jalanin `./gradlew runClient`.

## Struktur

- `src/main/java/...` — kode yang jalan di server & client
- `src/client/java/...` — kode yang cuma jalan di client (render, keybind, dll)
- `src/main/resources/fabric.mod.json` — metadata mod (nama, id, dependency)
- `gradle.properties` — versi Minecraft, Yarn mappings, Fabric Loader, Fabric API

## Ganti identitas mod

Ganti semua `simplemod` / `com.example.simplemod` sesuai nama mod lu, terus update `mod_id`, `name`, `description` di `fabric.mod.json`.

Cek versi Fabric terbaru di https://fabricmc.net/develop kalau mau update dependency.
