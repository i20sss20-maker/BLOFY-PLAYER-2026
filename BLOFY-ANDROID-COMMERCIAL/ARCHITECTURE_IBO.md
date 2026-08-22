# BLOFY PLAYER — IBO Architecture

## Product flow
Device ID + QR + license -> web playlist management -> device receives playlist -> 0–100 discovery -> local catalogue -> Home / Live / Movies / Series -> direct playback.

## Control plane
Railway handles device identity, pairing, trial/license/expiry, renewal/payment integration, and playlist configuration handoff only.

## Media plane
Android device connects directly to IPTV provider/CDN. Railway never proxies, relays or transcodes normal playback.

## Supported provider inputs
- Xtream Codes
- M3U / M3U8

## Discovery pipeline
The user sees only BLOFY branding and a percentage. Internally the app performs a bounded compatibility decision tree:
1. DNS / socket / HTTP(S) reachability
2. redirects and final host
3. Xtream player_api.php authentication
4. accepted request profile (headers/User-Agent)
5. get.php M3U Plus fallback
6. Live/VOD/Series endpoints
7. EPG capability
8. one lightweight media probe
9. protocol/container detection (TS/HLS/DASH/MP4/MKV/WebM where applicable)
10. CDN/host requirements discovered through normal responses/redirects
11. playback engine recommendation
12. persist CompatibilityProfile

No brute-force request storms. Discovery must remain a short bounded decision tree.

## Playback engines
Embedded:
- Auto policy
- Android Media3 / ExoPlayer primary
- LibVLC fallback

External player targets:
- MX Player Free
- MX Player Pro
- VLC
- Android system chooser / any installed player

HTTP authentication/provider rejection (401/403/456) is not a decoder failure and must not switch engines blindly.

## Local data
Each playlist has a stable playlistId. Catalogue, categories, favourites, history, sync state and compatibility data are namespaced by playlistId. Room is the source of truth for local catalogue data.

## Performance
- No provider/database heavy work on UI thread
- bounded/paged Room reads
- image caching
- no expensive artwork reload on every remote focus event
- minimal TV animations
- background sync/discovery
- cached content opens immediately

## UX rules
- TV-first 10-foot navigation
- BLOFY purple theme retained
- playlist management model inspired by IBO-style flow without copying proprietary code/assets
- after web save, device should ingest the playlist automatically
- discovery screen displays percentage only
- series episodes fetched on demand when series is opened
