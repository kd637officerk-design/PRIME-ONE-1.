# PRIME ONE — Android APK project

This project packages the supplied `PRIME_ONE_index.html` player in an Android WebView and adds a native Android media session/notification.

## Included
- The supplied PRIME ONE player HTML (kept intact).
- He-Man artwork as the Android launcher icon and bundled cover image.
- Android media notification with **Previous**, **Play/Pause**, and **Next** controls.
- Media-session metadata/artwork and lock-screen media controls.
- GitHub Actions workflow that builds and uploads a debug APK.

## Build the APK on GitHub
1. Create a new GitHub repository.
2. Extract this ZIP and upload all files/folders to the repository root.
3. Open **Actions** → **Build PRIME ONE APK**.
4. Select **Run workflow** (or push a commit to `main`).
5. Open the completed workflow run and download the `PRIME-ONE-debug-apk` artifact. It contains `app-debug.apk`.

## Build locally
- Install Android Studio with Android SDK 35 and JDK 17.
- Open this folder as an Android Studio project, sync Gradle, then choose **Build → Build APK(s)**.

## Important Android settings
- Allow notifications for PRIME ONE when Android asks. If notification permission was denied, enable it in Android Settings → Apps → PRIME ONE → Notifications.
- For uninterrupted playback on some Android brands, set Battery usage for PRIME ONE to **Unrestricted**.
- Start playback inside the app once. Then swipe down from the top of the screen to see the media controls in the notification shade. They remain available while you use other apps.

## Notes
- This is an Android Studio source project, not a prebuilt APK. GitHub Actions creates the APK for you.
- The media notification is native Android UI. Its previous/play-pause/next actions are connected to the existing player's media-session JavaScript bridge.
- Local file picking remains available in the supplied HTML. The existing optional Capacitor-only MediaStore scanner/lock-screen overlay plugins are not part of this plain WebView wrapper.
