# AI_HANDOFF.md — dokumen serah-terima untuk AI/engineer yang melanjutkan VSpace

> BUKAN README pengguna. Dokumen ini untuk AI lain / sesi berikutnya agar bisa melanjutkan proyek tanpa kehilangan konteks.
> **ATURAN: setiap rilis baru WAJIB menambah entri di bagian "Changelog" paling bawah** (versi, apa yang berubah, kenapa, bukti log).

## 1. Tujuan proyek
Aplikasi Android "virtual space" (seperti Parallel Space / Dual Apps / VirtualApp): menjalankan APK lain (guest) di dalam
app host `com.example.vspace` dengan data terpisah, tanpa meng-install guest ke sistem. Fokus: game, multi-akun.
- Target Android 10 sampai 16 (minSdk 28, **targetSdk 28 sengaja**, lihat §4).
- Dibuat bertahap. Kode dikirim ke user sebagai zip; user push ke GitHub (repo `paukuman/vspace`) dan build via GitHub Actions.

## 2. Lingkungan & cara kerja dengan user
- Bahasa user: Indonesia. User tidak punya adb; debug lewat tombol **Lihat log / Salin log** di app.
- HP utama user: Xiaomi/MIUI, Android 10 (SDK 29). HP kedua: Android 16 (BELUM pernah dites).
- Sandbox AI TIDAK bisa compile (akses dl.google.com/Maven Google diblokir). Semua kode ditulis tanpa compile -> hati-hati:
  - JANGAN akses field/method hidden API langsung di Kotlin (compile error di CI). Pakai refleksi lewat `Refl`.
    Contoh yang pernah bikin build gagal: `ApplicationInfo.seInfo`.
- Alur rilis: ubah kode -> naikkan `versionCode`/`versionName` di `app/build.gradle.kts` -> zip ke `VSpace-tahapX.Y.zip`
  (folder root `VSpace/`) -> kirim -> user menjalankan one-liner:
  `unzip -qo VSpace-tahapX.Y.zip && cd VSpace && git add -A && git commit -qm "..." && git push && sleep 10 && gh run watch ... && rm -rf vspace-debug-apk && gh run download -n vspace-debug-apk && ls -l vspace-debug-apk`
  - `rm -rf vspace-debug-apk` WAJIB: `gh run download` tidak menimpa folder lama (pernah menyebabkan user menguji APK lama).
  - Judul app menampilkan `VSpace v<versionName>` dan log berisi `hook: v<versionName> ...` untuk memastikan versi yang diuji.
  - Kalau build gagal: `gh run view --log-failed | grep -E "^e: |error:|FAILED" | head -20`.
- Siklus debug: user "Hapus log" -> "Jalankan" -> "Salin log" -> tempel ke chat. AI membaca stack trace, memperbaiki satu masalah per rilis.

## 3. Arsitektur (file di `app/src/main/java/com/example/vspace/`)
| File | Peran |
|---|---|
| `VApp.kt` | Application host. Di SEMUA proses: pasang hidden-API bypass (LSPosed `HiddenApiBypass`). Di proses slot (`:p0/:p1/:p2`): pasang `MainLoopGuard`, uncaught-handler (tulis CRASH ke log), lalu `GuestHooks.install`. |
| `VirtualInstaller.kt` | Class `Guest` + object installer. Salin APK (+split) ke `filesDir/virtual/<pkg>/{base.apk,splits/,lib/,data/,abi.txt}`; ekstrak `.so` sesuai bitness proses; `Guest.patchApp()` membuat `ApplicationInfo` guest; `packageInfo/activityInfo/providers/componentInfo` dari parse APK; `launcher()` membaca AndroidManifest biner via AssetManager (hidden API) mencari MAIN/LAUNCHER. |
| `GuestHooks.kt` | Hook utama di proses slot: (1) proxy `IPackageManager` (`ActivityThread.sPackageManager`), (2) proxy `IActivityTaskManager` untuk `startActivity` dari guest -> ganti ke Stub, (3) callback `ActivityThread.mH` yang menambal `LaunchActivityItem` (intent+ActivityInfo), (4) `bootGuest()` meniru `handleBindApplication` untuk guest. |
| `ServiceHooks.kt` | Bungkus service sistem via `ServiceManager.sCache` + singleton `ActivityManager`: argumen String bernilai package guest diganti package host. Juga memblokir `bind*` ke Google Play Services. |
| `MainLoopGuard.kt` | Nested `Looper.loop()` di main thread proses guest; mengabaikan exception yang jejaknya dari Google Play Services. |
| `StubActivities.kt` | `Stub0/1/2` (proses `:p0/:p1/:p2`). Kalau stub benar-benar tampil = hook gagal (menampilkan log). |
| `MainActivity.kt` / `PickerActivity.kt` | UI host: pilih app terinstall (dengan pencarian) / file APK, daftar guest, Jalankan(slot n)/Tes load/Hapus, Lihat/Hapus/Salin log. |
| `VLog.kt` | Log ke `filesDir/vspace.log` (dipakai semua proses). `Refl.kt`: helper refleksi. |

