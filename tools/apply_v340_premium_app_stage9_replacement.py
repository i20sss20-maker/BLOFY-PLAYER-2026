#!/usr/bin/env python3
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]; APP=ROOT/'BLOFY-ANDROID-2026/app'; JAVA=APP/'src/main/java/tv/blofy/player'
HOME=JAVA/'SevenMaxActivity.java'; VOD=JAVA/'VodPlayerActivity.java'; LIVE=JAVA/'LiveChannelOverlay.java'; PLAYER=JAVA/'PlayerActivity.java'; GRADLE=APP/'build.gradle.kts'

# Optional EPG cache; unsupported backend is harmless.
(JAVA/'EpgNowNextCache.java').write_text(r'''package tv.blofy.player;
import android.content.Context; import android.os.Handler; import android.os.Looper; import org.json.JSONObject;
import java.util.LinkedHashMap; import java.util.Map; import java.util.concurrent.ExecutorService; import java.util.concurrent.Executors;
final class EpgNowNextCache { interface Callback { void ready(String text); } private static final Handler MAIN=new Handler(Looper.getMainLooper()); private static final ExecutorService IO=Executors.newSingleThreadExecutor();
 private static final Map<String,CachedProgram>CACHE=new LinkedHashMap<String,CachedProgram>(64,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,CachedProgram> e){return size()>96;}};
 static void lookup(Context c,String id,Callback cb){if(id==null||id.isEmpty()){cb.ready("");return;} synchronized(CACHE){CachedProgram e=CACHE.get(id);if(e!=null&&!e.expired()){cb.ready(e.text);return;}}
 IO.execute(()->{String text="";try{JSONObject o=new BlofyApi(c).get("/api/epg/now-next?id="+BlofyApi.encode(id));String now=o.optString("now",o.optString("current","")),next=o.optString("next","");if(!now.isEmpty())text="الآن  •  "+now+(next.isEmpty()?"":"     التالي  •  "+next);}catch(Exception ignored){} final String out=text;synchronized(CACHE){CACHE.put(id,new CachedProgram(out));}MAIN.post(()->cb.ready(out));});}
 private static final class CachedProgram{final String text;final long at=System.currentTimeMillis();CachedProgram(String t){text=t;}boolean expired(){return System.currentTimeMillis()-at>120_000L;}} }
''',encoding='utf-8')

# Premium home is a standalone renderer invoked at the top of existing showHome().
h=HOME.read_text(encoding='utf-8')
if 'showPremiumHomeStage9()' not in h:
    m=re.search(r'(private void showHome\(\) \{)',h)
    if not m: raise SystemExit('stage9r: showHome missing')
    # Keep legacy body compile-reachable while runtime always uses premium home.
    h=h[:m.end()]+'''\n        showPremiumHomeStage9();\n        if (root != null) return;\n''' + h[m.end():]
    pos=m.start()
    helper='''    private void showPremiumHomeStage9() {\n        releasePreview();\n        stopHeroRotation();\n        screen = "home";\n        root.removeAllViews();\n        LinearLayout page = new LinearLayout(this);\n        page.setOrientation(LinearLayout.VERTICAL);\n        page.setPadding(dp(24), dp(12), dp(24), dp(28));\n        LinearLayout header = new LinearLayout(this);\n        header.setOrientation(LinearLayout.HORIZONTAL);\n        header.setGravity(Gravity.CENTER_VERTICAL);\n        header.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(220), dp(58)));\n        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));\n        TextView version = BlofyUi.chip(this, BuildConfig.VERSION_NAME);\n        header.addView(version, new LinearLayout.LayoutParams(dp(250), dp(34)));\n        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));\n        View heroFocus = addHero(page);\n        LinearLayout quick = new LinearLayout(this);\n        quick.setOrientation(LinearLayout.HORIZONTAL); quick.setGravity(Gravity.CENTER);\n        Button live = BlofyUi.button(this, "●  البث المباشر", true); live.setOnClickListener(v -> showLive()); quick.addView(live,new LinearLayout.LayoutParams(dp(220),dp(54)));\n        Button movies = BlofyUi.button(this, "الأفلام", false); movies.setOnClickListener(v -> showCatalog("movies",false)); LinearLayout.LayoutParams qm=new LinearLayout.LayoutParams(dp(180),dp(54));qm.leftMargin=dp(10);quick.addView(movies,qm);\n        Button series = BlofyUi.button(this, "المسلسلات", false); series.setOnClickListener(v -> showCatalog("series",false)); LinearLayout.LayoutParams qs=new LinearLayout.LayoutParams(dp(180),dp(54));qs.leftMargin=dp(10);quick.addView(series,qs);\n        Button search = BlofyUi.button(this, "بحث", false); search.setOnClickListener(v -> showUnifiedSearch()); LinearLayout.LayoutParams qx=new LinearLayout.LayoutParams(dp(150),dp(54));qx.leftMargin=dp(10);quick.addView(search,qx);\n        LinearLayout.LayoutParams quickParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(66));quickParams.bottomMargin=dp(10);page.addView(quick,quickParams);\n        addHomeRail(page, "متابعة المشاهدة", "أكمل من حيث توقفت", new HomeRailAdapter("", true, true), this::showHistory);\n        addHomeRail(page, "وصل حديثاً", "أحدث الأفلام في مكتبتك", new HomeRailAdapter("movies", false, false), () -> showCatalog("movies", false));\n        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setSmoothScrollingEnabled(true);scroll.addView(page);root.addView(scroll,match());\n        PlaybackDiagnostics.marker(this,"stage9-premium-home","home","","","","hero=true continue=true latest=true");\n        heroFocus.requestFocus();\n    }\n\n'''
    h=h[:pos]+helper+h[pos:]
