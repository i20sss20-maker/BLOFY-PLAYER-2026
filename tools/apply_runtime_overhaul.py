from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')


def req(text, old, new, label):
    if old not in text:
        raise SystemExit(f'{label}: pattern not found')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# Package sync: keep partial progress and retry transient network failures.
# ---------------------------------------------------------------------------
p = ROOT / 'PackageImporter.java'
s = p.read_text(encoding='utf-8')
s = req(s,
'''        database.beginFreshImport();
        database.putMetadata("sync_state", "in_progress");''',
'''        String previousSync = database.metadata("sync_state", "");
        if (!"partial".equals(previousSync) && !"in_progress".equals(previousSync)) {
            database.beginFreshImport();
        }
        database.putMetadata("sync_state", "in_progress");''', 'resume sync start')
s = req(s,
'''        } catch (Exception error) {
            database.beginFreshImport();
            database.putMetadata("sync_state", "failed");
            throw error;
        }''',
'''        } catch (Exception error) {
            // Keep successfully imported pages. A retry resumes idempotently instead
            // of deleting hundreds of thousands of rows and starting from zero.
            database.putMetadata("sync_state", "partial");
            throw error;
        }''', 'keep partial sync')
old_retry = '''            } catch (BlofyApi.ApiException error) {
                boolean retryable = error.status == 429 || error.status == 502
                        || error.status == 503 || error.status == 504;
                if (!retryable || attempt >= delays.length) throw error;
                emitRetry(path, error.status, attempt + 1);
                Thread.sleep(delays[attempt]);
            }'''
new_retry = '''            } catch (Exception error) {
                int status = error instanceof BlofyApi.ApiException
                        ? ((BlofyApi.ApiException) error).status : 0;
                boolean retryable = status == 0 || status == 408 || status == 429
                        || status == 500 || status == 502 || status == 503 || status == 504;
                if (!retryable || attempt >= delays.length) throw error;
                emitRetry(path, status, attempt + 1);
                Thread.sleep(delays[attempt]);
            }'''
s = req(s, old_retry, new_retry, 'network retry')
s = s.replace('"استجابة " + status + " • محاولة " + attempt + " تلقائيًا"',
              '(status == 0 ? "مهلة/شبكة" : "استجابة " + status) + " • محاولة " + attempt + " تلقائيًا"')
p.write_text(s, encoding='utf-8')

# ---------------------------------------------------------------------------
# Playback: Live and VOD use different strategies.
# - VOD starts with platform HTTP and lets Media3 sniff the real container.
# - Live uses a small startup/rebuffer target and no giant 120s memory buffer.
# ---------------------------------------------------------------------------
p = ROOT / 'PlayerActivity.java'
s = p.read_text(encoding='utf-8')
s = req(s,
'''    private boolean useCronetNow() {
        return PlaybackPolicy.useCronet(recoveryStep);
    }''',
'''    private boolean useCronetNow() {
        // Many Xtream VOD endpoints depend on Range/redirect behaviour that is
        // most compatible with the platform HTTP stack. Cronet remains available
        // for Live where it proved useful on restricted/provider networks.
        return isLive() && PlaybackPolicy.useCronet(recoveryStep);
    }''', 'vod transport')
s = req(s,
'''        playbackHandler.postDelayed(
                playbackTimeout,
                PlaybackPolicy.startupTimeoutMs(recoveryStep));''',
'''        playbackHandler.postDelayed(
                playbackTimeout,
                isLive() ? (recoveryStep == 0 ? 5_500L : 8_000L) : 15_000L);''', 'startup timeout')
# final stabilization load-control block
s = req(s,
'''        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        isLive() ? 12_000 : 20_000,
                        isLive() ? 120_000 : 75_000,
                        isLive() ? 1_200 : 1_500,
                        isLive() ? 6_000 : 4_000)
                .setBackBuffer(isLive() ? 15_000 : 30_000, false)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();''',
'''        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        isLive() ? 3_000 : 8_000,
                        isLive() ? 24_000 : 48_000,
                        isLive() ? 450 : 700,
                        isLive() ? 1_600 : 2_000)
                .setBackBuffer(isLive() ? 3_000 : 20_000, false)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();''', 'fast load control')
