(() => {
  "use strict";

  const THUMBNAIL_BUTTON_CLASS = "intake-thumbnail-download";
  const OVERLAY_ROOT_ID = "intake-generic-overlay-root";
  const SPECIALIZED_HOSTS = new Set([
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "rule34.xxx",
    "www.rule34.xxx"
  ]);
  const currentHost = location.hostname.toLowerCase();
  const LAYOUT_SENSITIVE_IMAGE_HOST = /(^|\.)(facebook|messenger)\.com$/.test(currentHost)
    || /(^|\.)fb\.com$/.test(currentHost);
  const SCAN_DELAY_MS = LAYOUT_SENSITIVE_IMAGE_HOST ? 600 : 160;
  const thumbnailOverlays = new Map();
  const mediaOverlays = new Map();
  const resizeObserver = new ResizeObserver(() => schedulePositionUpdate());
  let scanScheduled = false;
  let positionScheduled = false;

  if (SPECIALIZED_HOSTS.has(currentHost) || currentHost.endsWith(".rule34.xxx")) {
    return;
  }

  document.querySelectorAll(".intake-generic-media-controls, .intake-thumbnail-download")
    .forEach((control) => control.remove());
  document.querySelectorAll("[data-intake-mounted]")
    .forEach((media) => delete media.dataset.intakeMounted);
  document.querySelectorAll("[data-intake-thumbnail-mounted]")
    .forEach((element) => delete element.dataset.intakeThumbnailMounted);

  function supportedUrl(value) {
    try {
      const url = new URL(value, location.href);
      return url.protocol === "http:" || url.protocol === "https:" ? url.href : null;
    } catch {
      return null;
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

  async function queue(button, url, mode, title) {
    if (button.dataset.intakeBusy === "1") return;
    button.dataset.intakeBusy = "1";
    button.classList.add("intake-processing");
    const response = await sendToIntake(url, mode);
    button.classList.remove("intake-processing");
    button.classList.add(response.ok ? "intake-success" : "intake-error");

    if (response.ok) {
      globalThis.iNTAKENotifications?.show({
        state: "active",
        title: `${title} // DOWNLOAD QUEUED`,
        message: response.result?.started
          ? "iNTAKE opened in the background and accepted the download."
          : "iNTAKE accepted the download request."
      });
      globalThis.iNTAKENotifications?.monitor(response.result?.jobId, title);
    } else {
      console.warn("[iNTAKE]", response.error);
      globalThis.iNTAKENotifications?.show({
        state: "error",
        title: `${title} // FAILED TO DOWNLOAD`,
        message: "Check the main GUI or error logs."
      }, 9000);
    }

    setTimeout(() => {
      if (!button.isConnected) return;
      button.classList.remove("intake-success", "intake-error");
      button.dataset.intakeBusy = "0";
    }, 1800);
  }

  function actionButton(label, mode, urlProvider, titleProvider) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "intake-glitch-button";
    button.textContent = label;
    button.dataset.label = label;
    button.title = `Download ${mode} with iNTAKE`;
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      const url = supportedUrl(urlProvider());
      if (url) queue(button, url, mode, titleProvider());
    }, true);
    return button;
  }

  function mediaTitle(media) {
    return (media.getAttribute("aria-label")
      || media.getAttribute("title")
      || globalThis.iNTAKENotifications?.currentPageTitle()
      || "MEDIA REQUEST").trim();
  }

  function overlayRoot() {
    let root = document.getElementById(OVERLAY_ROOT_ID);
    if (root) return root;
    root = document.createElement("div");
    root.id = OVERLAY_ROOT_ID;
    root.className = "intake-generic-overlay-root";
    (document.body || document.documentElement).appendChild(root);
    return root;
  }

  function mountMediaControls(media) {
    if (!(media instanceof HTMLMediaElement) || mediaOverlays.has(media)) return;
    const bounds = media.getBoundingClientRect();
    if (media instanceof HTMLVideoElement && (bounds.width < 240 || bounds.height < 120)) return;
    if (media instanceof HTMLAudioElement && bounds.width < 180) return;
    if (!media.parentElement) return;

    const controls = document.createElement("div");
    controls.className = "intake-watch-controls intake-generic-media-controls";
    const pageUrl = () => location.href;
    const title = () => mediaTitle(media);
    controls.append(actionButton("AUDIO", "audio", pageUrl, title));
    if (media instanceof HTMLVideoElement) {
      controls.append(actionButton("VIDEO", "video", pageUrl, title));
    }
    overlayRoot().appendChild(controls);
    mediaOverlays.set(media, controls);
    resizeObserver.observe(media);
  }

  function thumbnailTitle(image, anchor) {
    return (image.alt
      || image.title
      || anchor.getAttribute("aria-label")
      || anchor.title
      || "MEDIA THUMBNAIL").trim();
  }

  function mountThumbnailButton(image) {
    if (!(image instanceof HTMLImageElement) || !image.isConnected
        || thumbnailOverlays.has(image)) return;
    const bounds = image.getBoundingClientRect();
    const sourceWidth = image.naturalWidth || bounds.width;
    const sourceHeight = image.naturalHeight || bounds.height;
    if (bounds.width < 100 || bounds.height < 56 || sourceWidth < 140 || sourceHeight < 78) return;

    const anchor = image.closest("a[href]");
    if (!(anchor instanceof HTMLAnchorElement)) return;
    const mediaUrl = supportedUrl(anchor.href);
    if (!mediaUrl || mediaUrl === location.href || anchor.hasAttribute("download")) return;

    const button = document.createElement("button");
    button.type = "button";
    button.className = `intake-glitch-icon ${THUMBNAIL_BUTTON_CLASS}`;
    button.dataset.label = "⇩";
    button.setAttribute("aria-label", "Download this media with iNTAKE");
    button.title = "Download video with iNTAKE";
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      const currentAnchor = image.closest("a[href]");
      const currentUrl = currentAnchor instanceof HTMLAnchorElement
        ? supportedUrl(currentAnchor.href) : null;
      if (currentUrl) {
        queue(button, currentUrl, "video", thumbnailTitle(image, currentAnchor));
      }
    }, true);
    overlayRoot().appendChild(button);
    thumbnailOverlays.set(image, button);
    resizeObserver.observe(image);
  }

  function visibleBounds(element, minimumWidth, minimumHeight) {
    if (!element.isConnected) return null;
    const bounds = element.getBoundingClientRect();
    if (bounds.width < minimumWidth || bounds.height < minimumHeight
        || bounds.right <= 0 || bounds.bottom <= 0
        || bounds.left >= innerWidth || bounds.top >= innerHeight) {
      return null;
    }
    return bounds;
  }

  function placeOverlay(element, left, top) {
    element.style.setProperty("display", "flex", "important");
    element.style.setProperty("left", `${Math.round(left)}px`, "important");
    element.style.setProperty("top", `${Math.round(top)}px`, "important");
    element.style.setProperty("right", "auto", "important");
    element.style.setProperty("bottom", "auto", "important");
  }

  function hideOverlay(element) {
    element.style.setProperty("display", "none", "important");
  }

  function updateOverlayPositions() {
    positionScheduled = false;

    for (const [image, button] of thumbnailOverlays) {
      if (!image.isConnected || !button.isConnected) {
        resizeObserver.unobserve(image);
        button.remove();
        thumbnailOverlays.delete(image);
        continue;
      }
      const anchor = image.closest("a[href]");
      const bounds = visibleBounds(image, 100, 56);
      if (!(anchor instanceof HTMLAnchorElement) || !supportedUrl(anchor.href) || !bounds) {
        hideOverlay(button);
        continue;
      }
      const left = Math.max(4, Math.min(innerWidth - 34, bounds.right - 35));
      const top = Math.max(4, Math.min(innerHeight - 32, bounds.top + 5));
      placeOverlay(button, left, top);
    }

    for (const [media, controls] of mediaOverlays) {
      if (!media.isConnected || !controls.isConnected) {
        resizeObserver.unobserve(media);
        controls.remove();
        mediaOverlays.delete(media);
        continue;
      }
      const minimumWidth = media instanceof HTMLVideoElement ? 240 : 180;
      const minimumHeight = media instanceof HTMLVideoElement ? 120 : 24;
      const bounds = visibleBounds(media, minimumWidth, minimumHeight);
      if (!bounds) {
        hideOverlay(controls);
        continue;
      }
      const left = Math.max(4, Math.min(innerWidth - 180, bounds.left + 6));
      const top = Math.max(4, Math.min(innerHeight - 40, bounds.bottom - 40));
      placeOverlay(controls, left, top);
    }
  }

  function schedulePositionUpdate() {
    if (positionScheduled) return;
    positionScheduled = true;
    requestAnimationFrame(updateOverlayPositions);
  }

  function scan() {
    scanScheduled = false;
    document.querySelectorAll("video, audio").forEach(mountMediaControls);
    if (!LAYOUT_SENSITIVE_IMAGE_HOST) {
      document.querySelectorAll("a[href] img").forEach(mountThumbnailButton);
    }
    schedulePositionUpdate();
  }

  function scheduleScan() {
    if (scanScheduled) return;
    scanScheduled = true;
    setTimeout(() => requestAnimationFrame(scan), SCAN_DELAY_MS);
  }

  const mutationOptions = {
    childList: true,
    subtree: true
  };
  if (!LAYOUT_SENSITIVE_IMAGE_HOST) {
    mutationOptions.attributes = true;
    mutationOptions.attributeFilter = ["href", "src", "poster"];
  }
  new MutationObserver(scheduleScan).observe(document.documentElement, mutationOptions);
  document.querySelectorAll(".intake-thumbnail-host").forEach((host) => {
    host.classList.remove("intake-thumbnail-host");
  });
  window.addEventListener("scroll", schedulePositionUpdate, { passive: true, capture: true });
  window.addEventListener("resize", schedulePositionUpdate, { passive: true });
  window.addEventListener("popstate", scheduleScan);
  window.addEventListener("load", scheduleScan, { once: true });
  scheduleScan();
  setTimeout(scheduleScan, 1000);
  setInterval(scheduleScan, 5000);
})();
