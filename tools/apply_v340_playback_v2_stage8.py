#!/usr/bin/env python3
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'BLOFY-ANDROID-2026/app'
JAVA=APP/'src/main/java/tv/blofy/player'
PLAYER=JAVA/'PlayerActivity.java'
PREVIEW=JAVA/'LivePreviewController.java'
GRADLE=APP/'build.gradle.kts'

v2 = r'''package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.SystemClock;

import java.util.Locale;

/**
 * BLOFY Playback V2 intelligence layer.
 * Provider-scoped learning only; it never stores credentials or full signed URLs.
 */
final class PlaybackV2 {
    private static final String PREFS = "blofy_playback_v2";

    static final class Session {
        final Context app;
        String providerKey;
        final String kind;
        final String extension;
        final String title;
        final long openedAt = SystemClock.elapsedRealtime();
        long firstFrameAt;
        long bufferingStartedAt;
        int rebuffers;
        String engine = "media3";
        Session(Context c, String url, String k, String ext, String t) {
            app=c.getApplicationContext(); kind=safe(k); extension=safe(ext); title=safe(t);
            providerKey=providerKey(url);
        }
        void updateSource(String url) { String p=providerKey(url); if (!p.isEmpty()) providerKey=p; }
    }

    private PlaybackV2() {}

    static Session begin(Context c, String url, String kind, String extension, String title) {
        Session s=new Session(c,url,kind,extension,title);
        PlaybackDiagnostics.marker(c,"v2-session-start",kind,"",extension,"","provider="+s.providerKey);
        return s;
    }

    static String providerKey(String url) {
        try {
            Uri u=Uri.parse(safe(url)); String host=u.getHost();
            if (host==null) return "unknown";
            host=host.toLowerCase(Locale.US);
            return Integer.toHexString(host.hashCode());
        } catch(Throwable ignored) { return "unknown"; }
    }

    static void firstFrame(Session s) {
        if (s==null || s.firstFrameAt!=0) return;
        s.firstFrameAt=SystemClock.elapsedRealtime();
        long ttff=Math.max(0,s.firstFrameAt-s.openedAt);
        edit(s).putLong(key(s,"ttff"),ttff).putInt(key(s,"success"),getInt(s,"success")+1)
                .putInt(key(s,"fail"),0).apply();
        PlaybackDiagnostics.marker(s.app,"v2-first-frame",s.kind,"",s.extension,"",
                "engine="+s.engine+" ttff_ms="+ttff+" rebuffers="+s.rebuffers);
    }

    static void buffering(Session s, boolean active) {
        if (s==null) return;
        long now=SystemClock.elapsedRealtime();
        if (active) {
            if (s.firstFrameAt>0 && s.bufferingStartedAt==0) s.bufferingStartedAt=now;
        } else if (s.bufferingStartedAt>0) {
            long dur=now-s.bufferingStartedAt; s.bufferingStartedAt=0;
            if (dur>=600) s.rebuffers++;
            edit(s).putInt(key(s,"rebuffers"),s.rebuffers).apply();
        }
    }

    static void engine(Session s, String engine) { if(s!=null) s.engine=safe(engine); }

    static void failure(Session s, String reason) {
        if(s==null) return;
        int fails=getInt(s,"fail")+1;
        SharedPreferences.Editor e=edit(s).putInt(key(s,"fail"),fails);
        if (s.engine.contains("media3") && fails>=2) e.putString(key(s,"engine"),"vlc");
        else if (s.engine.contains("vlc") && fails>=2) e.putString(key(s,"engine"),"media3");
        e.apply();
        PlaybackDiagnostics.marker(s.app,"v2-failure",s.kind,"",s.extension,"",
                "engine="+s.engine+" consecutive="+fails+" reason="+safe(reason));
    }

    static boolean preferVlc(Session s, boolean ultraHd) {
        if(s==null) return false;
        String learned=prefs(s).getString(key(s,"engine"),"");
        if("vlc".equals(learned)) return true;
        // On HEVC titles with no advertised hardware decoder, avoid repeated MediaCodec init failures.
        return ultraHd && looksHevc(s.title+" "+s.extension) && !supportsMime("video/hevc")
                && getInt(s,"fail")>0;
    }

    static String recommendedBufferMode(Session s, String requested) {
        if(!"auto".equals(requested) || s==null) return requested;
        int r=prefs(s).getInt(key(s,"rebuffers"),0);
        long ttff=prefs(s).getLong(key(s,"ttff"),0);
        if(r>=2) return "stable";
        if(ttff>0 && ttff<1400 && r==0) return "fast";
        return "auto";
    }

    static String recommendedDecoderMode(Session s, String requested) {
        if(!"auto".equals(requested) || s==null) return requested;
        if(looksHevc(s.title+" "+s.extension) && !supportsMime("video/hevc")) return "software";
        return "auto";
    }

    static long liveTargetOffsetMs(Session s) {
        int r=s==null?0:prefs(s).getInt(key(s,"rebuffers"),0);
        return r>=2 ? 5_000L : 2_500L;
    }

    static String quickSummary(Session s) {
        if(s==null) return "V2";
        long ttff=prefs(s).getLong(key(s,"ttff"),0);
        return "V2 • "+s.engine.toUpperCase(Locale.US)+" • TTFF "+ttff+"ms • RB "+s.rebuffers;
    }

    static boolean supportsMime(String mime) {
        try {
            MediaCodecInfo[] infos=new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            for(MediaCodecInfo info:infos) {
                if(info.isEncoder()) continue;
                for(String type:info.getSupportedTypes()) if(type.equalsIgnoreCase(mime)) return true;
            }
        } catch(Throwable ignored) {}
        return false;
    }

    private static boolean looksHevc(String v) {
        String u=safe(v).toUpperCase(Locale.US);
        return u.contains("HEVC")||u.contains("H265")||u.contains("H.265")||u.contains("2160")||u.contains("4K");
    }
    private static String key(Session s,String suffix){return s.providerKey+":"+s.kind+":"+suffix;}
    private static SharedPreferences prefs(Session s){return s.app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static SharedPreferences.Editor edit(Session s){return prefs(s).edit();}
    private static int getInt(Session s,String suffix){return prefs(s).getInt(key(s,suffix),0);}
    private static String safe(String v){return v==null?"":v;}
}
'''
(JAVA/'PlaybackV2.java').write_text(v2,encoding='utf-8')

