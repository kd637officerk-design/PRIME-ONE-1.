# EXO-1 — Android APK project

This project packages the supplied `PRIME_ONE_index.html` player in an Android WebView and adds a native Android media session/notification.

## Included
- Version 2.1: phone-file modification dates are sent to the player so Date modified sorting can show newest files first.
- Version 2.1: Android MediaStore album-art URI is supplied for scanned songs, and the player loads indexed album artwork when available.
- The wallpaper gallery continues to support importing multiple pictures and animated GIF files from the phone.
- The supplied EXO-1 player HTML (kept intact).
- He-Man artwork as the Android launcher icon and bundled cover image.
- Android media notification with **Previous**, **Play/Pause**, and **Next** controls.
- Media-session metadata/artwork and lock-screen media controls.
- GitHub Actions workflow that builds and uploads a debug APK.

## Build the APK on GitHub
1. Back up your existing repository first.
2. Extract this ZIP and replace the project files in your existing repository, keeping the folder structure. Upload the contents of `PRIME_ONE_Android` to the repository root.
3. Open **Actions** → **Build EXO-1 APK**.
4. Select **Run workflow** (or push a commit to `main`).
5. Open the completed workflow run and download the `EXO-1-debug-apk` artifact. It contains `app-debug.apk`.

## Build locally
- Install Android Studio with Android SDK 35 and JDK 17.
- Open this folder as an Android Studio project, sync Gradle, then choose **Build → Build APK(s)**.

## Important Android settings
- Allow notifications for EXO-1 when Android asks. If notification permission was denied, enable it in Android Settings → Apps → EXO-1 → Notifications.
- For uninterrupted playback on some Android brands, set Battery usage for EXO-1 to **Unrestricted**.
- Start playback inside the app once. Then swipe down from the top of the screen to see the media controls in the notification shade. They remain available while you use other apps.

## Notes
- This is an Android Studio source project, not a prebuilt APK. GitHub Actions creates the APK for you.
- The media notification is native Android UI. Its previous/play-pause/next actions are connected to the existing player's media-session JavaScript bridge.
- Local file picking remains available in the supplied HTML. The existing optional Capacitor-only MediaStore scanner/lock-screen overlay plugins are not part of this plain WebView wrapper.


## EXO-1 file access

EXO-1 requests Android media permissions for audio, photos, and videos. For browsing and managing shared internal storage, open the app's Settings menu and choose **Allow All files access**. Android opens its special-access settings page; enable access there and return to EXO-1.

The file browser supports listing folders and files, opening supported files with another app, renaming, copying, moving, and deleting individual files. Destructive actions require confirmation. It deliberately blocks folder deletion and paths outside shared internal storage. Android may still protect system folders and other apps' private data.

The existing media playback foreground service and notification/lock-screen controls are retained.
