"use strict";

const BRIDGE_ENDPOINT = "http://127.0.0.1:17845/enqueue";
const STATUS_ENDPOINT = "http://127.0.0.1:17845/status";
const NATIVE_HOST = "com.insrt.intake";

function isSupportedMediaUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

function bridgeHeaders(contentType = false) {
  const headers = { "X-iNTAKE-Extension": "1" };
  if (contentType) headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8";
  return headers;
}

async function parseBridgeResponse(response) {
  let payload = {};
  try {
    payload = await response.json();
  } catch {
    // The HTTP status still gives a useful fallback when the body is truncated.
  }
  if (!response.ok || !payload.ok) {
    throw new Error(payload.error || `iNTAKE rejected the request (${response.status}).`);
  }
  return payload;
}

async function postToBridge(url, mode, name) {
  const body = new URLSearchParams({ url, mode });
  if (typeof name === "string" && name) body.set("name", name);
  const response = await fetch(BRIDGE_ENDPOINT, {
    method: "POST",
    headers: bridgeHeaders(true),
    body,
    cache: "no-store"
  });
  return parseBridgeResponse(response);
}

function sendNativeWake(request) {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendNativeMessage(NATIVE_HOST, request, (response) => {
      if (chrome.runtime.lastError) {
        reject(new Error(
          "Could not start iNTAKE. Run Launch iNTAKE.cmd once to install the browser wake bridge."
        ));
        return;
      }
      if (!response?.ok) {
        reject(new Error(response?.error || "The iNTAKE wake bridge failed."));
        return;
      }
      resolve(response);
    });
  });
}

function notifyPage(sender, notification) {
  const tabId = sender?.tab?.id;
  if (!Number.isInteger(tabId)) return;
  chrome.tabs.sendMessage(tabId, {
    type: "intake.notification",
    notification
  }, () => void chrome.runtime.lastError);
}

async function enqueueInIntake(url, mode, name, sender) {
  if (!isSupportedMediaUrl(url)) {
    throw new Error("The page did not provide a valid media URL.");
  }
  if (mode !== "audio" && mode !== "video") {
    throw new Error("Unsupported download mode.");
  }

  try {
    return await postToBridge(url, mode, name);
  } catch (failure) {
    // Only a connection failure should wake the application. HTTP rejections
    // prove that the bridge is already online and should be returned directly.
    if (!(failure instanceof TypeError)) throw failure;
  }

  notifyPage(sender, {
    state: "starting",
    title: "iNTAKE // WAKE SIGNAL",
    message: "iNTAKE was offline. Opening it and attempting the download..."
  });
  return sendNativeWake({ url, mode, name: typeof name === "string" ? name : "" });
}

async function readDownloadStatus(jobId) {
  if (typeof jobId !== "string" || !/^[0-9a-f-]{36}$/i.test(jobId)) {
    throw new Error("Invalid iNTAKE job identifier.");
  }
  const response = await fetch(`${STATUS_ENDPOINT}?id=${encodeURIComponent(jobId)}`, {
    method: "GET",
    headers: bridgeHeaders(false),
    cache: "no-store"
  });
  return parseBridgeResponse(response);
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "intake.enqueue") {
    enqueueInIntake(message.url, message.mode, message.name, sender)
      .then((result) => sendResponse({ ok: true, result }))
      .catch((error) => sendResponse({ ok: false, error: error.message || String(error) }));
    return true;
  }
  if (message?.type === "intake.status") {
    readDownloadStatus(message.jobId)
      .then((result) => sendResponse({ ok: true, result }))
      .catch((error) => sendResponse({ ok: false, error: error.message || String(error) }));
    return true;
  }
  return false;
});
