# Outlier Radar 📡 — YouTube Shorts Outlier Finder

Outlier Radar helps creators, researchers, and video producers discover hidden viral Shorts from small YouTube channels whose outlier videos exploded (e.g. 100x–1000x their channel median views).

---

## 🚀 How to Build on GitHub (GitHub Actions)

This repository includes a ready-to-run **GitHub Actions Workflow** (`.github/workflows/android-build.yml`) that automatically compiles the app and generates the installable Android APK (`.apk`).

### Option 1: Automatic Build on Push
1. Push your code or commits to `main` or `master`.
2. Go to the **Actions** tab on your GitHub repository.
3. You will see the **Android Build & APK Generator** workflow running.
4. Once completed (green checkmark), click on the workflow run.
5. Under **Artifacts** at the bottom of the page, click **`Outlier-Radar-Debug-APK`** to download your ready-to-install APK.

### Option 2: Manual Trigger ("Run workflow")
1. Open your GitHub repository in your browser.
2. Click on the **Actions** tab.
3. In the left sidebar, click **Android Build & APK Generator**.
4. Click the **Run workflow** dropdown button on the right, select `main` or `master`, and click **Run workflow**.
5. Wait ~2-3 minutes for the build to finish.
6. Click into the run, scroll to **Artifacts**, and download **`Outlier-Radar-Debug-APK`**.

---

## 💻 Local Build (Android Studio or Command Line)

### Command Line:
```bash
# Build Debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk

# Run unit tests:
./gradlew testDebugUnitTest
```

### Android Studio:
1. Open Android Studio.
2. Choose **Open** and select this project directory.
3. Allow Gradle to sync.
4. Select **Run** > **Run 'app'** or connect an Android device / emulator.

---

## 🔑 Setup Inside the App

1. On first launch, the app prompts you for your **YouTube Data API v3** key.
2. Your key is encrypted locally using the Android Keystore (AES-GCM) and never shared.
3. You can test your key directly with the built-in 1-unit verification button before searching.
