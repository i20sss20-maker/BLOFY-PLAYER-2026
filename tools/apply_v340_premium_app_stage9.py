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

# Optional EPG cache. Lack of the endpoint remains a supported state.
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

# Premium home. Work only inside showHome() so later identical anchors elsewhere are untouched.
h=HOME.read_text(encoding='utf-8')
if 'stage9-premium-home' not in h:
    home_start=h.find('    private void showHome() {')
    home_end=h.find('\n    private TextView homeTile(',home_start)
    if home_start<0 or home_end<0: raise SystemExit('stage9: showHome boundaries missing')
    section=h[home_start:home_end]

    # Insert hero after the home header regardless of formatting/line wrapping.
    header_re=re.compile(r'(page\.addView\(header\s*,\s*new LinearLayout\.LayoutParams\(\s*ViewGroup\.LayoutParams\.MATCH_PARENT\s*,\s*dp\(72\)\s*\)\s*\)\s*;)',re.S)
    section,n=header_re.subn(r'\1\n\n        // stage9-premium-home: cinematic rotating hero.\n        addHero(page);',section,count=1)
    if n==0:
        # Fallback: insert before launchers after header construction.
        marker='        LinearLayout launchers = new LinearLayout(this);'
        if marker not in section: raise SystemExit('stage9: home header/launchers anchor missing')
        section=section.replace(marker,'        // stage9-premium-home: cinematic rotating hero.\n        addHero(page);\n\n'+marker,1)

    # Change launcher from weight-fill to fixed height and add real rails below it.
    launch_re=re.compile(r'page\.addView\(launchers\s*,\s*new LinearLayout\.LayoutParams\(\s*ViewGroup\.LayoutParams\.MATCH_PARENT\s*,\s*0\s*,\s*1f\s*\)\s*\)\s*;',re.S)
    replacement='''page.addView(launchers, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(292)));\n\n        addHomeRail(page, "متابعة المشاهدة", "أكمل من حيث توقفت",\n                new HomeRailAdapter("", true, true), this::showHistory);\n        addHomeRail(page, "وصل حديثاً", "أحدث الأفلام في مكتبتك",\n                new HomeRailAdapter("movies", false, false), () -> showCatalog("movies", false));'''
    section,n=launch_re.subn(replacement,section,count=1)
    if n==0:
        raise SystemExit('stage9: launcher layout anchor missing')

    # Wrap the now-taller home in a scroll container.
    root_re=re.compile(r'root\.addView\(page\s*,\s*match\(\)\s*\)\s*;\s*live\.requestFocus\(\)\s*;',re.S)
    root_repl='''ScrollView premiumScroll = new ScrollView(this);\n        premiumScroll.setFillViewport(true);\n        premiumScroll.setSmoothScrollingEnabled(true);\n        premiumScroll.addView(page);\n        root.addView(premiumScroll, match());\n        PlaybackDiagnostics.marker(this, "stage9-premium-home", "home", "", "", "",\n                "hero=true continue=true latest=true");\n        live.requestFocus();'''
    section,n=root_re.subn(root_repl,section,count=1)
    if n==0: raise SystemExit('stage9: home root anchor missing')
    h=h[:home_start]+section+h[home_end:]

h=h.replace('"BLOFY PLAYER  •  v328"','"BLOFY PLAYER  •  " + BuildConfig.VERSION_NAME')
HOME.write_text(h,encoding='utf-8')

# VOD Track Manager: quality joins existing audio/subtitle/stereo controls.
v=VOD.read_text(encoding='utf-8')
if 'private TextView qualityButton;' not in v:
    field='    private TextView stereoButton;\n'
    if field not in v: raise SystemExit('stage9: stereo field missing')
    v=v.replace(field,field+'    private TextView qualityButton;\n    private int qualityStep;\n',1)

if 'الجودة: تلقائي' not in v:
    track_re=re.compile(r'(stereoButton\.setOnClickListener\([^;]+;\s*LinearLayout\.LayoutParams stereoParams\s*=\s*new LinearLayout\.LayoutParams\(dp\(220\),\s*dp\(42\)\);\s*stereoParams\.leftMargin\s*=\s*dp\(10\);\s*trackRow\.addView\(stereoButton,\s*stereoParams\);)',re.S)
    addition=r'''\1
        qualityButton = playerOptionButton("▣  الجودة: تلقائي");
        qualityButton.setOnClickListener(view -> cycleQuality());
        LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(dp(210), dp(42));
        qualityParams.leftMargin = dp(10);
        trackRow.addView(qualityButton, qualityParams);'''
    v,n=track_re.subn(addition,v,count=1)
    if n==0: raise SystemExit('stage9: VOD track row anchor missing')

if 'qualityButton.setId' not in v:
    marker='        stereoButton.setId(View.generateViewId());\n'
    if marker not in v: raise SystemExit('stage9: VOD control IDs anchor missing')
    v=v.replace(marker,marker+'        qualityButton.setId(View.generateViewId());\n',1)

