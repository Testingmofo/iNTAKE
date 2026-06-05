# iNTAKE

> A lightweight, dark-mode GUI wrapper for **yt-dlp** built for speed, simplicity, and a classic download-manager experience.

iNTAKE provides a clean and straightforward interface for downloading media without unnecessary clutter. Paste a link, choose a format, queue your downloads, and let the app do the work.

---

## ✨ Features

- 🎵 MP3 audio downloads
- 🎬 MP4 video downloads
- 📋 Queue-based downloading
- 🌙 Dark-mode interface
- ⚡ Lightweight PowerShell implementation
- 🔄 Automatic yt-dlp engine updates on launch

---

## 📦 Requirements

Before running iNTAKE, install the following dependencies.

### 1. FFmpeg

Required for audio extraction, video/audio merging, and format conversion.

```powershell
winget install ffmpeg
```

### 2. yt-dlp

The download engine used by iNTAKE.

```powershell
winget install yt-dlp
```

> **Important**
>
> After installing FFmpeg and yt-dlp, sign out of Windows and sign back in (or restart your PC). This ensures their paths are properly registered in your system environment variables so iNTAKE can detect and use them.

---

## 🚀 Installation

### Option A — Compiled Version

Run:

```text
iNTAKE.exe
```

### Option B — PowerShell Script

Right-click:

```text
iNTAKE.ps1
```

and select **Run with PowerShell**.

---

## 📥 Usage

1. Launch iNTAKE.
2. Allow the application to check for engine updates.
3. Paste a YouTube URL.
4. Select your preferred output format.
5. Click **Start Queue**.
6. Enjoy your downloads.

---

# 🌐 iNTAKE Browser Extension

Quick Queueing from YouTube

Adds download buttons directly on YouTube pages.

- Installation

1. Open your browser's Extensions page.

2. Enable Developer Mode.

3. Click Load Unpacked.

4. Select the provided extension folder.

5. Open YouTube and enjoy.


- What it does
  
1. Adds download buttons near the Subscribe button.
2. Sends videos directly to your iNTAKE workflow.
3. Provides faster access without manually copying links.


---
## 📁 Download Location

Currently, all downloads are saved to your default **Downloads** folder.

Future updates will include customizable output directories and additional quality-of-life features.

---

## 🛠 Built With

- PowerShell
- yt-dlp
- FFmpeg

---

## 💬 Note from the Developer

> For now, downloads go straight to the default Downloads folder.
>
> I'll keep vibecoding and adding features as time goes on.

---

Built with ❤️ for people who prefer simple tools.