### Alur peluncuran guest
1. Host (proses UI) `startActivity(Stub<slot>)` dengan extra `vs_guest_pkg`/`vs_guest_cls` (`Const`).
2. Sistem membuat proses `:p<slot>` -> `VApp.attachBaseContext` memasang hook.
3. Pesan `EXECUTE_TRANSACTION` (159) -> callback `mH` -> `patchLaunch`: `bootGuest` (sekali per proses: `getPackageInfoNoCheck` -> `makeApplication(false,null)` -> `installContentProviders` -> `callApplicationOnCreate`), lalu ganti `mInfo`/`mIntent` di `LaunchActivityItem`.
4. Framework meng-instantiate Activity guest memakai `ApplicationInfo` hasil `patchApp`.
5. `startActivity` dari dalam guest -> proxy ATM -> ganti ke `Stub<slot>` + extra (flag SINGLE_TOP/CLEAR_TOP/REORDER_TO_FRONT dibuang).

## 4. Trik penting & ALASANNYA (jangan dihapus tanpa paham)
- **targetSdk=28/minSdk=28**: hindari aturan DCL read-only (A14), exec W^X, scoped storage. minSdk tidak boleh > targetSdk (AGP error).
- **`ApplicationInfo.uid = Process.SYSTEM_UID`** (`Guest.patchApp`): `ContextImpl` memakai package PROSES (host) sebagai `opPackageName` bila `ai.uid==1000` dan proses bukan system. Menyelesaikan SecurityException "Package X does not belong to uid" untuk Settings/ContentResolver/AppOps. Konsekuensi: hanya aman di user 0 (cache LoadedApk pecah di user lain); `callingPackage` di Binder berisi package HOST (bukan guest!).
- **`splitNames` wajib non-null** bila `splitSourceDirs` diisi (Android 10 `LoadedApk.setupJitProfileSupport` NPE). Nama dari nama file: `split_config.arm64_v8a.apk` -> `config.arm64_v8a`.
- **`processName`, `seInfo`, `nativeLibraryRootDir` harus diisi**: dibaca dari APK nilainya null; MIUI `ForceDarkHelper.registAppDarkModeObserver` -> NPE di `MiuiBinderProxy`. `processName==pkg` juga mencegah framework memakai WarningContextClassLoader.
- **`bootGuest` harus sebelum Activity di-instantiate**: Application guest tidak pernah dibuat oleh `handleBindApplication` (proses di-bind ke app host) -> error "AppContext.set has not been invoked".
- **PackageManager proxy**: `getPackageInfo/getApplicationInfo` guest dijawab dari APK (`LoadedApk.initializeJavaContextClassLoader` butuh ini); `get{Activity,Service,Receiver,Provider}Info(ComponentName)` guest dijawab dari APK; `set/getComponentEnabledSetting` guest di-no-op.
- **Izin**: izin Android per-UID, jadi manifest host mendeklarasikan ~45 izin (INTERNET dll). Tanpa INTERNET: DNS EPERM. Izin berbahaya diminta runtime atas nama host. SMS/call log/CALL_PHONE sengaja tidak dideklarasikan.
- **Stub tunggal per slot**: semua Activity guest dipetakan ke satu kelas stub per slot -> semantik launchMode/back stack terbatas; `NewIntentItem` belum ditambal.
- **ABI**: ekstraksi `.so` memilih ABI sesuai `Process.is64Bit()`; game 32-bit-only di device 64-bit ditolak.

