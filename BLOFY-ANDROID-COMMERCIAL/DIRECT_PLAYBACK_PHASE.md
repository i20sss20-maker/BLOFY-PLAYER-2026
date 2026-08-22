# BLOFY direct playback phase

This branch validates the agreed native architecture before UI work:

- Media3 + OkHttp is the primary Android TV player path.
- Railway authorizes and returns a short-lived redirect only.
- Video bytes travel directly from the IPTV provider to the TV.
- Both provider HTTP and HTTPS sources are allowed.
- Provider 401/403/456 responses are treated as URL/account/IP/header failures, not decoder failures.
- Railway proxy/transcode is not part of the primary playback path in this phase.
- LibVLC fallback is intentionally added only after direct channel/movie/episode validation.
