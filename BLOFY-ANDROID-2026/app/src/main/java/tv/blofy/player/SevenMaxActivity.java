package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SevenMaxActivity extends Activity {
    private FrameLayout root;
    private CatalogDatabase database;
    private ImageLoader images;
    private BlofyApi api;
    private String screen = "home";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BlofyUi.BLACK);
        getWindow().setNavigationBarColor(BlofyUi.BLACK);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        database = new CatalogDatabase(this);
        api = new BlofyApi(this);
        images = new ImageLoader(api);
        showHome();
    }

    private void showHome() {
        screen = "home";
        root.removeAllViews();
        LinearLayout page = shell();

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        body.setPadding(dp(36), dp(12), dp(36), dp(28));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER);
        addSideButton(side, "⚙  الإعدادات", () -> openLegacySettings());
        addSideButton(side, "↻  تحديث الباقة", () -> openLegacyRefresh());
        addSideButton(side, "⌕  البحث", () -> showCatalog("movies", true));
        addSideButton(side, "✕  خروج", this::finishAffinity);
        body.addView(side, new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.WRAP_CONTENT));

        GridLayout menu = new GridLayout(this);
        menu.setColumnCount(3);
        menu.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        addMenuCard(menu, "▣", "مسلسلات", database.count("series") + " مسلسل", () -> showCatalog("series", false), 1);
        addMenuCard(menu, "🎬", "أفلام", database.count("movies") + " فيلم", () -> showCatalog("movies", false), 1);
        addMenuCard(menu, "📡", "بث مباشر", database.count("live") + " قناة", () -> showLive(), 2);
        addMenuCard(menu, "★", "المفضلة", "محتواك المحفوظ", () -> showFavorites(), 1);
        addMenuCard(menu, "◷", "سجل المشاهدة", "تابع من حيث توقفت", () -> showHistory(), 1);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        menuParams.setMargins(dp(24), 0, dp(24), 0);
        body.addView(menu, menuParams);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());
        focusFirst();
    }

    private LinearLayout shell() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(26), dp(12), dp(26), dp(10));
        top.setBackground(BlofyUi.panel(this, Color.rgb(18,18,20), 18, Color.rgb(47,47,53)));
        TextView expiry = BlofyUi.text(this, "BLOFY PLAYER  •  " + database.metadata("server_name", "Playlist"), 13, BlofyUi.MUTED);
        top.addView(expiry, new LinearLayout.LayoutParams(0, dp(64), 1));
        top.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(250), dp(72)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        tp.setMargins(dp(24), dp(16), dp(24), dp(6));
        page.addView(top, tp);
        return page;
    }

    private void showLive() {
        screen = "live";
        root.removeAllViews();
        LinearLayout page = shell();

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(24), 0, dp(24), dp(8));
        TextView title = BlofyUi.title(this, "بث مباشر", 19);
        tabs.addView(title, new LinearLayout.LayoutParams(dp(180), dp(54)));
        EditText search = BlofyUi.input(this, "بحث باسم أو رقم القناة", false);
        tabs.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button back = BlofyUi.button(this, "رجوع", false);
        back.setOnClickListener(v -> showHome());
        tabs.addView(back, new LinearLayout.LayoutParams(dp(120), dp(52)));
        page.addView(tabs);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setGravity(Gravity.TOP);
        columns.setPadding(dp(18), 0, dp(18), dp(18));

        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        List<BlofyModels.Category> categoryRows = new ArrayList<>();
        categoryRows.add(new BlofyModels.Category("", "ALL  •  " + database.count("live"), "live"));
        categoryRows.addAll(database.categories("live"));
        CategoryListAdapter catAdapter = new CategoryListAdapter(categoryRows);
        cats.setAdapter(catAdapter);
        cats.setBackground(BlofyUi.panel(this, Color.rgb(16,16,18), 6, Color.rgb(55,55,60)));
        columns.addView(cats, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

        RecyclerView channels = new RecyclerView(this);
        channels.setLayoutManager(new LinearLayoutManager(this));
        LiveListAdapter liveAdapter = new LiveListAdapter();
        channels.setAdapter(liveAdapter);
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(dp(410), ViewGroup.LayoutParams.MATCH_PARENT);
        channelParams.setMargins(dp(8),0,dp(8),0);
        columns.addView(channels, channelParams);

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(18),dp(16),dp(18),dp(16));
        preview.setBackground(BlofyUi.panel(this, Color.rgb(17,17,20), 6, Color.rgb(55,55,60)));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        preview.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        TextView name = BlofyUi.title(this, "اختر قناة", 23);
        preview.addView(name);
        TextView epg = BlofyUi.text(this, "معلومات القناة والبرنامج الحالي تظهر هنا", 14, BlofyUi.MUTED);
        preview.addView(epg);
        Button play = BlofyUi.button(this, "▶  تشغيل", true);
        play.setEnabled(false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        pp.topMargin = dp(18);
        preview.addView(play, pp);
        columns.addView(preview, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        final BlofyModels.Media[] selected = {null};
        liveAdapter.listener = item -> {
            selected[0] = item;
            name.setText(item.name);
            epg.setText((item.extension == null ? "" : item.extension.toUpperCase(Locale.US)) + "  •  BLOFY Direct");
            images.load(logo, item.image);
            play.setEnabled(true);
            play.setOnClickListener(v -> play(item));
        };
        catAdapter.listener = c -> liveAdapter.reload(c.id, search.getText().toString());
        search.setOnEditorActionListener((v,a,e) -> { liveAdapter.reload(liveAdapter.category, search.getText().toString()); return true; });

        page.addView(columns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());
        liveAdapter.reload("", "");
        cats.requestFocus();
    }

    private void showCatalog(String type, boolean focusSearch) {
        screen = type;
        root.removeAllViews();
        LinearLayout page = shell();
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(24), 0, dp(24), dp(8));
        TextView title = BlofyUi.title(this, "series".equals(type) ? "مسلسلات" : "أفلام", 20);
        toolbar.addView(title, new LinearLayout.LayoutParams(dp(170), dp(54)));
        EditText search = BlofyUi.input(this, "بحث", false);
        toolbar.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button back = BlofyUi.button(this, "رجوع", false);
        back.setOnClickListener(v -> showHome());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(120), dp(52)));
        page.addView(toolbar);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(18), 0, dp(18), dp(18));
        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        List<BlofyModels.Category> rows = new ArrayList<>();
        rows.add(new BlofyModels.Category("", "ALL", type));
        rows.addAll(database.categories(type));
        CategoryListAdapter catAdapter = new CategoryListAdapter(rows);
        cats.setAdapter(catAdapter);
        cats.setBackground(BlofyUi.panel(this, Color.rgb(16,16,18), 6, Color.rgb(55,55,60)));
        body.addView(cats, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this, 5));
        PosterAdapter adapter = new PosterAdapter(type, false, false);
        media.setAdapter(adapter);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        mp.setMargins(dp(12),0,0,0);
        body.addView(media, mp);
        catAdapter.listener = c -> adapter.reload(c.id, search.getText().toString());
        search.setOnEditorActionListener((v,a,e) -> { adapter.reload(adapter.category, search.getText().toString()); return true; });
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());
        adapter.reload("", "");
        if (focusSearch) search.requestFocus(); else cats.requestFocus();
    }

    private void showFavorites() { showSpecial(true, false, "المفضلة"); }
    private void showHistory() { showSpecial(false, true, "سجل المشاهدة"); }

    private void showSpecial(boolean fav, boolean hist, String titleText) {
        screen = "special";
        root.removeAllViews();
        LinearLayout page = shell();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = BlofyUi.title(this, titleText, 20);
        bar.addView(title, new LinearLayout.LayoutParams(0,dp(56),1));
        Button back = BlofyUi.button(this,"رجوع",false); back.setOnClickListener(v->showHome());
        bar.addView(back,new LinearLayout.LayoutParams(dp(120),dp(52)));
        page.addView(bar);
        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this,5));
        PosterAdapter adapter = new PosterAdapter("",fav,hist); media.setAdapter(adapter); adapter.reload("","");
        page.addView(media,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        root.addView(page,match());
        media.requestFocus();
    }

    private void play(BlofyModels.Media item) {
        database.addHistory(item.type,item.id);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ID,item.id);
        i.putExtra(PlayerActivity.EXTRA_TITLE,item.name);
        i.putExtra(PlayerActivity.EXTRA_KIND,item.type);
        i.putExtra(PlayerActivity.EXTRA_EXTENSION,item.extension);
        startActivity(i);
    }

    private void openDetails(BlofyModels.Media item) {
        Intent i = new Intent(this, DetailsActivity.class);
        i.putExtra(DetailsActivity.EXTRA_ITEM,item.json().toString());
        startActivity(i);
    }

    private void openLegacySettings() { ToastBridge.show(this,"الإعدادات الجديدة بتدخل في نفس الثيم بالنسخة التالية"); }
    private void openLegacyRefresh() { ToastBridge.show(this,"تحديث الباقة من شاشة الدخول الحالية مؤقتًا"); }

    private void addMenuCard(GridLayout grid,String icon,String title,String sub,Runnable action,int span) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER); card.setPadding(dp(18),dp(14),dp(18),dp(14));
        card.setFocusable(true); card.setClickable(true); card.setBackground(BlofyUi.focusDrawable(this,Color.rgb(35,35,39),Color.rgb(65,47,91),Color.WHITE));
        TextView iv=BlofyUi.title(this,icon,34); iv.setGravity(Gravity.CENTER); card.addView(iv);
        TextView tv=BlofyUi.title(this,title,20); tv.setGravity(Gravity.CENTER); card.addView(tv);
        TextView sv=BlofyUi.text(this,sub,12,BlofyUi.MUTED); sv.setGravity(Gravity.CENTER); card.addView(sv);
        card.setOnClickListener(v->action.run());
        GridLayout.LayoutParams p=new GridLayout.LayoutParams(); p.width=0; p.height=dp(span==2?190:145); p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,span,1f); p.setMargins(dp(6),dp(6),dp(6),dp(6)); grid.addView(card,p);
    }

    private void addSideButton(LinearLayout side,String text,Runnable action){ Button b=BlofyUi.button(this,text,false); b.setOnClickListener(v->action.run()); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(62)); p.setMargins(0,dp(6),0,dp(6)); side.addView(b,p); }
    private void focusFirst(){ ArrayList<View> vs=new ArrayList<>(); root.addFocusables(vs,View.FOCUS_FORWARD); if(!vs.isEmpty())vs.get(0).requestFocus(); }
    private int dp(int v){ return BlofyUi.dp(this,v); }
    private FrameLayout.LayoutParams match(){ return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT); }

    @Override public void onBackPressed(){ if("home".equals(screen)) finishAffinity(); else showHome(); }
    @Override protected void onDestroy(){ database.close(); super.onDestroy(); }

    private interface CategoryListener{void selected(BlofyModels.Category c);} private interface LiveListener{void selected(BlofyModels.Media m);}

    private final class CategoryListAdapter extends RecyclerView.Adapter<CategoryListAdapter.H>{
        final List<BlofyModels.Category> rows; CategoryListener listener; CategoryListAdapter(List<BlofyModels.Category> r){rows=r;}
        @Override public H onCreateViewHolder(ViewGroup p,int t){Button b=BlofyUi.button(p.getContext(),"",false); b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); b.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(50))); return new H(b);} 
        @Override public void onBindViewHolder(H h,int pos){BlofyModels.Category c=rows.get(pos); h.b.setText(c.name); h.b.setOnClickListener(v->{if(listener!=null)listener.selected(c);});}
        @Override public int getItemCount(){return rows.size();} final class H extends RecyclerView.ViewHolder{Button b;H(Button x){super(x);b=x;}}
    }

    private final class LiveListAdapter extends RecyclerView.Adapter<LiveListAdapter.H>{
        List<BlofyModels.Media> rows=new ArrayList<>(); String category=""; LiveListener listener;
        void reload(String c,String q){category=c==null?"":c; rows=database.media("live",category,q==null?"":q,false,false,5000,0); notifyDataSetChanged();}
        @Override public H onCreateViewHolder(ViewGroup p,int t){Button b=BlofyUi.button(p.getContext(),"",false); b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); b.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52))); return new H(b);}
        @Override public void onBindViewHolder(H h,int pos){BlofyModels.Media m=rows.get(pos); h.b.setText((pos+1)+"   "+m.name); h.b.setOnFocusChangeListener((v,f)->{if(f&&listener!=null)listener.selected(m);}); h.b.setOnClickListener(v->play(m));}
        @Override public int getItemCount(){return rows.size();} final class H extends RecyclerView.ViewHolder{Button b;H(Button x){super(x);b=x;}}
    }

    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.H>{
        final String type; final boolean fav,hist; String category=""; List<BlofyModels.Media> rows=new ArrayList<>();
        PosterAdapter(String t,boolean f,boolean h){type=t;fav=f;hist=h;}
        void reload(String c,String q){category=c==null?"":c; rows=database.media(type,category,q==null?"":q,fav,hist,5000,0); notifyDataSetChanged();}
        @Override public H onCreateViewHolder(ViewGroup p,int t){LinearLayout card=new LinearLayout(p.getContext()); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER); card.setFocusable(true); card.setClickable(true); card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,Color.rgb(27,27,31),Color.rgb(62,45,89),Color.WHITE)); ImageView im=new ImageView(p.getContext()); im.setScaleType(ImageView.ScaleType.CENTER_CROP); card.addView(im,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(180))); TextView n=BlofyUi.title(p.getContext(),"",13); n.setGravity(Gravity.CENTER); n.setMaxLines(2); card.addView(n,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48))); RecyclerView.LayoutParams rp=new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); rp.setMargins(dp(5),dp(5),dp(5),dp(5)); card.setLayoutParams(rp); return new H(card,im,n);}
        @Override public void onBindViewHolder(H h,int pos){BlofyModels.Media m=rows.get(pos); h.n.setText(m.name); images.load(h.im,m.image); h.card.setOnClickListener(v->{if("live".equals(m.type))play(m); else openDetails(m);}); h.card.setOnLongClickListener(v->{database.toggleFavorite(m.type,m.id); return true;});}
        @Override public int getItemCount(){return rows.size();} final class H extends RecyclerView.ViewHolder{LinearLayout card;ImageView im;TextView n;H(LinearLayout c,ImageView i,TextView x){super(c);card=c;im=i;n=x;}}
    }
}
