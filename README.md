# insiflix-apk

The Android app is implemented in Java 17. Gradle configuration uses Kotlin DSL
(`.gradle.kts`); the app does not apply the Kotlin Android plugin. The JavaScript
TV bridge and TypeScript Vue integration example remain in their original languages.

WebView app for phones, with native Media3 playback on Android-based TV devices including Fire TV. This does **not** enable video playback on projected Android Auto.
Google currently lists games, not video, as supported parked apps on regular Android Auto.

## Requirements
- Android Studio with JDK 17 and Android SDK 36
- An existing HTTPS-hosted Vue streaming application
- A phone with current Android System WebView

## Setup
1. Open this directory in Android Studio. Let it import/sync Gradle.
2. Set `STREAM_URL` in `app/build.gradle.kts` to your HTTPS Vue app URL.
3. Replace `com.example.privatestream` with a unique applicationId before your first Play upload.
4. Run the `app` configuration on the phone.
5. Test login, playback, fullscreen, pause on backgrounding, back navigation and video error handling.
6. For CLI builds: if `gradle` is installed, run `gradle wrapper --gradle-version 8.13`, then `./gradlew assembleDebug` (or use Android Studio's Build action). No wrapper JAR is bundled.

## Important limitations
- The page's main origin must be HTTPS. Cross-origin *navigation* is blocked by default; cross-origin API/media subresources may still load subject to normal WebView, cookies, CORS and site policies.
- Third-party SSO, downloads, file pickers, browser popups, DRM, picture-in-picture and native MediaSession are **not** implemented in this starter.
- A streaming app which uses a native-only codec, custom non-HTML player, DRM or unusual authentication may need a dedicated Media3 player instead.
- The `native-playback-pause` event is provided for your Vue player via `vue-example/useNativePlaybackPause.ts`.
- Authentication and stream entitlement enforcement must remain server-side. Protect URLs/tokens; don't embed secrets in the APK.
- This is source code, not a signed APK. Android Studio/Gradle dependencies are downloaded when syncing.

## Fire TV playback
TV devices automatically use Media3 for direct HTML video streams from the configured
website or `https://api.insiflix.com/videos/watch/`. The APK injects a TV-only script;
no website deployment or frontend changes are required. The native message bridge
is restricted to the configured HTTPS origin and top-level frame.

Start a normal video on the website to open the native player. Use the remote for
play/pause, seeking and subtitle selection. Back closes the player and restores
the video's position on the website, paused. Backgrounding pauses playback and
releases the decoder when the activity stops.

The existing watch-token URL is retained. Requests include the website's Origin,
Referer and user agent; browser cookies and bearer headers are not copied.
Embedded media subtitles and exposed HTTPS VTT/SRT tracks on the trusted hosts
are supported. YouTube embeds, DRM, blob/MSE sources and live lounge synchronization
are not handled by this native path. Position restoration applies when returning
to the same loaded page, not after Android kills the process.

Build with `./gradlew :app:assembleDebug`. Run the handoff tests with
`node --test scripts/tv-native-playback.test.cjs`.

## Private Play testing
1. Set a final unique applicationId, app name, versionCode, icon and a privacy policy suitable for your app.
2. Build > Generate Signed Bundle / APK > Android App Bundle.
3. In Google Play Console, create the app and upload the signed AAB to Testing > Internal testing.
4. Add your friends/family's Google accounts as testers and distribute the opt-in link.
5. Increase versionCode for every subsequent Play upload.

## Car integration
The `CAR_LAUNCHER` category is deliberately NOT declared. A video app cannot simply enable Android Auto projection by adding it. Check current Google developer requirements and supported vehicle eligibility when video rollout opens.

References:
- https://developer.android.com/training/cars/parked
- https://developer.android.com/training/cars/platforms/android-auto
- https://support.google.com/googleplay/android-developer/answer/9845334
