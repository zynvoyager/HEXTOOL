# HEXTOOL fixes

Not compiled here (no Android SDK / network). Run `gradlew assembleDebug` to verify.

- build-error.txt / compile-error.txt: stale (ACTION_CONVERT_CLIPBOARD no longer exists in the source). No code change needed.
- WhatsAppHexService: group messages ("Sender: hex") were never decoded; now handled. Group-summary notifications ignored; duplicate re-posts ignored (5 s window). Targets can also match the sender name inside a group.
- MainActivity: requests POST_NOTIFICATIONS on Android 13+ (translation notifications were silently dropped); added a button that opens Accessibility settings (needed for screen translate).
- FloatingWidgetService: bubble menu now has "Tutup bubble" (stopSelf); before, the bubble could only be killed by force-stop.
- HexLogDatabase: synchronized writes (listener thread vs UI), and corrupt JSON no longer crashes.
- Manifest: allowBackup=false (decoded chat logs were included in backups); MainActivity exported=false.
- Accessibility service now has a description (res/values/strings.xml).

- MainActivity: bottom navigation (TAHAP_NAV_V1) installed: Home tab = old screen, Targets/Logs/Settings = placeholders.

- STATUS_FIX_V2: kartu Status Sistem tidak pernah berubah karena TextView subtitle tidak punya tag "subtitle". Sekarang diberi tag, nilai kartu real (listener, aksesibilitas, overlay, bubble aktif via FloatingWidgetService.running) dan diperbarui tiap 2 dtk.
- BUBBLE_FIX_V2: tahan bubble (long-press) tidak lagi membuka Pengaturan Aksesibilitas (itu yang membuat app "keluar sendiri"); menu Target/Log/Pengaturan sekarang membuka tab yang sesuai (EXTRA_TAB + onNewIntent).

- TARGETS_V2: tab Targets sekarang berfungsi penuh. Badge Aktif/Mati bisa diketuk (target Mati = pesan dari kontak/grup itu diabaikan dan kuncinya tidak dipakai); ketuk nama untuk mengubah nama/identifier; kartu Home menghitung target aktif. Perbaikan: ikon kunci AES sebelumnya tertimpa gaya badge.
- ENCODE_PLAIN_V1: panel bubble — tombol ENCODE / PASTE & ENCODE tidak lagi butuh mengetik prefix 3301; hasil HEX langsung disalin. ENKRIPSI (AES) tetap seperti sebelumnya.

- CROP_COMPAT_V1: fitur Crop sekarang jalan di Android 10 ke bawah lewat MediaProjection (ProjectionCapture.kt: dialog izin sistem + foreground service mediaProjection). Android 11+ tetap pakai takeScreenshot Aksesibilitas. Manifest: izin FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PROJECTION, service & activity baru.

- CROP_UI_V2: Crop punya kotak awal otomatis yang bisa digeser dan diperluas (8 pegangan), tombol SALIN (menutup crop lalu OCR + salin) dan BATAL, Back juga menutup. Bubble dijamin muncul kembali walau crop error. Kalau takeScreenshot gagal / hasilnya tak terbaca (mis. foto HDR), otomatis pindah ke MediaProjection.

- CROP_FIX_V3: di MIUI/Xiaomi (dan merek lain) dialog izin tangkap layar bisa diblokir diam-diam saat dibuka dari bubble, sehingga bubble tetap tersembunyi. Sekarang ada deteksi 2 detik + pesan cara mengaktifkan "Tampilkan jendela pop-up saat berjalan di latar belakang", dan pengaman 20 detik agar bubble selalu kembali.
