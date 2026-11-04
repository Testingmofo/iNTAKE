# iNTAKE

iNTAKE is a Windows media-download manager built with JavaFX and yt-dlp. It provides a Cyberpunk-inspired desktop interface, background downloads through the system tray, download history, configurable audio and video formats, playlist controls, and a companion browser extension.

This repository contains the current JavaFX edition. The original PowerShell edition remains available from the repository's earlier history and is referred to as **iNTAKE-Powershell**.

## Features

- Queue-based, asynchronous yt-dlp downloads
- Audio and video format selection
- Separate audio and video output locations
- Playlist ignore and selection controls
- Download progress, history, logs, and error reports
- System-tray operation and silent browser-extension startup
- Chrome and Edge browser extension with native messaging
- Self-contained Windows installer and portable package generation

## Running from source

Development requires Windows, JDK 17 or newer, Maven, yt-dlp, FFmpeg/FFprobe, and Deno available on `PATH`.

```powershell
mvn clean javafx:run
```

Alternatively, run `Launch iNTAKE.cmd`. The launcher checks the required tools and can use Chocolatey to install or update missing dependencies.

See [LAUNCHING.md](LAUNCHING.md) for launcher details.

## Building a Windows release

The release builder requires Chocolatey, WiX Toolset 3.x, JDK tooling, Maven, yt-dlp, FFmpeg, and Deno.

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts\Build-Release.ps1
```

Generated installers and archives are placed in `For-Release`. That directory is intentionally excluded from Git because release binaries should be uploaded as GitHub Release assets.

## Browser extension

The unpacked extension is located in `browser-extension`. Release builds also produce a ZIP archive that can be attached to a GitHub Release.

The extension communicates with the local iNTAKE application. If iNTAKE is offline, its native-messaging helper starts the application silently before forwarding the download request.

## Project layout

- `src/main/java` — JavaFX application source
- `browser-extension` — Chrome/Edge extension
- `native-host` — native-messaging helper source
- `scripts` — launcher and release-build scripts
- `release` — release documentation templates

## Legal

iNTAKE is provided under the license in [LICENSE](LICENSE). Downloads must comply with the applicable website terms, copyright law, and local law. Third-party components retain their respective licenses; see `release/THIRD-PARTY-NOTICES.txt`.
