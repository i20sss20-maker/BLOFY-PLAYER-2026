const DEFAULT_PROVIDER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";

function cleanHeaderValue(value) {
  const cleaned = String(value || "").replace(/[\r\n\0]/g, " ").trim();
  return cleaned.length >= 3 && cleaned.length <= 256 ? cleaned : "";
}

export function providerRequestHeaders(requestHeaders = {}, configuredUserAgent = process.env.PROVIDER_USER_AGENT) {
  return {
    accept: "video/mp2t,application/vnd.apple.mpegurl,application/x-mpegURL,video/*,audio/*,*/*;q=0.8",
    "user-agent": cleanHeaderValue(configuredUserAgent) || DEFAULT_PROVIDER_USER_AGENT,
    ...(requestHeaders.range ? { range: String(requestHeaders.range) } : {}),
  };
}

export function providerResponseStatus(value) {
  const status = Number(value);
  return Number.isInteger(status) && status >= 400 && status <= 599 ? status : 502;
}

// A live provider response can be endless. Node's ServerResponse does not
// automatically cancel the upstream Web stream when a TV/player disconnects,
// so bind both possible disconnect signals and run the canceller exactly once.
export function bindRelayCancellation(req, res, cancelUpstream) {
  let state = "open";

  const detach = () => {
    req.removeListener("aborted", cancel);
    res.removeListener("close", cancel);
  };

  const cancel = () => {
    if (state !== "open") return;
    state = "cancelled";
    detach();
    Promise.resolve().then(cancelUpstream).catch(() => {});
  };

  const complete = () => {
    if (state !== "open") return;
    state = "completed";
    detach();
  };

  req.once("aborted", cancel);
  res.once("close", cancel);
  if (req.aborted || res.destroyed) queueMicrotask(cancel);

  return {
    cancel,
    complete,
    get cancelled() { return state === "cancelled"; },
  };
}
