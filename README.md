# Sleep Monitor

Android app for personal sleep-audio experiments.

## Initial target
- Device: OnePlus 11 5G (16 GB RAM, 512 GB storage)
- Local-first storage; recordings remain on device by default
- Optional user-controlled backup to personal OneDrive
- Event-triggered audio clips only (no continuous overnight recording)

## Experimental scope
1. Sleep sounds: choose local audio or discover selectable online sources; timed playback and automatic stop.
2. Overnight audio monitoring: detect candidate snoring and speech events; retain short clips around detected events.
3. Morning review: timeline, event labels, playback of clips, and nightly summaries.

## Privacy and safety
- Microphone monitoring must be explicitly started by the user and clearly indicated while active.
- Audio processing should run on-device wherever practical.
- No audio upload by default. OneDrive backup is opt-in and must be clearly visible and controllable.
- Event labels are experimental indicators, not medical diagnoses. The app cannot diagnose sleep apnea or other sleep disorders.

## Development plan
Start with a working Android shell and reliable foreground audio session. Add event capture and review UI, then iterate on on-device event classification and OneDrive backup.

## Build
Open the project in Android Studio and allow Gradle sync.

GitHub Actions builds the debug APK. To keep the APK signing identity stable across updates, configure these repository Actions secrets:
- `SLEEP_MONITOR_KEYSTORE_BASE64`
- `SLEEP_MONITOR_KEYSTORE_PASSWORD`
- `SLEEP_MONITOR_KEY_PASSWORD`

The key alias is `sleepmonitor`. Never commit the keystore or its passwords to the repository.