p=PLAYER.read_text(encoding='utf-8')
if 'private PlaybackV2.Session playbackV2;' not in p:
    p=p.replace('    private boolean lifecycleStarted;\n','    private boolean lifecycleStarted;\n    private PlaybackV2.Session playbackV2;\n',1)

anchor='        recoveryStep = preferredRecoveryStep();\n'
if anchor in p and 'PlaybackV2.begin(this' not in p:
    p=p.replace(anchor,anchor+'        playbackV2 = PlaybackV2.begin(this, url, kind, extension, title);\n',1)

# Refresh provider identity after native-link resolves.
resolve_anchor='                    playbackReferer = resolvedReferer;\n'
if resolve_anchor in p and 'playbackV2.updateSource(url)' not in p:
    p=p.replace(resolve_anchor,resolve_anchor+'                    if (playbackV2 != null) playbackV2.updateSource(url);\n',1)

# Provider-adaptive buffer selection.
old='        String mode = playerSetting(SettingsActivity.KEY_BUFFER, "auto");\n'
if old in p and 'PlaybackV2.recommendedBufferMode' not in p:
    p=p.replace(old,old+'        mode = PlaybackV2.recommendedBufferMode(playbackV2, mode);\n',1)

# Decoder capability matrix + learned provider engine.
dec='        String decoderMode = playerSetting(SettingsActivity.KEY_DECODER, "auto");\n'
if dec in p and 'PlaybackV2.recommendedDecoderMode' not in p:
    p=p.replace(dec,dec+'        decoderMode = PlaybackV2.recommendedDecoderMode(playbackV2, decoderMode);\n',1)

# Prefer learned VLC only after repeated provider/device failures.
init='    private void initializePlayer() {\n        if (player != null || !validUrl(url)) return;\n'
if init in p and 'PlaybackV2.preferVlc' not in p:
    p=p.replace(init,init+'        if (PlaybackV2.preferVlc(playbackV2, isUltraHd())) { PlaybackV2.engine(playbackV2, "vlc-profile"); openVlc("v2-profile-preferred"); return; }\n',1)

