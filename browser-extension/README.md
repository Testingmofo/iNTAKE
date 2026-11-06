# iNTAKE Browser Module

Manifest V3 extension for Chromium-based browsers. It adds:

- `AUDIO` and `VIDEO` controls beside YouTube's Subscribe control.
- A compact glitch download icon on YouTube video thumbnails.
- Detail-page Audio/Video controls and thumbnail actions on Rule34.
- Layout-neutral Audio/Video overlay controls on standard HTML media players on other sites.
- A compact download action on large linked thumbnails across other HTTP/HTTPS sites.
- Stable Rule34 filenames using `Rule34_<post-id>.<extension>`.
- A cyberpunk top-right notification with startup, progress, completion, and failure states.
- Native messaging wake-up when the desktop application is offline.

Every action is sent to the local iNTAKE queue at `127.0.0.1:17845`. If that bridge is offline, the registered native host starts iNTAKE silently, waits for it to become ready, and then forwards the original request. The desktop application chooses the configured favorite format and separate Audio/Video output directory.

## Install in Chrome or Edge

1. Double-click `Launch iNTAKE.cmd` once. This builds and registers the browser wake-up host.
2. Open `chrome://extensions` or `edge://extensions`.
3. Enable **Developer mode**.
4. Select **Load unpacked**.
5. Select this `browser-extension` directory. If it was already loaded, select **Reload**.

The extension has a fixed development ID so the native-host permission remains stable. Moving the project folder requires running `Launch iNTAKE.cmd` again to update the registered host path.

## Generic site detection

yt-dlp extractors cover many unrelated website designs, so there is no universal thumbnail element. Outside the specialized YouTube and Rule34 integrations, iNTAKE detects standard `<video>`/`<audio>` players and sufficiently large images inside links. Generic controls render in an isolated viewport overlay and never change the host site's media wrappers, dimensions, positioning, or child layout. The linked page URL is sent to yt-dlp, which performs the final support check. Custom canvas players, closed shadow DOM, and unusual thumbnail markup may not expose an injectable element.

Facebook and Messenger use continuously recycled, lazy-loaded image containers. To avoid interfering with those render pipelines, iNTAKE does not scan their ordinary `<img>` thumbnails. Controls remain available on actual HTML video and audio players, and supported post/page URLs can still be pasted into the desktop application.

YouTube is a single-page application; the content script watches navigation and dynamically inserted recommendations, so buttons remain available without reloading the tab.
