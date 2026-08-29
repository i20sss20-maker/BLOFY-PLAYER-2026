# BLOFY PLAYER — Package64 release contract

This contract preserves the proven v340 visual theme. A COMPLETE build must not intentionally replace the theme/layout identity. The package is grouped only for tracking; all 64 items remain part of one release target.

## Playback core
1. Media3 primary playback engine.
2. LibVLC compatibility fallback.
3. Media3 FFmpeg decoder extension bundled in release.
4. HLS playback support.
5. MPEG-TS playback support.
6. Direct/no-extension compatibility route.
7. MP4/MKV alternate-container VOD fallback.
8. 4K/UHD/HEVC-aware playback path.
9. Hardware decoder mode.
10. Software decoder fallback mode.
11. Per-source bounded recovery; no infinite retry loop.
12. 403/404 hard-route breaker shared across engines.
13. Startup timeout and stalled-start recovery.
14. First-frame success detection and positive route learning.
15. Stale playback callback/transaction cancellation.
16. Warm live channel switching without rebuilding the entire screen when possible.

## Live TV
17. First channel preview can auto-start when entering a live group.
18. Mini-player preview remains inside the channel/category experience.
19. Second OK/full action opens full-screen playback.
20. Up/down remote navigation changes channels reliably.
21. Channel list stays scoped to the selected group.
22. Live full-screen overlay/channel switch UI without forcing the main left menu.
23. EPG title/time overlay support.
24. Quick switching cancels the previous channel request immediately.

## Movies and series
25. Movie playback and fallback use the same compatibility matrix as validated VOD routes.
26. Series episode playback resolves episode links correctly.
27. Seasons and episodes are ordered naturally/ascending.
28. Resume progress is persisted for VOD.
29. Resume/Start behavior is available and controlled by settings.
30. Auto-next episode behavior is available and controlled by settings.
31. Details page does not auto-play content unexpectedly.
32. Details experience keeps the content page focused instead of exposing the main sidebar over it.

## Catalog, search and persistence
33. Completed playlists open from local cache without mandatory full reload.
34. Cache is accepted only after a completed sync state.
35. Package count/fingerprint integrity guards detect partial saved imports.
36. Atomic/staged catalog replacement prevents a half-written package becoming active.
37. Source-scoped catalog/cache identity prevents data leaking between servers/playlists.
38. Home/catalog state cache restores quickly after returning to the app.
39. Focus/scroll position is restored after returning from details/playback.
40. Large list RecyclerView/catalog tuning avoids UI freezes.
41. Instant local search begins from the first entered character.
42. Arabic-normalized search is supported.
43. Category/count query caches are invalidated atomically after catalog commits.
44. Manual refresh remains available and is the explicit way to force a re-download.

## TV remote and UI behavior
45. D-pad focus is deterministic in all primary screens.
46. Settings supports up/down/left/right navigation.
47. Rapid D-pad input does not crash or permanently lose focus.
48. Reduced-motion/faster TV focus mode is supported.
49. Back exits the current layer/player cleanly without freezing the activity.
50. Fatal playback error dialog owns focus and remains actionable by remote.
51. UI main-thread responsiveness watchdog records stalls without intentionally killing the app.
52. Existing v340 visual theme, logo, login/barcode identity and approved catalog presentation are preserved.

## Player controls and settings
53. Stream format setting: Auto / MPEG-TS / HLS.
54. Decoder setting: Auto / hardware / software.
55. Buffer profile setting: fast / auto / stable-4K.
56. Aspect setting: fit / zoom / fill.
57. Audio output setting includes stereo 2.0 option.
58. Subtitle preference includes Arabic-first / auto / off.
59. Subtitle size setting includes small / medium / large.
60. Live preview autoplay can be enabled/disabled.

## Operational stability and release integrity
61. Device-adaptive performance/cache behavior supports low-RAM devices.
62. Diagnostics retain useful playback route/error breadcrumbs for field testing.
63. Release passes unit tests, lint, release assemble, signing verification, zipalign, package/version, ABI/native-engine and archive-integrity checks.
64. Final installable signed APK is produced with upgrade-safe versionCode and without automatic merge/release mutation outside the working branch.
