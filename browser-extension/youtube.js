(() => {
  "use strict";

  const WATCH_CONTROLS_ID = "intake-watch-controls";
  const THUMBNAIL_BUTTON_CLASS = "intake-thumbnail-download";
  const VIDEO_LINK_SELECTOR = [
    "a[href^='/watch?']",
    "a[href^='/shorts/']",
    "a[href^='https://www.youtube.com/watch?']",
    "a[href^='https://www.youtube.com/shorts/']",
    "a[href^='https://m.youtube.com/watch?']",
    "a[href^='https://m.youtube.com/shorts/']"
  ].join(",");
  const THUMBNAIL_MEDIA_SELECTOR = [
    "img",
    "picture",
    "yt-img-shadow",
    "yt-image-view-model",
    "yt-thumbnail-view-model",
    "ytd-thumbnail",
    ".yt-core-image",
    "[style*='background-image']"
  ].join(",");
  const KNOWN_THUMBNAIL_LINK_SELECTOR = [
    "a#thumbnail",
    "a.ytd-thumbnail",
    "a.yt-lockup-view-model__content-image",
    "a.yt-simple-endpoint.ytd-thumbnail",
    "a.media-item-thumbnail-container"
  ].join(",");
  let scanScheduled = false;

  function canonicalVideoUrl(rawUrl = location.href) {
    try {
      const url = new URL(rawUrl, location.href);
      const id = url.searchParams.get("v");
      const shortsMatch = url.pathname.match(/^\/shorts\/([^/?]+)/);
      if (!id && shortsMatch) return `https://www.youtube.com/shorts/${encodeURIComponent(shortsMatch[1])}`;
      if (!id) return url.href;
      return `https://www.youtube.com/watch?v=${encodeURIComponent(id)}`;
    } catch {
      return rawUrl;
    }
  }

  function sendToIntake(url, mode) {
    return new Promise((resolve) => {
      chrome.runtime.sendMessage({ type: "intake.enqueue", url, mode }, (response) => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        resolve(response || { ok: false, error: "No response from iNTAKE." });
      });
    });
  }

  async function activateButton(button, url, mode) {
    if (button.dataset.intakeBusy === "1") return;
    button.dataset.intakeBusy = "1";
    const original = button.dataset.label || button.textContent;
    button.classList.add("intake-processing");
    button.dataset.label = "LINKING";
    if (!button.classList.contains(THUMBNAIL_BUTTON_CLASS)) button.textContent = "LINKING";

    const response = await sendToIntake(url, mode);
    button.classList.remove("intake-processing");
    button.classList.add(response.ok ? "intake-success" : "intake-error");
    button.dataset.label = response.ok ? "QUEUED" : "OFFLINE";
    if (!button.classList.contains(THUMBNAIL_BUTTON_CLASS)) {
      button.textContent = response.ok ? "QUEUED" : "OFFLINE";
    }
    const noticeTitle = globalThis.iNTAKENotifications?.currentPageTitle() || "MEDIA REQUEST";
    if (response.ok) {
      globalThis.iNTAKENotifications?.show({
        state: "active",
        title: `${noticeTitle} // DOWNLOAD QUEUED`,
        message: response.result?.started
          ? "iNTAKE opened in the background and accepted the download."
          : "iNTAKE accepted the download request."
      });
      globalThis.iNTAKENotifications?.monitor(response.result?.jobId, noticeTitle);
    } else {
      console.warn("[iNTAKE]", response.error);
      globalThis.iNTAKENotifications?.show({
        state: "error",
        title: `${noticeTitle} // FAILED TO DOWNLOAD`,
        message: "Check the main GUI or error logs."
      }, 9000);
    }

    setTimeout(() => {
      if (!button.isConnected) return;
      button.classList.remove("intake-success", "intake-error");
      button.dataset.label = original;
      if (!button.classList.contains(THUMBNAIL_BUTTON_CLASS)) button.textContent = original;
      button.dataset.intakeBusy = "0";
    }, 1800);
  }

  function createActionButton(label, mode, urlProvider) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "intake-glitch-button";
    button.textContent = label;
    button.dataset.label = label;
    button.title = `Queue ${mode} in iNTAKE`;
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      activateButton(button, urlProvider(), mode);
    });
    return button;
  }

  function mountWatchControls() {
    if (!location.pathname.startsWith("/watch")) return;
    if (document.getElementById(WATCH_CONTROLS_ID)) return;

    const subscribe = document.querySelector(
      "ytd-watch-metadata #subscribe-button, #owner #subscribe-button, ytd-subscribe-button-renderer"
    );
    if (!subscribe?.parentElement) return;

    const controls = document.createElement("div");
    controls.id = WATCH_CONTROLS_ID;
    controls.className = "intake-watch-controls";
    controls.append(
      createActionButton("AUDIO", "audio", () => canonicalVideoUrl()),
      createActionButton("VIDEO", "video", () => canonicalVideoUrl())
    );
    subscribe.insertAdjacentElement("afterend", controls);
  }

  function mountThumbnailButton(anchor) {
    if (!(anchor instanceof HTMLAnchorElement)) return;
    if (anchor.querySelector(":scope > .intake-thumbnail-download")) return;

    const rawUrl = anchor.getAttribute("href") || anchor.href;
    if (!rawUrl || !(/\/watch(?:\?|$)/.test(rawUrl) || /\/shorts\//.test(rawUrl))) return;
    if (!isThumbnailLink(anchor)) return;
    anchor.classList.add("intake-thumbnail-host");

    const button = document.createElement("button");
    button.type = "button";
    button.className = `intake-glitch-icon ${THUMBNAIL_BUTTON_CLASS}`;
    button.dataset.label = "⇩";
    button.setAttribute("aria-label", "Queue video in iNTAKE");
    button.title = "Queue video in iNTAKE";
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      activateButton(button, canonicalVideoUrl(anchor.href), "video");
    }, true);
    anchor.appendChild(button);
  }

  function isThumbnailLink(anchor) {
    // YouTube uses #wc-endpoint for a whole clickable card/row. It can contain
    // thumbnail media, but mounting there produces a second icon beside text.
    if (anchor.id === "wc-endpoint") return false;

    if (anchor.matches(KNOWN_THUMBNAIL_LINK_SELECTOR)) return true;
    if (anchor.querySelector(THUMBNAIL_MEDIA_SELECTOR)) return true;

    const thumbnailContainer = anchor.closest([
      "ytd-thumbnail",
      "yt-thumbnail-view-model",
      "ytm-thumbnail-cover",
      ".video-thumbnail-container-compact",
      ".compact-media-item-image"
    ].join(","));
    if (thumbnailContainer?.querySelector(THUMBNAIL_MEDIA_SELECTOR)) return true;

    // Fallback used by renderer variants that expose the thumbnail as a large
    // visual endpoint without one of YouTube's stable thumbnail class names.
    const bounds = anchor.getBoundingClientRect();
    return bounds.width >= 80 && bounds.height >= 70;
  }

  function scan() {
    scanScheduled = false;
    mountWatchControls();
    document.querySelectorAll(VIDEO_LINK_SELECTOR).forEach(mountThumbnailButton);
  }

  function scheduleScan() {
    if (scanScheduled) return;
    scanScheduled = true;
    requestAnimationFrame(scan);
  }

  new MutationObserver(scheduleScan).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["href"]
  });
  window.addEventListener("yt-navigate-finish", () => {
    document.getElementById(WATCH_CONTROLS_ID)?.remove();
    scheduleScan();
  });
  window.addEventListener("yt-page-data-updated", scheduleScan);
  window.addEventListener("popstate", scheduleScan);
  scheduleScan();
  setTimeout(scheduleScan, 750);
  setTimeout(scheduleScan, 2000);
  // YouTube recycles DOM nodes while scrolling long feeds. The observer handles
  // normal additions; this low-frequency pass catches nodes whose geometry or
  // internal renderer state changes without a useful mutation event.
  setInterval(scheduleScan, 4000);
})();
