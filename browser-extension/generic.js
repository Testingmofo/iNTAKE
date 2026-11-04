(() => {
  "use strict";

  const THUMBNAIL_BUTTON_CLASS = "intake-thumbnail-download";
  const SPECIALIZED_HOSTS = new Set([
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "rule34.xxx",
    "www.rule34.xxx"
  ]);
  let scanScheduled = false;

  if (SPECIALIZED_HOSTS.has(location.hostname.toLowerCase())
      || location.hostname.toLowerCase().endsWith(".rule34.xxx")) {
    return;
  }

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

  function mountMediaControls(media) {
    if (!(media instanceof HTMLMediaElement) || media.dataset.intakeMounted === "1") return;
    const bounds = media.getBoundingClientRect();
    if (media instanceof HTMLVideoElement && (bounds.width < 240 || bounds.height < 120)) return;
    if (media instanceof HTMLAudioElement && bounds.width < 180) return;
    if (!media.parentElement) return;

    media.dataset.intakeMounted = "1";
    const controls = document.createElement("div");
    controls.className = "intake-watch-controls intake-generic-media-controls";
    const pageUrl = () => location.href;
    const title = () => mediaTitle(media);
    controls.append(actionButton("AUDIO", "audio", pageUrl, title));
    if (media instanceof HTMLVideoElement) {
      controls.append(actionButton("VIDEO", "video", pageUrl, title));
    }
    media.insertAdjacentElement("afterend", controls);
  }

  function thumbnailTitle(image, anchor) {
    return (image.alt
      || image.title
      || anchor.getAttribute("aria-label")
      || anchor.title
      || "MEDIA THUMBNAIL").trim();
  }

  function thumbnailHost(image, anchor) {
    const imageBounds = image.getBoundingClientRect();
    let candidate = image.parentElement;
    while (candidate && candidate !== anchor.parentElement) {
      const bounds = candidate.getBoundingClientRect();
      if (bounds.width <= imageBounds.width * 1.3
          && bounds.height <= imageBounds.height * 1.3
          && bounds.width >= imageBounds.width * .8
          && bounds.height >= imageBounds.height * .8) {
        return candidate;
      }
      if (candidate === anchor) break;
      candidate = candidate.parentElement;
    }
    return anchor;
  }

  function mountThumbnailButton(image) {
    if (!(image instanceof HTMLImageElement) || !image.isConnected) return;
    const bounds = image.getBoundingClientRect();
    const sourceWidth = image.naturalWidth || bounds.width;
    const sourceHeight = image.naturalHeight || bounds.height;
    if (bounds.width < 100 || bounds.height < 56 || sourceWidth < 140 || sourceHeight < 78) return;

    const anchor = image.closest("a[href]");
    if (!(anchor instanceof HTMLAnchorElement)) return;
    const mediaUrl = supportedUrl(anchor.href);
    if (!mediaUrl || mediaUrl === location.href || anchor.hasAttribute("download")) return;

    const host = thumbnailHost(image, anchor);
    if (!(host instanceof HTMLElement) || host.dataset.intakeThumbnailMounted === "1") return;
    host.dataset.intakeThumbnailMounted = "1";
    host.classList.add("intake-thumbnail-host");

    const button = document.createElement("button");
    button.type = "button";
    button.className = `intake-glitch-icon ${THUMBNAIL_BUTTON_CLASS}`;
    button.dataset.label = "⇩";
    button.setAttribute("aria-label", "Download this media with iNTAKE");
    button.title = "Download video with iNTAKE";
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      queue(button, mediaUrl, "video", thumbnailTitle(image, anchor));
    }, true);
    host.appendChild(button);
  }

  function scan() {
    scanScheduled = false;
    document.querySelectorAll("video, audio").forEach(mountMediaControls);
    document.querySelectorAll("a[href] img").forEach(mountThumbnailButton);
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
    attributeFilter: ["href", "src", "poster"]
  });
  window.addEventListener("popstate", scheduleScan);
  window.addEventListener("load", scheduleScan, { once: true });
  scheduleScan();
  setTimeout(scheduleScan, 1000);
  setInterval(scheduleScan, 5000);
})();