## 5. Keterbatasan / BELUM dikerjakan (prioritas kira-kira)
1. **Virtualisasi Service/Broadcast/PendingIntent**: `startService/bindService/sendBroadcast` ke komponen guest masih ke sistem (bisa menyentuh app asli yang terinstall atau gagal). Perlu stub Service + hook `IActivityManager` (start/bind/stop service, broadcast, getIntentSender).
2. **Google Play Services tidak didukung** (GMS memvalidasi package vs UID): bind ke GMS diblokir; fitur login Google/Play Games/Firebase/FCM tidak jalan.
3. **Tahap 4 – IO redirect native + folder storage** (permintaan user): arahkan `Android/data|obb|media/<pkg>` guest ke `/storage/emulated/0/<NamaApp>/data|obb|media` (nama folder dari label app, bisa diganti). Perlu modul NDK + inline hook libc (`open/openat/stat/access/mkdir/rename/unlink/opendir`), alias `/sdcard`, `/storage/self/primary`, `/mnt/sdcard`. `MANAGE_EXTERNAL_STORAGE` sudah dideklarasikan; belum ada tombol buka halaman izin.
4. Notifikasi guest tampil atas nama VSpace; ikon resource guest mungkin salah. PendingIntent dari notifikasi menunjuk komponen guest asli.
5. Android 11–16 BELUM dites. Asumsi struktur `ClientTransaction/LaunchActivityItem` (field `mIntent`,`mInfo`), `ServiceManager.sCache`, singleton `IActivityManagerSingleton`/`IActivityTaskManagerSingleton` mungkin berubah. Log `tx: transaksi tanpa patch, item=[...]` membantu mendiagnosis.
6. APK split/XAPK dari file picker belum didukung (hanya app terinstall yang bisa membawa split).
7. Crash native (`.so`/SIGSEGV) tidak masuk log VSpace (hanya logcat).
8. Pemisahan multi-akun: slot = proses; dua guest di slot yang sama tidak boleh berjalan bersamaan.

## 7. Cara membaca log
Format: `[proses] tag: pesan`. Tag penting: `hook` (versi+pemasangan), `boot` (Application/Provider guest), `patch` (Stub diganti; berisi uid/proc/seInfo),
`startActivity`, `svc` (penulisan ulang package per service.method, SEKALI per kombinasi), `tx` (transaksi tanpa patch, maks 8), `guard` (exception GMS diabaikan), `CRASH`.
Pola error -> perbaikan: "does not belong to"/"package name mismatch"/"not owned by" = service sistem baru yang perlu ditambah ke `ServiceHooks.SERVICES`.

## 7b. PRINSIP: generik dulu, bukan per-app (umpan balik user)
- Utamakan perbaikan tingkat framework (hook/emulasi perilaku sistem), bukan daftar manual yang tumbuh per crash.
- Jujur soal batas: tidak ada virtual app yang 100% kompatibel. Pasti tidak jalan: butuh Google Play Services (login Google/Play Games/FCM),
- Roadmap generik (urutan): (A) virtualisasi Service/Broadcast/PendingIntent (stub Service per slot + hook AM start/stop/bind/broadcast/getIntentSender);
  (B) auto-hook semua service sistem [SELESAI 0.2.14]; (C) stub per launchMode + patch `NewIntentItem` + back stack yang benar;
  (D) Tahap 4 IO redirect native + folder storage; (E) matriks uji ~10 app dari kategori berbeda (Java biasa, Unity, Flutter, WebView, sosial, perbankan[diharapkan gagal], app Google-sign-in);
  (F) dukungan Android 11-16 (uji di HP Android 16 user).
- Diagnostik harus tidak buta: jangan batasi log yang penting (pernah terpotong cap `tx` sehingga kegagalan tersembunyi).

