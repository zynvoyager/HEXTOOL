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
