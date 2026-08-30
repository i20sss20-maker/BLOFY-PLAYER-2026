#!/usr/bin/env python3
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'BLOFY-ANDROID-2026/app'; JAVA=APP/'src/main/java/tv/blofy/player'
T=JAVA/'PlaybackTransportFactory.java'; G=APP/'build.gradle.kts'
t=T.read_text(encoding='utf-8')
# Shared request headers for Default HTTP and Cronet. This keeps normal fast path unchanged.
if 'private static Map<String, String> requestHeaders(String referer)' not in t:
    anchor='    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor,\n'
    if anchor not in t: raise SystemExit('compat-v1: transport create anchor missing')
    helper='''    private static Map<String, String> requestHeaders(String referer) {\n        Map<String, String> headers = new HashMap<>();\n        headers.put("Accept", "*/*");\n        headers.put("Accept-Encoding", "identity");\n        headers.put("Connection", "keep-alive");\n        headers.put("Icy-MetaData", "1");\n        if (referer != null && !referer.isEmpty()) {\n            headers.put("Referer", referer);\n            try {\n                android.net.Uri uri = android.net.Uri.parse(referer);\n                if (uri.getScheme() != null && uri.getHost() != null) {\n                    String origin = uri.getScheme() + "://" + uri.getHost()\n                            + (uri.getPort() > 0 ? ":" + uri.getPort() : "");\n                    headers.put("Origin", origin);\n                }\n            } catch (Exception ignored) {}\n        }\n        return headers;\n    }\n\n'''
    t=t.replace(anchor,helper+anchor,1)
# Replace duplicated default headers with shared helper.
pos=t.find('int compatibilityProfile, String referer')
start=t.find('        Map<String, String> headers = new HashMap<>();',pos)
if start>=0:
    end=t.find('        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()',start)
    if end<0: raise SystemExit('compat-v1: default header block end missing')
    t=t[:start]+'        Map<String, String> headers = requestHeaders(referer);\n'+t[end:]
old='''                return new DefaultDataSource.Factory(\n                        context,\n                        new CronetDataSource.Factory(engine, executor).setUserAgent(userAgent));'''
new='''                Map<String, String> headers = requestHeaders(referer);\n                CronetDataSource.Factory cronet = new CronetDataSource.Factory(engine, executor)\n                        .setUserAgent(userAgent)\n                        .setConnectionTimeoutMs(connectTimeoutMs)\n                        .setReadTimeoutMs(readTimeoutMs)\n                        .setResetTimeoutOnRedirects(true)\n                        .setDefaultRequestProperties(headers);\n                Log.i(TAG, "transport=cronet-gms headers=parity connect=" + connectTimeoutMs\n                        + " read=" + readTimeoutMs + " profile=" + compatibilityProfile);\n                return new DefaultDataSource.Factory(context, cronet);'''
if old in t: t=t.replace(old,new,1)
elif 'transport=cronet-gms headers=parity' not in t: raise SystemExit('compat-v1: cronet anchor missing')
T.write_text(t,encoding='utf-8')
# Provider-scoped compatibility memory; no network calls, no impact until used by later ladders.
M=JAVA/'ProviderCompatibilityMemory.java'
M.write_text('''package tv.blofy.player;\n\nimport android.content.Context;\nimport android.net.Uri;\n\n/** Provider-scoped memory for compatibility choices. Never leaks one provider into another. */\nfinal class ProviderCompatibilityMemory {\n    private static final String PREFS="blofy_provider_compat_v1";\n    private ProviderCompatibilityMemory(){}\n    static String providerKey(String url){\n        if(url==null||url.isEmpty())return "unknown";\n        try{Uri u=Uri.parse(url);String h=u.getHost();int p=u.getPort();return h==null?"unknown":h.toLowerCase()+(p>0?":"+p:"");}\n        catch(Exception ignored){return "unknown";}\n    }\n    static int profile(Context c,String url){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt("profile:"+providerKey(url),0);}\n    static void rememberProfile(Context c,String url,int profile){if(profile<0||profile>2)return;c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt("profile:"+providerKey(url),profile).apply();}\n    static String route(Context c,String url){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("route:"+providerKey(url),"");}\n    static void rememberRoute(Context c,String url,String route){if(route==null||route.isEmpty())return;c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("route:"+providerKey(url),route).apply();}\n}\n''',encoding='utf-8')
g=G.read_text(encoding='utf-8'); g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000364',g,count=1); g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-vision-stable-compat-v1"',g,count=1); G.write_text(g,encoding='utf-8')
for marker in ['requestHeaders(String referer)', '.setConnectionTimeoutMs(connectTimeoutMs)', '.setReadTimeoutMs(readTimeoutMs)', '.setResetTimeoutOnRedirects(true)', '.setDefaultRequestProperties(headers)', 'headers=parity']:
    if marker not in T.read_text(encoding='utf-8'): raise SystemExit('compat-v1 missing '+marker)
if 'providerKey(String url)' not in M.read_text(encoding='utf-8'): raise SystemExit('compat-v1 memory missing')
print('BLOFY universal compatibility v1 applied: HTTP/Cronet parity + provider-scoped compatibility memory')