## 8. Changelog (tambah entri baru DI BAWAH, jangan hapus yang lama)
- **0.1 (Tahap 1)** — Proyek dasar + GitHub Actions. Pilih APK via file, salin ke folder privat, parse, ekstrak .so, tes `DexClassLoader`.
- **0.2 (Tahap 2)** — Stub0/1/2 (proses :p0-:p2), `GuestHooks` (proxy IPackageManager, callback `mH` menambal `LaunchActivityItem`), hidden-API bypass, `launcher()` dari manifest biner, `VLog`. minSdk/targetSdk 28.
- **0.2.1** — `PickerActivity` (daftar app terinstall + cari), dukungan split APK, hook `startActivity` via ATM singleton, uncaught handler yang menulis CRASH ke log.
- **0.2.2** — Fix NPE `splitNames` (`LoadedApk.setupJitProfileSupport`, Android 10). Bukti: stack trace CODM.
- **0.2.3** — `bootGuest` (makeApplication + installContentProviders + onCreate) sebelum Activity dibuat. Bukti: WhatsApp "AppContext.set has not been invoked".
- **0.2.4** — Trik `uid=SYSTEM_UID` -> opPackageName host. Bukti: "Package com.whatsapp does not belong to 10419" (Settings provider).
- **0.2.5/0.2.6** — Diagnostik MIUI (dump `ForceDarkHelper`/`MiuiBinderProxy`), tombol Salin log. 0.2.5 gagal build karena akses `seInfo` langsung (hidden) -> 0.2.6 pakai refleksi.
- **0.2.7** — Isi `processName`/`seInfo`/`nativeLibraryRootDir`; dump MIUI dimatikan. Bukti: log `proc=null seInfo=null` + NPE MIUI.
- **0.2.8** — Versi tampil di judul app dan log `hook` (user sempat menguji APK lama).
- **0.2.9** — `ServiceHooks`: bungkus 12 service + `IActivityManager` (rewrite package guest->host). Bukti: NotificationManager "gave package com.whatsapp which is owned by uid ...".
- **0.2.10** — Deklarasi ~45 izin di manifest. Bukti: DNS `EPERM` "missing INTERNET permission".
- **0.2.11** — Tambah service: shortcut, launcherapps, telecom, isub, bluetooth_manager, clipboard, device_policy, role, carrier_config, power, user, storagestats, appwidget; log `svc` dedupe per tag. Bukti: ShortcutService "Calling package name mismatch".
- **0.2.12** — PM proxy: no-op `set/getComponentEnabledSetting` guest, jawab `get*Info` dari APK; buang flag SINGLE_TOP/CLEAR_TOP/REORDER_TO_FRONT pada intent stub; blokir bind ke GMS (BUG: syarat `fromGuest` tidak pernah terpenuhi). Bukti: WhatsApp `setComponentEnabledSetting` SecurityException; CODM `NewIntentItem`.
- **0.2.13** — Perbaikan blokir bind GMS (hapus syarat `fromGuest`, karena callingPackage=host akibat trik uid); tambah `MainLoopGuard` (abaikan exception berjejak GMS di main thread); tambah dokumen ini. Bukti: CODM crash `Unknown calling package name` tetap muncul di 0.2.12 tanpa log "diblokir".
- **0.2.14** — `ServiceHooks.hookAuto`: bungkus SEMUA service Java dari `ServiceManager.listServices()` (kelas dari `interfaceDescriptor`), kecuali SKIP (package/activity/activity_task/window/input/display/media.camera) — menggantikan kebutuhan menambah daftar manual. Diagnostik: log `boot: mulai <pkg>` sebelum bootGuest; log `Stub diluncurkan TANPA extra guest` (tidak ter-cap); `VApp.installExitLogger` (shutdown hook mencatat stack pemanggil `System.exit` di proses guest); `VApp.logExitReasons` (Android 11+: `getHistoricalProcessExitReasons`, tag `exit-info`). Bukti: 8 Ball Pool mati diam-diam setelah `hook terpasang` tanpa log lain; CODM tidak menunjukkan `patch` untuk `CODMainActivity` setelah `startActivity -> Stub0` karena log `tx` ter-cap di 8. Catatan 0.2.13 terverifikasi sebagian: bind GMS diblokir (log `svc: bind ke Google Play Services diblokir`), CODM melewati dialog izin dan memanggil `CODMainActivity`.
- **0.2.15** — `LogcatDump` + tombol "Ambil logcat" (membaca logcat UID sendiri via `logcat -d`, disaring ke baris F/E/signal, ditulis ke vspace.log tag `logcat`); `VirtualInstaller.guessAbi` (cadangan bila `abi.txt` kosong tetapi ada .so; log CODM menampilkan `abi=null` dengan `so=47`); cap log `tx` 8 -> 25. Bukti: 8BP & CODM mati tanpa `CRASH`. Catatan: virtualisasi Service/Broadcast/PendingIntent (roadmap A) TERTUNDA ke 0.2.16 agar penyebab kematian proses 8BP diketahui dulu.
