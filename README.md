# VSpace (Tahap 2)

Tahap 1: salin APK ke folder privat, parse, ekstrak .so, tes load kelas.
Tahap 2 (baru, BELUM TERUJI): menjalankan Activity guest lewat stub.
  - Stub0/1/2 = 3 slot proses (:p0 :p1 :p2)
  - GuestHooks: hook IPackageManager + ActivityThread.mH (LaunchActivityItem)
  - Hidden API bypass (LSPosed hiddenapibypass)
  - Cari Activity launcher dengan membaca AndroidManifest biner
  - Log ke file (tombol "Lihat log" di UI)

Batasan yang diketahui:
  - startActivity DARI DALAM guest belum di-hook (Tahap 3) -> pindah layar di guest bisa crash
  - ContentProvider guest tidak di-install (Firebase/androidx startup bisa bermasalah)
  - Binder call dengan package name guest bisa SecurityException (izin, notifikasi, dll)
  - IO native belum diredirect (Tahap 4); data Java API sudah ke folder guest
  - APK split/XAPK belum didukung; game 32-bit-only di device 64-bit belum didukung
  - targetSdk sengaja 28
