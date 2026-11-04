(() => {
  "use strict";

  function rule34Name(urlValue) {
    try {
      const id = new URL(urlValue, location.href).searchParams.get("id");
      return /^\d+$/.test(id || "") ? `Rule34_${id}` : null;
    } catch {
      return null;
    }
  }

  function sendToIntake(url, mode) {
    return new Promise((resolve) => {
      chrome.runtime.sendMessage({
        type: "intake.enqueue",
        url,
        mode,
        name: rule34Name(url)
      }, (response) => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        resolve(response || { ok: false, error: "No response from iNTAKE." });
      });
    });
  }

  async function queue(button, url, mode) {
    if (button.dataset.intakeBusy === "1") return;
    button.dataset.intakeBusy = "1";
    button.classList.add("intake-processing");
    const result = await sendToIntake(url, mode);
    button.classList.remove("intake-processing");
    button.classList.add(result.ok ? "intake-success" : "intake-error");
    const noticeTitle = rule34Name(url) || "RULE34 MEDIA";
    if (result.ok) {
      globalThis.iNTAKENotifications?.show({
        state: "active",
        title: `${noticeTitle} // DOWNLOAD QUEUED`,
        message: result.result?.started
          ? "iNTAKE opened in the background and accepted the download."
          : "iNTAKE accepted the download request."
      });
      globalThis.iNTAKENotifications?.monitor(result.result?.jobId, noticeTitle);
    } else {
      console.warn("[iNTAKE]", result.error);
      globalThis.iNTAKENotifications?.show({
        state: "error",
        title: `${noticeTitle} // FAILED TO DOWNLOAD`,
        message: "Check the main GUI or error logs."
      }, 9000);
    }
    setTimeout(() => {
      if (!button.isConnected) return;
      button.classList.remove("intake-success", "intake-error");
      button.dataset.intakeBusy = "0";
    }, 1800);
  }

  function postUrlFrom(element) {
    const link = element.closest("a[href]") || element.querySelector("a[href]");
    if (!link) return null;
    try {
      const url = new URL(link.href, location.href);
      return url.searchParams.get("s") === "view" ? url.href : null;
    } catch {
      return null;
    }
  }

  function mountListingButtons() {
    document.querySelectorAll(".preview, [id^='p']").forEach((entry) => {
      if (!(entry instanceof HTMLElement) || entry.dataset.intakeMounted === "1") return;
      const url = postUrlFrom(entry);
      if (!url) return;
      entry.dataset.intakeMounted = "1";
      entry.classList.add("intake-thumbnail-host");
      const button = document.createElement("button");
      button.type = "button";
      button.className = "intake-glitch-icon intake-thumbnail-download";
      button.dataset.label = "⇩";
      button.title = "Queue media in iNTAKE";
      button.addEventListener("click", (event) => {
        event.preventDefault();
        event.stopImmediatePropagation();
        queue(button, url, "video");
      }, true);
      entry.appendChild(button);
    });
  }

  function mountDetailControls() {
    const params = new URLSearchParams(location.search);
    if (params.get("s") !== "view" || document.getElementById("intake-r34-controls")) return;
    const media = document.querySelector("#fluid_video_wrapper_gelcomVideoPlayer, #image, video");
    if (!media?.parentElement) return;
    const controls = document.createElement("div");
    controls.id = "intake-r34-controls";
    controls.className = "intake-watch-controls intake-r34-controls";
    for (const mode of ["audio", "video"]) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "intake-glitch-button";
      button.textContent = mode.toUpperCase();
      button.dataset.label = mode.toUpperCase();
      button.addEventListener("click", () => queue(button, location.href, mode));
      controls.appendChild(button);
    }
    media.parentElement.insertBefore(controls, media.nextSibling);
  }

  function scan() {
    mountListingButtons();
    mountDetailControls();
  }

  new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
  scan();
})();