if 'qualityButton.setNextFocusLeftId' not in v:
    v=v.replace('stereoButton.setNextFocusRightId(stereoButton.getId());','stereoButton.setNextFocusRightId(qualityButton.getId());\n        qualityButton.setNextFocusLeftId(stereoButton.getId());\n        qualityButton.setNextFocusRightId(qualityButton.getId());',1)
    v=v.replace('stereoButton.setNextFocusUpId(stereoButton.getId());','stereoButton.setNextFocusUpId(stereoButton.getId());\n        qualityButton.setNextFocusUpId(qualityButton.getId());',1)
    v=v.replace('stereoButton.setNextFocusDownId(playerView.getId());','stereoButton.setNextFocusDownId(playerView.getId());\n        qualityButton.setNextFocusDownId(playerView.getId());',1)
    v=v.replace('stereoButton.setOnFocusChangeListener(fixedControlFocus);','stereoButton.setOnFocusChangeListener(fixedControlFocus);\n        qualityButton.setOnFocusChangeListener(fixedControlFocus);',1)

if 'private void cycleQuality()' not in v:
    m=re.search(r'\n    private void cycleAudio\(\) \{',v)
    if not m: raise SystemExit('stage9: cycleAudio anchor missing')
    helper='''\n    private void cycleQuality() {\n        if (player == null || qualityButton == null) return;\n        qualityStep = (qualityStep + 1) % 4;\n        int width; int height; String label;\n        if (qualityStep == 1) { width = 1280; height = 720; label = "720p"; }\n        else if (qualityStep == 2) { width = 1920; height = 1080; label = "1080p"; }\n        else if (qualityStep == 3) { width = 3840; height = 2160; label = "4K"; }\n        else { width = Integer.MAX_VALUE; height = Integer.MAX_VALUE; label = "تلقائي"; }\n        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()\n                .setMaxVideoSize(width, height).build());\n        qualityButton.setText("▣  الجودة: " + label);\n        PlaybackDiagnostics.marker(this, "stage9-track-manager", kind, id, extension, sourceVariant,\n                "quality=" + label);\n    }\n'''
    v=v[:m.start()]+helper+v[m.start():]
VOD.write_text(v,encoding='utf-8')

# Quick diagnostics on the Stage7 visible status chip.
p=PLAYER.read_text(encoding='utf-8')
if 'stage9-quick-diagnostics' not in p:
    status_re=re.compile(r'(root\.addView\(playbackStatusChip\s*,\s*statusParams\)\s*;)')
    diag=r'''\1
        playbackStatusChip.setClickable(true);
        playbackStatusChip.setLongClickable(true);
        playbackStatusChip.setOnLongClickListener(v -> {
            String summary = PlaybackV2.quickSummary(playbackV2);
            android.widget.Toast.makeText(this, summary, android.widget.Toast.LENGTH_LONG).show();
            PlaybackDiagnostics.marker(this, "stage9-quick-diagnostics", kind, id, extension, sourceVariant, summary);
            return true;
        });'''
    p,n=status_re.subn(diag,p,count=1)
    if n==0: raise SystemExit('stage9: playback status chip anchor missing')
PLAYER.write_text(p,encoding='utf-8')

# Live overlay optional EPG line. It never blocks playback.
l=LIVE.read_text(encoding='utf-8')
if 'private final TextView nowNext;' not in l:
    marker='    private final RecyclerView list;\n'
    if marker not in l: raise SystemExit('stage9: live list field missing')
    l=l.replace(marker,marker+'    private final TextView nowNext;\n',1)
if 'EPG غير متوفر' not in l:
    # Put it after the existing hint regardless of exact hint wording.
    hint_re=re.compile(r'(panel\.addView\(hint\s*,\s*new LinearLayout\.LayoutParams\(ViewGroup\.LayoutParams\.MATCH_PARENT\s*,\s*dp\(38\)\)\s*\)\s*;)')
    epg_ui=r'''\1

        nowNext = BlofyUi.text(activity, "EPG غير متوفر", 11, Color.rgb(205, 196, 220));
        nowNext.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        nowNext.setTextDirection(View.TEXT_DIRECTION_RTL);
        panel.addView(nowNext, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));'''
    l,n=hint_re.subn(epg_ui,l,count=1)
    if n==0: raise SystemExit('stage9: live hint anchor missing')
if 'EpgNowNextCache.lookup' not in l:
    show_re=re.compile(r'(void show\(String currentId\) \{\s*this\.currentId\s*=\s*currentId == null \? "" : currentId;)')
    lookup=r'''\1
        EpgNowNextCache.lookup(activity, this.currentId, text -> {
            nowNext.setText(text == null || text.isEmpty() ? "EPG غير متوفر" : text);
        });'''
    l,n=show_re.subn(lookup,l,count=1)
    if n==0: raise SystemExit('stage9: live show anchor missing')
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
