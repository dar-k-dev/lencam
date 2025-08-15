# AdvancedCamera

Cloud-built Android camera app scaffold focused on maximum image/video quality, with GitHub Actions producing APKs. No Android Studio required.

## What you get now (M0)
- Kotlin + Jetpack Compose app
- CameraX preview and basic still capture to Pictures/AdvancedCamera
- GitHub Actions CI builds a debug APK on every push

## Use without Android Studio
1. Initialize and push to GitHub:
   ```
   cd advanced-camera
   git init
   git add .
   git commit -m "Initial scaffold"
   git branch -M main
   git remote add origin https://github.com/youruser/advanced-camera.git
   git push -u origin main
   ```
2. Open the GitHub repo -> Actions -> latest run -> download the artifact `advanced-camera-debug-apk`.
3. Sideload on your Android device (enable "Install unknown apps").

## Roadmap (implemented scaffolding)
- Multi-frame HDR, Night, SR, Portrait: module APIs and stubs wired end-to-end
- HEIC/JPEG/RAW outputs, HEVC/HDR video: media-core scaffolding
- Pro controls, histograms, zebra, focus peaking: UI hooks prepared in app

## Notes
- Quality parity with iPhone depends on device sensor/ISP; our pipeline aims to maximize results on each device with computational photography.
- All on-device processing; no cloud.
