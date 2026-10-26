# VSpace (Tahap 1)

Yang sudah ada:
- Pilih APK -> disalin ke folder privat app (files/virtual/<package>/base.apk)
- Parse package name, label, daftar Activity
- Ekstrak native .so sesuai ABI device
- Tes memuat kelas Activity guest lewat DexClassLoader

Yang BELUM ada (tahap berikutnya):
- Stub Activity + hook startActivity/ActivityThread (Tahap 2)
- Virtual PackageManager/ActivityManager (Tahap 3)
- IO redirect native (Tahap 4)
- Multi-proses & isolasi data (Tahap 5)

Cara build: push ke GitHub -> tab Actions -> "Build APK" -> download artifact.
