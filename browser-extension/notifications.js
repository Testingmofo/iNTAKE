(() => {
  "use strict";

  const NOTICE_ID = "intake-download-notice";
  let dismissalTimer = 0;

  function currentPageTitle() {
    return document.title.replace(/\s+-\s+YouTube\s*$/i, "").trim() || "MEDIA REQUEST";
  }

  function noticeElement() {
    let notice = document.getElementById(NOTICE_ID);
    if (notice) return notice;
    notice = document.createElement("aside");
    notice.id = NOTICE_ID;
    notice.className = "intake-download-notice";
    notice.setAttribute("role", "status");
    notice.setAttribute("aria-live", "polite");
    const title = document.createElement("strong");
    title.className = "intake-notice-title";
    const message = document.createElement("span");
    message.className = "intake-notice-message";
    notice.append(title, message);
    (document.body || document.documentElement).appendChild(notice);
    return notice;
  }

  function show({ state = "active", title = "iNTAKE", message = "" }, timeout = 0) {
    const notice = noticeElement();
    notice.className = `intake-download-notice intake-notice-${state}`;
    notice.querySelector(".intake-notice-title").textContent = title;
    notice.querySelector(".intake-notice-message").textContent = message;
    notice.classList.add("intake-notice-visible");
    clearTimeout(dismissalTimer);
    if (timeout > 0) {
      dismissalTimer = setTimeout(() => notice.classList.remove("intake-notice-visible"), timeout);
    }
  }

  function sendStatus(jobId) {
    return new Promise((resolve) => {
      chrome.runtime.sendMessage({ type: "intake.status", jobId }, (response) => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        resolve(response || { ok: false, error: "No status response from iNTAKE." });
      });
    });
  }

  async function monitor(jobId, fallbackTitle = currentPageTitle()) {
    if (!jobId) return;
    let failedPolls = 0;
    for (;;) {
      await new Promise((resolve) => setTimeout(resolve, 1500));
      const response = await sendStatus(jobId);
      if (!response.ok) {
        failedPolls += 1;
        if (failedPolls < 3) continue;
        show({
          state: "error",
          title: `${fallbackTitle} // FAILED TO DOWNLOAD`,
          message: "Check the main GUI or error logs."
        }, 9000);
        return;
      }

      failedPolls = 0;
      const job = response.result;
      const title = job.title || fallbackTitle;
      if (job.status === "completed") {
        show({
          state: "success",
          title: `${title} // DOWNLOAD COMPLETE`,
          message: "Media transfer finished successfully."
        }, 7000);
        return;
      }
      if (job.status === "error") {
        show({
          state: "error",
          title: `${title} // FAILED TO DOWNLOAD`,
          message: "Check the main GUI or error logs."
        }, 9000);
        return;
      }

      const statusText = job.status === "downloading"
        ? `DOWNLOADING ${job.progress || ""}`.trim()
        : job.status.toUpperCase();
      show({
        state: "active",
        title: `${title} // ${statusText}`,
        message: "iNTAKE is processing the media request in the background."
      });
    }
  }

  chrome.runtime.onMessage.addListener((message) => {
    if (message?.type === "intake.notification") {
      show(message.notification || {});
    }
  });

  globalThis.iNTAKENotifications = { show, monitor, currentPageTitle };
})();
