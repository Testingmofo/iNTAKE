# Launching iNTAKE on Windows

Double-click **Launch iNTAKE.cmd**. The launcher will:

1. Request Administrator permission only when dependencies need installation or updates.
2. Install Chocolatey if it is missing using Chocolatey's official installer.
3. Install or update Temurin Java 17, Maven, yt-dlp, FFmpeg, and Deno through Chocolatey.
4. Resolve JavaFX dependencies and build iNTAKE with Maven.
5. Create an `iNTAKE` shortcut on the current user's Desktop.
6. Build and register the Chrome/Edge native messaging host used for silent browser wake-up.
7. Start iNTAKE in a hidden Maven process so no terminal remains open.

The launcher checks required commands and Chocolatey updates whenever it runs. Build and runtime output is saved to `launcher-output.log` and `launcher-error.log` in the application directory.

Internet access is required for initial setup and dependency updates. Cancelling the Windows Administrator prompt cancels setup without making further changes.
