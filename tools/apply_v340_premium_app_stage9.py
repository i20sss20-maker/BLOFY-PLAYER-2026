#!/usr/bin/env python3
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'BLOFY-ANDROID-2026/app'
JAVA=APP/'src/main/java/tv/blofy/player'
HOME=JAVA/'SevenMaxActivity.java'
VOD=JAVA/'VodPlayerActivity.java'
LIVE=JAVA/'LiveChannelOverlay.java'
PLAYER=JAVA/'PlayerActivity.java'
GRADLE=APP/'build.gradle.kts'

# ---- Optional EPG Now/Next cache. Backend absence is a supported state.
epg=r'''package tv.blofy.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EpgNowNextCache {
    interface Callback { void ready(String text); }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Map<String,Entry> CACHE=new LinkedHashMap<String,Entry>(64,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Entry> e){return size()>96;}
    };
    static void lookup(Context c,String id,Callback cb){
        if(id==null||id.isEmpty()){cb.ready("");return;}
        synchronized(CACHE){Entry e=CACHE.get(id);if(e!=null&&!e.expired()){cb.ready(e.text);return;}}
        IO.execute(()->{
            String text="";
            try{
                JSONObject o=new BlofyApi(c).get("/api/epg/now-next?id="+BlofyApi.encode(id));
                String now=o.optString("now",o.optString("current",""));
                String next=o.optString("next","");
                if(!now.isEmpty()) text="الآن  •  "+now+(next.isEmpty()?"":"     التالي  •  "+next);
            }catch(Exception ignored){}
            final String out=text;
            synchronized(CACHE){CACHE.put(id,new Entry(out));}
            MAIN.post(()->cb.ready(out));
        });
    }
    private static final class Entry{final String text;final long at=System.currentTimeMillis();Entry(String t){text=t;}boolean expired(){return System.currentTimeMillis()-at>120_000L;}}
}
'''
(JAVA/'EpgNowNextCache.java').write_text(epg,encoding='utf-8')

# ---- Premium home: activate the already-built cinematic hero and home rails.
h=HOME.read_text(encoding='utf-8')
if 'stage9-premium-home' not in h:
    header_anchor='        page.addView(header, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));\n'
    if header_anchor not in h: raise SystemExit('stage9: home header anchor missing')
    h=h.replace(header_anchor,header_anchor+'\n        // stage9-premium-home: activate the existing cinematic hero on the launcher.\n        addHero(page);\n',1)

    launch_anchor='        page.addView(launchers, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));\n'
    if launch_anchor not in h: raise SystemExit('stage9: launcher anchor missing')
    replacement='''        page.addView(launchers, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(292)));\n\n        addHomeRail(page, "متابعة المشاهدة", "أكمل من حيث توقفت",\n                new HomeRailAdapter("", true, true), this::showHistory);\n        addHomeRail(page, "وصل حديثاً", "أحدث الأفلام في مكتبتك",\n                new HomeRailAdapter("movies", false, false), () -> showCatalog("movies", false));\n'''
    h=h.replace(launch_anchor,replacement,1)

    root_anchor='        root.addView(page, match());\n        live.requestFocus();\n'
    if root_anchor not in h: raise SystemExit('stage9: home root anchor missing')
    h=h.replace(root_anchor,'''        ScrollView premiumScroll = new ScrollView(this);\n        premiumScroll.setFillViewport(true);\n        premiumScroll.setSmoothScrollingEnabled(true);\n        premiumScroll.addView(page);\n        root.addView(premiumScroll, match());\n        PlaybackDiagnostics.marker(this, "stage9-premium-home", "home", "", "", "",\n                "hero=true continue=true latest=true");\n        live.requestFocus();\n''',1)

# Real build version instead of stale v328 label.
h=h.replace('"BLOFY PLAYER  •  v328"','"BLOFY PLAYER  •  " + BuildConfig.VERSION_NAME')
HOME.write_text(h,encoding='utf-8')

# ---- VOD Track Manager: add video quality control beside audio/subtitle/stereo.
v=VOD.read_text(encoding='utf-8')
if 'private TextView qualityButton;' not in v:
    v=v.replace('    private TextView stereoButton;\n','    private TextView stereoButton;\n    private TextView qualityButton;\n    private int qualityStep;\n',1)

track_anchor='''        stereoButton.setOnClickListener(v -> toggleStereo());\n        LinearLayout.LayoutParams stereoParams = new LinearLayout.LayoutParams(dp(220), dp(42));\n        stereoParams.leftMargin = dp(10);\n        trackRow.addView(stereoButton, stereoParams);\n'''
if track_anchor in v and 'الجودة: تلقائي' not in v:
    v=v.replace(track_anchor,track_anchor+'''        qualityButton = playerOptionButton("▣  الجودة: تلقائي");\n        qualityButton.setOnClickListener(view -> cycleQuality());\n        LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(dp(210), dp(42));\n        qualityParams.leftMargin = dp(10);\n        trackRow.addView(qualityButton, qualityParams);\n''',1)

ids='''        audioButton.setId(View.generateViewId());\n        subtitleButton.setId(View.generateViewId());\n        stereoButton.setId(View.generateViewId());\n'''
if ids in v and 'qualityButton.setId' not in v:
    v=v.replace(ids,ids+'        qualityButton.setId(View.generateViewId());\n',1)

