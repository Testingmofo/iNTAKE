# iNTAKE

iNTAKE is a Windows media-download manager built with JavaFX and yt-dlp. It provides a Cyberpunk-inspired desktop interface, background downloads through the system tray, download history, configurable audio and video formats, playlist controls, and a companion browser extension.

This repository contains the current JavaFX edition. The original PowerShell edition remains available from the repository's earlier history and is referred to as **iNTAKE-Powershell**.

## Features

- Queue-based, asynchronous yt-dlp downloads
- Audio and video format selection
- Separate audio and video output locations
- MVSS local music-vibe analysis and automatic audio-library sorting
- Persistent Main/Accent/Highlight color customization with cyberpunk presets
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

## MVSS automatic audio sorting

Enable **Auto Sort via MVSS** in the main control bar to analyze completed audio downloads locally. MVSS decodes a short sample through FFmpeg, measures tempo, energy, brightness, zero-crossing activity, and dynamics, then moves the file into:

```text
<Audio download location>\MVSS\<Vibe>\
```

Vibe folders include `Energetic`, `Chill`, `Dark`, `Bright`, `Melancholic`, and `Atmospheric`. Existing files are never overwritten; MVSS adds a numeric suffix when necessary.

The acoustic classifier works fully offline. If Ollama is already running with a small local model (up to roughly 3B parameters), MVSS uses it as an optional constrained refinement step. Ollama is not required.

## Cyberpunk theme customization

Open the configuration matrix from the top-right settings control to customize the three interface roles:

- **Main** — primary buttons, major borders, and success states
- **Accent** — telemetry, secondary borders, icons, and online states
- **Highlight** — warnings, errors, paused states, and alert hovers

Built-in presets include Neon District, Netrunner Ice, Arasaka Alert, Toxic Chrome, and Synthwave Grid. Custom colors apply immediately after saving and persist in `intake.properties`. **Reset Neon District** restores the original yellow, cyan, and magenta palette.

## Project layout

- `src/main/java` — JavaFX application source
- `browser-extension` — Chrome/Edge extension
- `native-host` — native-messaging helper source
- `scripts` — launcher and release-build scripts
- `release` — release documentation templates

## Legal

iNTAKE is provided under the license in [LICENSE](LICENSE). Downloads must comply with the applicable website terms, copyright law, and local law. Third-party components retain their respective licenses; see `release/THIRD-PARTY-NOTICES.txt`.