# Live edge target with gentle catch-up speed. This only applies to live MediaItems.
item='        MediaItem.Builder itemBuilder = new MediaItem.Builder()\n                .setUri(PlaybackPolicy.directPlaybackUrl(url)).setMediaId(title);\n'
if item in p and 'setLiveConfiguration' not in p:
    repl=item+'        if (isLive()) itemBuilder.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()\n                .setTargetOffsetMs(PlaybackV2.liveTargetOffsetMs(playbackV2))\n                .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build());\n'
    p=p.replace(item,repl,1)

# Instrument first frame and buffering without changing recovery ownership.
ff='    @Override public void onRenderedFirstFrame() {\n'
if ff in p and 'PlaybackV2.firstFrame(playbackV2);' not in p:
    p=p.replace(ff,ff+'        PlaybackV2.firstFrame(playbackV2);\n',1)

state='    @Override public void onPlaybackStateChanged(int playbackState) {\n'
if state in p and 'PlaybackV2.buffering(playbackV2' not in p:
    p=p.replace(state,state+'        PlaybackV2.buffering(playbackV2, playbackState == Player.STATE_BUFFERING);\n',1)

err='    @Override public void onPlayerError(PlaybackException error) {\n'
if err in p and 'PlaybackV2.failure(playbackV2' not in p:
    p=p.replace(err,err+'        PlaybackV2.failure(playbackV2, error == null ? "media3" : error.getMessage());\n',1)

# Mark engine transitions.
if 'usingVlc = false;\n        firstFrameRendered = false;' in p and 'PlaybackV2.engine(playbackV2, "media3")' not in p:
    p=p.replace('usingVlc = false;\n        firstFrameRendered = false;','usingVlc = false;\n        PlaybackV2.engine(playbackV2, "media3");\n        firstFrameRendered = false;',1)
if 'usingVlc = true;\n        vlcAttempted = true;' in p and 'PlaybackV2.engine(playbackV2, "vlc")' not in p:
    p=p.replace('usingVlc = true;\n        vlcAttempted = true;','usingVlc = true;\n        PlaybackV2.engine(playbackV2, "vlc");\n        vlcAttempted = true;',1)

PLAYER.write_text(p,encoding='utf-8')

# Preview source handoff already caches resolved URL; enlarge TTL to reduce Railway from hot path
# while keeping signed-link cache bounded.
pr=PREVIEW.read_text(encoding='utf-8')
pr=pr.replace('> 15 * 60_000L','> 30 * 60_000L')
if 'v2-preview-handoff' not in pr:
    marker='                URL_CACHE_BY_URL.put(openedResolved.url, openedResolved);\n'
    if marker in pr:
        pr=pr.replace(marker,marker+'                PlaybackDiagnostics.marker(context, "v2-preview-handoff", "live", "", openedResolved.extension, "cached", "ttl=30m");\n',1)
PREVIEW.write_text(pr,encoding='utf-8')

g=GRADLE.read_text(encoding='utf-8')
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000361',g,count=1)
g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-playback-v2-stage8"',g,count=1)
GRADLE.write_text(g,encoding='utf-8')

checks={
 JAVA/'PlaybackV2.java':['Provider-scoped learning','v2-first-frame','recommendedBufferMode','recommendedDecoderMode','liveTargetOffsetMs','supportsMime','quickSummary'],
 PLAYER:['PlaybackV2.Session playbackV2','PlaybackV2.begin(this','PlaybackV2.recommendedBufferMode','PlaybackV2.recommendedDecoderMode','PlaybackV2.preferVlc','setLiveConfiguration','PlaybackV2.firstFrame','PlaybackV2.buffering','PlaybackV2.failure'],
 PREVIEW:['> 30 * 60_000L','v2-preview-handoff'],
 GRADLE:['versionCode = 1000361','v340-playback-v2-stage8']}
for path,marks in checks.items():
    t=path.read_text(encoding='utf-8')
    for m in marks:
        if m not in t: raise SystemExit(f'stage8 invariant missing {path.name}: {m}')
print('stage8 Playback V2 applied: provider profiles + engine learning + TTFF/rebuffer stats + decoder matrix + adaptive buffer + live edge + preview handoff')