s = req(s,
'''        String mimeType = PlaybackPolicy.mimeType(extension);
        if (mimeType != null) itemBuilder.setMimeType(mimeType);''',
'''        String mimeType = PlaybackPolicy.mimeType(extension);
        // Do not force a VOD MIME type from Xtream's filename extension. A number
        // of providers expose MKV/MP4 URLs whose actual response/container differs.
        // Media3 sniffing is more reliable and begins playback sooner.
        if (isLive() && mimeType != null) itemBuilder.setMimeType(mimeType);''', 'vod sniffing')
# buffering watchdog after stabilization: avoid repeatedly re-arming a 15s timeout on VOD
s = s.replace('playbackHandler.postDelayed(playbackTimeout, isLive() ? 15_000L : 25_000L);',
              'playbackHandler.postDelayed(playbackTimeout, isLive() ? 9_000L : 18_000L);')
p.write_text(s, encoding='utf-8')

# ---------------------------------------------------------------------------
# TV UI: local DB is complete, but only bind small pages at a time.
# Provider order is supplied by CatalogDatabase.sort_order.
# ---------------------------------------------------------------------------
p = ROOT / 'SevenMaxActivity.java'
s = p.read_text(encoding='utf-8')
# imports for preview player controller do not require Media3 imports here.
s = req(s,
'''    private String screen = "home";''',
'''    private String screen = "home";
    private LivePreviewController livePreview;''', 'preview field')

# Replace static logo preview with actual muted mini player.
old_preview = '''        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        preview.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));'''
new_preview = '''        livePreview = new LivePreviewController(this);
        preview.addView(livePreview.view(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));'''
s = req(s, old_preview, new_preview, 'live preview view')
s = req(s,
'''            images.load(logo, item.image);
            play.setEnabled(true);''',
'''            livePreview.preview(item);
            play.setEnabled(true);''', 'live preview selection')

# Add paging listeners after adapters are attached.
s = req(s,
'''        channels.setAdapter(liveAdapter);''',
'''        channels.setAdapter(liveAdapter);
        channels.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= liveAdapter.getItemCount() - 18) liveAdapter.loadMore();
            }
        });''', 'live paging listener')
s = req(s,
'''        media.setAdapter(adapter);
        LinearLayout.LayoutParams mp''',
'''        media.setAdapter(adapter);
        media.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 20) adapter.loadMore();
            }
        });
        LinearLayout.LayoutParams mp''', 'poster paging listener')

old_live_adapter = '''    private final class LiveListAdapter extends RecyclerView.Adapter<LiveListAdapter.H>{
        List<BlofyModels.Media> rows=new ArrayList<>(); String category=""; LiveListener listener;
        void reload(String c,String q){category=c==null?"":c; rows=database.media("live",category,q==null?"":q,false,false,5000,0); notifyDataSetChanged();}
        @Override public H onCreateViewHolder(ViewGroup p,int t){Button b=BlofyUi.button(p.getContext(),"",false); b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); b.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52))); return new H(b);}
        @Override public void onBindViewHolder(H h,int pos){BlofyModels.Media m=rows.get(pos); h.b.setText((pos+1)+"   "+m.name); h.b.setOnFocusChangeListener((v,f)->{if(f&&listener!=null)listener.selected(m);}); h.b.setOnClickListener(v->play(m));}
        @Override public int getItemCount(){return rows.size();} final class H extends RecyclerView.ViewHolder{Button b;H(Button x){super(x);b=x;}}
    }'''
