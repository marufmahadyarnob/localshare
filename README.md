# LocalShare ⚡

> **High-speed, offline local Wi-Fi file & text sharing for Android and any modern web browser.**

LocalShare turns your Android device into a high-performance local web server. Connected devices on the same Wi-Fi network (Android, iOS, Windows, macOS, Linux) can immediately upload and download files, stream videos, view photos, and share text without installing third-party apps or uploading data to the cloud.

---

## 🌟 Highlights

- **Zero Cloud & 100% Offline**: File transfers happen strictly over your local router/Wi-Fi network. No internet connection or cloud service is required.
- **Universal Browser Client**: Any device with a web browser can connect by opening the local IP URL or scanning the instant QR code.
- **Bi-directional Transfers**: Send files from the Android phone to the network, and upload files from desktop or phone browsers back to the Android phone.
- **High-Performance Streaming**: Large files are transferred using chunked streaming buffers (64KB chunks) with `Range` header support for seeking audio/video.
- **Shared Clipboard & Text**: Instant real-time text snippet, notes, and link exchange.
- **Foreground Service**: Transfers continue uninterrupted in the background while multitasking.
- **Built-in Security**: Strict path traversal validation, unique duplicate file collision handling, and sanitized filename parsing with Unicode support.
- **Automated CI/CD**: Automated GitHub Actions workflow builds signed APKs and publishes tagged GitHub Releases.

---

## 🛠️ Architecture

- **UI Framework**: Jetpack Compose with Material Design 3 (Dynamic Color, Dark/Light modes).
- **HTTP Engine**: Embedded `NanoHTTPD` web server running on port 8080 (or auto-assigned open port).
- **Local Persistence**: `Room Database` for indexing shared files and text messages.
- **Storage Manager**: Streams file payloads directly to disk without memory overhead; checks canonical paths to prevent path traversal attacks.
- **Network Discovery**: `ConnectivityManager` and `NetworkInterface` monitor active Wi-Fi IPv4 addresses in real time.
- **QR Code Engine**: Embedded `ZXing` QR generator for instant camera pairing.

---

## 📡 HTTP API Reference

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/` | Serves the responsive browser web interface (`index.html`) |
| `GET` | `/api/files` | Lists all shared files in JSON format |
| `POST` | `/api/upload` | Multipart file upload stream from browser to Android phone |
| `GET` | `/api/files/{id}/download` | Downloads a shared file with `Content-Disposition: attachment` and HTTP `Range` support |
| `GET` | `/api/files/{id}/preview` | Streams media files (images, audio, video) inline |
| `DELETE` | `/api/files/{id}` | Deletes a shared file from disk and database |
| `GET` | `/api/text` | Lists shared text snippets |
| `POST` | `/api/text` | Posts a new text snippet (`{"content": "..."}`) |
| `DELETE` | `/api/text/{id}` | Deletes a text snippet |

---

## 🚀 Automated GitHub Actions CI/CD Release System

This repository includes a production-ready automated CI/CD release pipeline under `.github/workflows/`.

### 1. Automated Release on Git Tag
Every time a version tag is pushed, GitHub Actions automatically builds the release APK and publishes a new GitHub Release:

```bash
# Create and push a semantic version tag
git tag v1.0.0
git push origin v1.0.0
```

### 2. Manual Release Dispatch
You can also trigger a release directly from the GitHub Actions tab:
1. Navigate to **Actions** -> **Automated Release & APK Builder**.
2. Click **Run workflow**.
3. Enter the release version (e.g. `1.1.0`).

### 3. Versioning Strategy
- **Semantic Versioning**: `MAJOR.MINOR.PATCH` (e.g., `v1.2.4` -> `versionName: "1.2.4"`).
- **Automated `versionCode`**: Calculated as `MAJOR * 10000 + MINOR * 100 + PATCH` (e.g., `1.2.4` -> `10204`), guaranteeing unique, monotonically increasing codes for Android app updates.

### 4. Release Artifacts
Every release automatically generates and attaches:
- `LocalShare-v{VERSION}.apk` — Signed Android APK ready to install.
- `LocalShare-v{VERSION}.apk.sha256` — Cryptographic checksum for file verification.

---

## 🔒 Security Best Practices

1. **Path Traversal Protection**: Files are constrained within the app's sandboxed `shared_files` directory using canonical path verification.
2. **Duplicate Filename Strategy**: Incoming files with identical names are renamed cleanly (`filename (1).ext`) to prevent overwrites.
3. **Stream Buffering**: All uploads and downloads use continuous buffered streams (64KB), eliminating Out-Of-Memory (`OOM`) crashes even on multi-gigabyte files.