h=h.replace('"BLOFY PLAYER  •  v328"','"BLOFY PLAYER  •  " + BuildConfig.VERSION_NAME')
HOME.write_text(h,encoding='utf-8')

# VOD quality selector joins existing audio/subtitle/stereo controls.
v=VOD.read_text(encoding='utf-8')
if 'private TextView qualityButton;' not in v:
    v=v.replace('    private TextView stereoButton;','    private TextView stereoButton;\n    private TextView qualityButton;\n    private int qualityStep;',1)
if 'الجودة: تلقائي' not in v:
    marker='        trackRow.addView(stereoButton, stereoParams);'
    if marker not in v: raise SystemExit('stage9r: stereo row missing')
    v=v.replace(marker,marker+'''\n        qualityButton = playerOptionButton("▣  الجودة: تلقائي");\n        qualityButton.setOnClickListener(view -> cycleQuality());\n        LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(dp(210), dp(42)); qualityParams.leftMargin = dp(10); trackRow.addView(qualityButton, qualityParams);''',1)
if 'qualityButton.setId' not in v:
    marker='        stereoButton.setId(View.generateViewId());'
    if marker not in v: raise SystemExit('stage9r: stereo id missing')
    v=v.replace(marker,marker+'\n        qualityButton.setId(View.generateViewId());',1)
if 'private void cycleQuality()' not in v:
    m=re.search(r'\n    private void cycleAudio\(\) \{',v)
    if not m: raise SystemExit('stage9r: cycleAudio missing')
    helper='''\n    private void cycleQuality() {\n        if (player == null || qualityButton == null) return; qualityStep=(qualityStep+1)%4; int w,h;String label;\n        if(qualityStep==1){w=1280;h=720;label="720p";} else if(qualityStep==2){w=1920;h=1080;label="1080p";} else if(qualityStep==3){w=3840;h=2160;label="4K";} else {w=Integer.MAX_VALUE;h=Integer.MAX_VALUE;label="تلقائي";}\n        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(w,h).build()); qualityButton.setText("▣  الجودة: "+label);\n        PlaybackDiagnostics.marker(this,"stage9-track-manager",kind,id,extension,sourceVariant,"quality="+label);\n    }\n'''
    v=v[:m.start()]+helper+v[m.start():]
VOD.write_text(v,encoding='utf-8')

# Quick diagnostics on Stage7 chip.
p=PLAYER.read_text(encoding='utf-8')
if 'stage9-quick-diagnostics' not in p:
    marker='        root.addView(playbackStatusChip, statusParams);'
    if marker not in p: raise SystemExit('stage9r: status chip missing')
    p=p.replace(marker,marker+'''\n        playbackStatusChip.setClickable(true); playbackStatusChip.setLongClickable(true); playbackStatusChip.setOnLongClickListener(v -> { String summary=PlaybackV2.quickSummary(playbackV2); android.widget.Toast.makeText(this,summary,android.widget.Toast.LENGTH_LONG).show(); PlaybackDiagnostics.marker(this,"stage9-quick-diagnostics",kind,id,extension,sourceVariant,summary); return true; });''',1)
PLAYER.write_text(p,encoding='utf-8')

# Live optional now/next.
l=LIVE.read_text(encoding='utf-8')
if 'private final TextView nowNext;' not in l:
    l=l.replace('    private final RecyclerView list;','    private final RecyclerView list;\n    private final TextView nowNext;',1)
if 'EPG غير متوفر' not in l:
    marker='        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));'
    if marker not in l: raise SystemExit('stage9r: hint missing')
    l=l.replace(marker,marker+'''\n        nowNext=BlofyUi.text(activity,"EPG غير متوفر",11,Color.rgb(205,196,220)); nowNext.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); nowNext.setTextDirection(View.TEXT_DIRECTION_RTL); panel.addView(nowNext,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(34)));''',1)
if 'EpgNowNextCache.lookup' not in l:
    marker='        this.currentId = currentId == null ? "" : currentId;'
    if marker not in l: raise SystemExit('stage9r: show current id missing')
    l=l.replace(marker,marker+'''\n        EpgNowNextCache.lookup(activity,this.currentId,text -> nowNext.setText(text==null||text.isEmpty()?"EPG غير متوفر":text));''',1)
LIVE.write_text(l,encoding='utf-8')

g=GRADLE.read_text(encoding='utf-8');g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000362',g,count=1);g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-premium-app-stage9"',g,count=1);GRADLE.write_text(g,encoding='utf-8')
for path,marks in {HOME:['showPremiumHomeStage9','stage9-premium-home','متابعة المشاهدة','وصل حديثاً'],VOD:['qualityButton','cycleQuality','stage9-track-manager'],PLAYER:['stage9-quick-diagnostics'],LIVE:['nowNext','EpgNowNextCache.lookup'],GRADLE:['versionCode = 1000362']}.items():
 t=path.read_text(encoding='utf-8');
 for m in marks:
  if m not in t: raise SystemExit(f'stage9r missing {path.name}: {m}')
print('stage9 replacement applied: independent premium home + track manager + diagnostics + optional EPG')