new_live_adapter = '''    private final class LiveListAdapter extends RecyclerView.Adapter<LiveListAdapter.H>{
        static final int PAGE=140; List<BlofyModels.Media> rows=new ArrayList<>(); String category="",query=""; boolean end;
        LiveListener listener;
        void reload(String c,String q){ category=c==null?"":c; query=q==null?"":q; end=false; rows=new ArrayList<>(); loadMore(); }
        void loadMore(){ if(end)return; List<BlofyModels.Media> more=database.media("live",category,query,false,false,PAGE,rows.size()); if(more.size()<PAGE)end=true; if(more.isEmpty())return; int at=rows.size(); rows.addAll(more); notifyItemRangeInserted(at,more.size()); }
        @Override public H onCreateViewHolder(ViewGroup p,int t){Button b=BlofyUi.button(p.getContext(),"",false); b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); b.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52))); return new H(b);}
        @Override public void onBindViewHolder(H h,int pos){BlofyModels.Media m=rows.get(pos); h.b.setText((pos+1)+"   "+m.name); h.b.setOnFocusChangeListener((v,f)->{if(f&&listener!=null)listener.selected(m);}); h.b.setOnClickListener(v->play(m));}
        @Override public int getItemCount(){return rows.size();} final class H extends RecyclerView.ViewHolder{Button b;H(Button x){super(x);b=x;}}
    }'''
s = req(s, old_live_adapter, new_live_adapter, 'live paged adapter')

old_poster_head = '''    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.H>{
        final String type; final boolean fav,hist; String category=""; List<BlofyModels.Media> rows=new ArrayList<>();
        PosterAdapter(String t,boolean f,boolean h){type=t;fav=f;hist=h;}
        void reload(String c,String q){category=c==null?"":c; rows=database.media(type,category,q==null?"":q,fav,hist,5000,0); notifyDataSetChanged();}'''
new_poster_head = '''    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.H>{
        static final int PAGE=90; final String type; final boolean fav,hist; String category="",query=""; boolean end; List<BlofyModels.Media> rows=new ArrayList<>();
        PosterAdapter(String t,boolean f,boolean h){type=t;fav=f;hist=h;}
        void reload(String c,String q){category=c==null?"":c; query=q==null?"":q; end=false; rows=new ArrayList<>(); loadMore();}
        void loadMore(){ if(end)return; List<BlofyModels.Media> more=database.media(type,category,query,fav,hist,PAGE,rows.size()); if(more.size()<PAGE)end=true; if(more.isEmpty())return; int at=rows.size(); rows.addAll(more); notifyItemRangeInserted(at,more.size()); }'''
s = req(s, old_poster_head, new_poster_head, 'poster paged adapter')

# Fill settings with useful runtime data instead of an almost empty panel.
s = s.replace('panel.addView(BlofyUi.text(this, "طريقة التشغيل: " + database.metadata("playback_profile", "Media3 + Cronet"), 15, BlofyUi.MUTED));',
'''panel.addView(BlofyUi.text(this, "طريقة التشغيل: Live مباشر + VOD HTTP/Range + Media3/FFmpeg", 15, BlofyUi.MUTED));
        panel.addView(BlofyUi.text(this, "ترتيب القوائم: نفس ترتيب السيرفر (بدون فرز أبجدي)", 15, BlofyUi.MUTED));
        panel.addView(BlofyUi.text(this, "التحميل: صفحات خفيفة Lazy Loading لتسريع الحركة", 15, BlofyUi.MUTED));
        panel.addView(BlofyUi.text(this, "الصوت: Media3 + FFmpeg Audio Decoder", 15, BlofyUi.MUTED));''')

# release preview with the screen/activity.
s = s.replace('    @Override protected void onDestroy(){ database.close(); super.onDestroy(); }',
'''    @Override protected void onDestroy(){ if(livePreview!=null)livePreview.release(); database.close(); super.onDestroy(); }''')
p.write_text(s, encoding='utf-8')

print('BLOFY runtime overhaul applied: provider order, paging, live preview, VOD sniffing, resumable sync')
