iNTAKE 0.2.2 Beta
==================

Recommended installation
------------------------
Run iNTAKE-Setup-0.2.2-beta.exe. The installer contains a private Java runtime,
JavaFX, yt-dlp, FFmpeg, Deno, and the browser wake helper. Maven, Chocolatey,
and a system Java installation are not required.

After installation:
1. Start iNTAKE once from the Start Menu or Desktop shortcut.
2. Open Chrome/Edge extensions, enable Developer mode, select Load unpacked,
   and choose %LOCALAPPDATA%\iNTAKE\browser-extension. The application keeps
   that managed folder current after future updates.
3. The first application start registers the per-user browser wake helper.

Uninstalling
------------
Open Start Menu > iNTAKE > Uninstall iNTAKE, or use Windows Settings > Apps >
Installed apps. The removal wizard leaves downloaded media and the user data in
%LOCALAPPDATA%\iNTAKE untouched.

Portable edition
----------------
Extract iNTAKE-Portable-0.2.2-beta.zip to a writable folder and run iNTAKE.exe.
Do not run it directly from inside the ZIP. Start it once before using the
browser extension so the native wake path can be registered.

User data
---------
Configuration, history, and errors are stored in:
  %LOCALAPPDATA%\iNTAKE

Downloads default to:
  %USERPROFILE%\Downloads\iNTAKE

Verification
------------
SHA256SUMS.txt contains SHA-256 hashes for every distributed archive/installer.

Automatic updates
-----------------
Normal application launches check published GitHub beta releases. An accepted
update is downloaded, verified against SHA256SUMS.txt, installed into the same
directory, and then relaunched. A failed download or hash check does not alter
the current installation.

This is an unsigned beta build. Windows SmartScreen may show an unrecognized
publisher warning until the installer is code-signed for a public release.