# Update DPAD chain to include quality.
v=v.replace('stereoButton.setNextFocusRightId(stereoButton.getId());','stereoButton.setNextFocusRightId(qualityButton.getId());\n        qualityButton.setNextFocusLeftId(stereoButton.getId());\n        qualityButton.setNextFocusRightId(qualityButton.getId());')
v=v.replace('stereoButton.setNextFocusUpId(stereoButton.getId());','stereoButton.setNextFocusUpId(stereoButton.getId());\n        qualityButton.setNextFocusUpId(qualityButton.getId());')
v=v.replace('stereoButton.setNextFocusDownId(playerView.getId());','stereoButton.setNextFocusDownId(playerView.getId());\n        qualityButton.setNextFocusDownId(playerView.getId());')
v=v.replace('stereoButton.setOnFocusChangeListener(fixedControlFocus);','stereoButton.setOnFocusChangeListener(fixedControlFocus);\n        qualityButton.setOnFocusChangeListener(fixedControlFocus);')

if 'private void cycleQuality()' not in v:
    anchor='    private void cycleAudio() {\n'
    if anchor not in v: raise SystemExit('stage9: cycleAudio anchor missing')
    helper='''    private void cycleQuality() {\n        if (player == null) return;\n        qualityStep = (qualityStep + 1) % 4;\n        int width; int height; String label;\n        if (qualityStep == 1) { width = 1280; height = 720; label = "720p"; }\n        else if (qualityStep == 2) { width = 1920; height = 1080; label = "1080p"; }\n        else if (qualityStep == 3) { width = 3840; height = 2160; label = "4K"; }\n        else { width = Integer.MAX_VALUE; height = Integer.MAX_VALUE; label = "تلقائي"; }\n        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()\n                .setMaxVideoSize(width, height).build());\n        qualityButton.setText("▣  الجودة: " + label);\n        PlaybackDiagnostics.marker(this, "stage9-track-manager", kind, id, extension, sourceVariant,\n                "quality=" + label);\n    }\n\n'''
    v=v.replace(anchor,helper+anchor,1)
VOD.write_text(v,encoding='utf-8')

# ---- Quick diagnostics: long-press the visible playback status chip.
p=PLAYER.read_text(encoding='utf-8')
status='        root.addView(playbackStatusChip, statusParams);\n'
if status in p and 'stage9-quick-diagnostics' not in p:
    p=p.replace(status,status+'''        playbackStatusChip.setClickable(true);\n        playbackStatusChip.setLongClickable(true);\n        playbackStatusChip.setOnLongClickListener(v -> {\n            String summary = PlaybackV2.quickSummary(playbackV2);\n            android.widget.Toast.makeText(this, summary, android.widget.Toast.LENGTH_LONG).show();\n            PlaybackDiagnostics.marker(this, "stage9-quick-diagnostics", kind, id, extension, sourceVariant, summary);\n            return true;\n        });\n''',1)
PLAYER.write_text(p,encoding='utf-8')

# ---- Live overlay optional Now/Next line.
l=LIVE.read_text(encoding='utf-8')
if 'private final TextView nowNext;' not in l:
    l=l.replace('    private final RecyclerView list;\n','    private final RecyclerView list;\n    private final TextView nowNext;\n',1)

hint='''        TextView hint = BlofyUi.text(activity, "↑↓ تنقل   •   OK تشغيل   •   رجوع إغلاق", 12, BlofyUi.MUTED);\n        hint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);\n        hint.setTextDirection(View.TEXT_DIRECTION_RTL);\n        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));\n'''
if hint in l and 'EPG غير متوفر' not in l:
    l=l.replace(hint,hint+'''        nowNext = BlofyUi.text(activity, "EPG غير متوفر", 11, Color.rgb(205, 196, 220));\n        nowNext.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);\n        nowNext.setTextDirection(View.TEXT_DIRECTION_RTL);\n        panel.addView(nowNext, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));\n''',1)

show='''    void show(String currentId) {\n        this.currentId = currentId == null ? "" : currentId;\n'''
if show in l and 'EpgNowNextCache.lookup' not in l:
    l=l.replace(show,show+'''        EpgNowNextCache.lookup(activity, this.currentId, text -> {\n            if (nowNext != null) nowNext.setText(text == null || text.isEmpty() ? "EPG غير متوفر" : text);\n        });\n''',1)
LIVE.write_text(l,encoding='utf-8')

# Version.
g=GRADLE.read_text(encoding='utf-8')
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000362',g,count=1)
g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-premium-app-stage9"',g,count=1)
GRADLE.write_text(g,encoding='utf-8')

checks={
 HOME:['stage9-premium-home','addHero(page)','متابعة المشاهدة','وصل حديثاً','BuildConfig.VERSION_NAME'],
 VOD:['qualityButton','private void cycleQuality()','stage9-track-manager','setMaxVideoSize'],
 PLAYER:['stage9-quick-diagnostics','PlaybackV2.quickSummary'],
 LIVE:['nowNext','EpgNowNextCache.lookup','EPG غير متوفر'],
 JAVA/'EpgNowNextCache.java':['/api/epg/now-next','120_000L'],
 GRADLE:['versionCode = 1000362','v340-premium-app-stage9']}
for path,marks in checks.items():
    t=path.read_text(encoding='utf-8')
    for m in marks:
        if m not in t: raise SystemExit(f'stage9 invariant missing {path.name}: {m}')
print('stage9 premium app applied: hero + continue/latest rails + quality track manager + quick diagnostics + optional EPG now-next')
