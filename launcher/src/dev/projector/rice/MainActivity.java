package dev.projector.rice;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HOME app with signature-protected VPN control; no root or shell execution. */
public final class MainActivity extends Activity {
    private int BG=Palette.BACKGROUND,SURFACE=Palette.SURFACE;
    private static final int LINE=Palette.BORDER,TEXT=Palette.TEXT,MUTED=Palette.MUTED,SELECTION=Palette.SELECTION;
    private static final String STOCK="com.chihihx.launcher";
    private static final String YOUTUBE="io.gh.reisxd.tizentube.cobalt";
    private static final String OLD_YOUTUBE="com.google.android.youtube.tv";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<App> apps=new ArrayList<>();
    private final ArrayList<String> pins=new ArrayList<>();
    private final ArrayList<View> focusTiles=new ArrayList<>();
    private SharedPreferences prefs;
    private float scale=1;
    private int accent=Palette.ACCENT;
    private boolean appPage=false, resumed=false, loading=false;
    private App focusedApp;
    private TextView clock, date, greeting, connection;
    private FrameLayout content;
    private View homeNav,appsNav,settingsNav;
    private TextView vpnButton;
    private VpnController vpn;

    private static final class App {
        final ComponentName component;
        final String label;
        App(ComponentName c,String l){component=c;label=YOUTUBE.equals(c.getPackageName())?"YouTube":"io.nekohasekai.sfa".equals(c.getPackageName())?"Focal VPN":l;}
        String key(){return component.flattenToString();}
    }
    private final Runnable tick=new Runnable(){public void run(){
        updateClock();
        // Minute precision needs no continuous redraws on a low-power projector.
        if(resumed) handler.postDelayed(this,Math.max(1000,60000-System.currentTimeMillis()%60000));
    }};
    private final BroadcastReceiver changed=new BroadcastReceiver(){
        public void onReceive(Context context,Intent intent){loadApps();}
    };

    @Override public void onCreate(Bundle state){
        Palette.initialize(this);setTheme(Palette.oled(this)?R.style.AppThemeOLED:R.style.AppTheme);
        super.onCreate(state);
        BG=Palette.background(this);SURFACE=Palette.surface(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        float w=getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density;
        float h=getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density;
        scale=Math.min(w/1280f,h/720f);
        prefs=getSharedPreferences("rice",MODE_PRIVATE);
        RemoteService.start(this);
        vpn=new VpnController(this,this::renderVpn);
        accent=prefs.getInt("accent",accent);
        try{JSONArray a=new JSONArray(prefs.getString("pins","[]"));
            for(int i=0;i<a.length();i++) pins.add(a.getString(i));
        }catch(Exception ignored){pins.clear();}
        appPage=state!=null && state.getBoolean("apps");
        build();
        if(RemoteSettings.ACTION.equals(getIntent().getAction())){getIntent().setAction(Intent.ACTION_MAIN);handler.post(()->RemoteSettings.show(this));}
    }
    @Override protected void onResume(){super.onResume();resumed=true;handler.removeCallbacks(tick);tick.run();loadApps();vpn.resume();}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(tick);vpn.pause();super.onPause();}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);f.addDataScheme("package");registerReceiver(changed,f);}
    @Override protected void onStop(){unregisterReceiver(changed);super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();vpn.close();handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle state){state.putBoolean("apps",appPage);super.onSaveInstanceState(state);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);
        if(intent.hasCategory(Intent.CATEGORY_HOME)){appPage=false;build();}
        if(RemoteSettings.ACTION.equals(intent.getAction())){intent.setAction(Intent.ACTION_MAIN);handler.post(()->RemoteSettings.show(this));}}

    private int px(float n){return Math.round(n*scale*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String value,float size,int color,boolean mono){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP,size*scale);
        t.setTypeface(mono?Typeface.create("monospace",Typeface.NORMAL):Typeface.create("sans-serif",Typeface.NORMAL));
        t.setIncludeFontPadding(false);return t;
    }
    private GradientDrawable surface(int fill,int border){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(px(8));if(border!=0)d.setStroke(px(1),border);return d;}
    private void gap(LinearLayout l,int size,boolean horizontal){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(horizontal?px(size):1,horizontal?1:px(size)));}
    private void spacer(LinearLayout l){l.addView(new View(this),new LinearLayout.LayoutParams(1,1,1));}

    private TextView button(String label,String tag,Runnable action,boolean selected){
        TextView t=text(label,16,selected?accent:MUTED,true);t.setTag(tag);
        t.setId(View.generateViewId());t.setMinHeight(px(48));
        t.setGravity(Gravity.CENTER);t.setPadding(px(18),px(12),px(18),px(12));
        t.setFocusable(true);t.setClickable(true);
        boolean boxed=tag.startsWith("@fav");
        t.setBackground(surface(boxed||selected?SURFACE:Color.TRANSPARENT,boxed?LINE:0));
        t.setOnClickListener(v->action.run());
        t.setOnFocusChangeListener((v,focus)->{focusedApp=null;t.setTextColor(focus?TEXT:selected?accent:MUTED);
            t.setBackground(surface(focus?SELECTION:boxed||selected?SURFACE:Color.TRANSPARENT,focus?accent:boxed?LINE:0));});
        return t;
    }

    private void build(){
        Object oldTag=getCurrentFocus()==null?null:getCurrentFocus().getTag();
        focusedApp=null;clock=null;date=null;greeting=null;focusTiles.clear();
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);
        LinearLayout layout=column();layout.setPadding(px(56),px(34),px(56),px(28));
        root.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=row();
        TextView brand=text("_VeR0",25,TEXT,true);bar.addView(brand,new LinearLayout.LayoutParams(px(207),-2));
        homeNav=button("01  главная","@home",()->{appPage=false;build();},!appPage);bar.addView(homeNav);gap(bar,8,true);
        appsNav=button("02  приложения","@apps",()->{appPage=true;build();},appPage);bar.addView(appsNav);
        spacer(bar);connection=text("",13,MUTED,true);bar.addView(connection);gap(bar,12,true);
        vpnButton=button(vpn.label(),"@vpn",vpn::toggle,false);vpnButton.setWidth(px(156));
        vpnButton.setOnFocusChangeListener((v,focus)->{focusedApp=null;renderVpn();});
        vpnButton.setOnLongClickListener(v->{vpn.openClient();return true;});bar.addView(vpnButton);gap(bar,8,true);
        settingsNav=button("настройки","@settings",this::settings,false);bar.addView(settingsNav);
        layout.addView(bar,new LinearLayout.LayoutParams(-1,px(52)));gap(layout,18,false);
        View rule=new View(this);rule.setBackgroundColor(LINE);layout.addView(rule,new LinearLayout.LayoutParams(-1,px(1)));
        content=new FrameLayout(this);layout.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if(appPage) buildApps();else buildHome();
        wireFocus();
        View bottomRule=new View(this);bottomRule.setBackgroundColor(LINE);layout.addView(bottomRule,new LinearLayout.LayoutParams(-1,px(1)));
        gap(layout,18,false);
        LinearLayout footer=row();footer.addView(text("↑ ↓ ← →  навигация     OK  открыть     MENU  избранное",13,MUTED,true));
        spacer(footer);footer.addView(text(appPage?String.format(Locale.ROOT,"%02d приложений",apps.size()):"меньше шума. больше кино.",13,MUTED,true));
        layout.addView(footer,new LinearLayout.LayoutParams(-1,px(22)));
        setContentView(root);updateClock();renderVpn();
        final Object restore=oldTag;
        root.post(()->{View focus=restore==null?null:root.findViewWithTag(restore);
            if(focus==null)focus=root.findViewWithTag(apps.isEmpty()?"@apps":appPage?apps.get(0).key():"@fav0");
            if(focus==null)focus=root.findViewWithTag("@home");if(focus!=null)focus.requestFocus();});
    }

    private void buildHome(){
        LinearLayout home=column();home.setPadding(0,px(24),0,px(26));content.addView(home,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout hero=new FrameLayout(this);home.addView(hero,new LinearLayout.LayoutParams(-1,0,1));
        SolarSystemView art=new SolarSystemView(this,accent);FrameLayout.LayoutParams artLp=new FrameLayout.LayoutParams(px(530),-1,Gravity.RIGHT|Gravity.CENTER_VERTICAL);hero.addView(art,artLp);
        LinearLayout time=column();time.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams timeLp=new FrameLayout.LayoutParams(px(600),-1);hero.addView(time,timeLp);
        date=text("",15,accent,true);date.setLetterSpacing(.12f);time.addView(date);gap(time,18,false);
        clock=text("",108,TEXT,true);clock.setLetterSpacing(-.065f);time.addView(clock);gap(time,20,false);
        greeting=text("",21,MUTED,false);time.addView(greeting);
        gap(home,12,false);LinearLayout heading=row();heading.addView(text("ИЗБРАННОЕ",13,MUTED,true));
        spacer(heading);heading.addView(text("удерживай OK, чтобы изменить",13,MUTED,true));home.addView(heading);gap(home,14,false);
        LinearLayout favorites=row();
        for(int i=0;i<4;i++){
            if(i>0)gap(favorites,12,true);
            App app=i<pins.size()?find(pins.get(i)):null;
            View tile;
            if(app!=null){tile=tile(app,i+1,true);tile.setTag("@fav"+i);}
            else{tile=button("+  добавить","@fav"+i,()->{appPage=true;build();},false);tile.setBackground(surface(SURFACE,LINE));}
            favorites.addView(tile,new LinearLayout.LayoutParams(0,px(100),1));
            focusTiles.add(tile);
        }
        home.addView(favorites);
    }

    private View tile(App app,int index,boolean favorite){
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(px(20),px(17),px(20),px(17));
        tile.setFocusable(true);tile.setClickable(true);tile.setLongClickable(true);tile.setTag(app.key());
        tile.setId(View.generateViewId());
        tile.setContentDescription(app.label+(pins.contains(app.key())?", в избранном":""));
        tile.setBackground(surface(SURFACE,LINE));
        LinearLayout line=row();TextView number=text(String.format(Locale.ROOT,"%02d",index),12,MUTED,true);line.addView(number);
        spacer(line);TextView arrow=text("↗",18,MUTED,true);line.addView(arrow);tile.addView(line);gap(tile,12,false);
        TextView label=text(app.label,21,TEXT,false);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);tile.addView(label,new LinearLayout.LayoutParams(-1,-2));
        tile.setOnFocusChangeListener((v,focus)->{if(focus)focusedApp=app;
            tile.setBackground(surface(focus?SELECTION:SURFACE,focus?accent:LINE));
            number.setTextColor(focus?accent:MUTED);arrow.setTextColor(focus?accent:MUTED);});
        tile.setOnClickListener(v->launch(app));tile.setOnLongClickListener(v->{pinMenu(tile,app);return true;});return tile;
    }

    private void buildApps(){
        LinearLayout page=column();page.setPadding(0,px(28),0,px(22));content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout title=row();title.addView(text("Приложения",32,TEXT,false));spacer(title);
        title.addView(text("MENU / долгое OK — закрепить",13,MUTED,true));page.addView(title);gap(page,24,false);
        if(apps.isEmpty()){page.addView(text(loading?"Загружаем приложения…":"Нет приложений для запуска",20,MUTED,false));return;}
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout grid=column();scroll.addView(grid,new ScrollView.LayoutParams(-1,-2));
        for(int offset=0;offset<apps.size();offset+=4){LinearLayout r=row();
            for(int col=0;col<4;col++){if(col>0)gap(r,12,true);int idx=offset+col;
                View v=idx<apps.size()?tile(apps.get(idx),idx+1,false):new View(this);
                if(idx<apps.size())focusTiles.add(v);
                r.addView(v,new LinearLayout.LayoutParams(0,px(100),1));}
            grid.addView(r);if(offset+4<apps.size())gap(grid,12,false);}
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }

    private void wireFocus(){
        View[] nav={homeNav,appsNav,vpnButton,settingsNav};
        for(int i=0;i<nav.length;i++){
            nav[i].setNextFocusLeftId(nav[Math.max(0,i-1)].getId());
            nav[i].setNextFocusRightId(nav[Math.min(nav.length-1,i+1)].getId());
            nav[i].setNextFocusUpId(nav[i].getId());
            if(!focusTiles.isEmpty())nav[i].setNextFocusDownId(focusTiles.get(i>=2?Math.min(3,focusTiles.size()-1):0).getId());
        }
        int count=focusTiles.size();
        for(int i=0;i<count;i++){
            View v=focusTiles.get(i);
            v.setNextFocusLeftId(focusTiles.get(i%4==0?i:i-1).getId());
            v.setNextFocusRightId(focusTiles.get(i%4==3||i==count-1?i:i+1).getId());
            v.setNextFocusUpId(i<4?(appPage?appsNav:homeNav).getId():focusTiles.get(i-4).getId());
            int down=i+4<count?i+4:((i/4)<((count-1)/4)?count-1:i);
            v.setNextFocusDownId(focusTiles.get(down).getId());
        }
    }

    private void pinMenu(View anchor,App app){PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(pins.contains(app.key())?"Убрать из избранного":"В избранное").setOnMenuItemClickListener(item->{
            if(pins.contains(app.key()))pins.remove(app.key());
            else if(pins.size()<4)pins.add(app.key());
            else{Toast.makeText(this,"В избранном четыре места. Сначала убери одно приложение.",Toast.LENGTH_LONG).show();return true;}
            savePins();build();return true;});menu.show();}
    private void savePins(){JSONArray a=new JSONArray();for(String key:pins)a.put(key);prefs.edit().putString("pins",a.toString()).putBoolean("pinsSet",true).apply();}
    private App find(String key){for(App a:apps)if(a.key().equals(key))return a;return null;}

    private void migrateYouTubeFavorite(){
        if(prefs.getBoolean("youtubeAliasMigrated",false))return;
        App replacement=null;
        for(App app:apps)if(YOUTUBE.equals(app.component.getPackageName())){replacement=app;break;}
        if(replacement==null)return;
        ArrayList<String> migrated=new ArrayList<>();
        for(String key:pins){
            ComponentName old=ComponentName.unflattenFromString(key);
            String mapped=old!=null&&OLD_YOUTUBE.equals(old.getPackageName())?replacement.key():key;
            if(!migrated.contains(mapped))migrated.add(mapped);
        }
        pins.clear();pins.addAll(migrated);
        prefs.edit().putBoolean("youtubeAliasMigrated",true).apply();
    }

    private void loadApps(){
        if(loading||worker.isShutdown())return;loading=true;
        worker.execute(()->{LinkedHashMap<String,App> found=new LinkedHashMap<>();PackageManager pm=getPackageManager();
            for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}){
                Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(category);
                for(ResolveInfo r:pm.queryIntentActivities(intent,0)){
                    String pkg=r.activityInfo.packageName;
                    if(pkg.equals(getPackageName())||pkg.equals(STOCK)||found.containsKey(pkg))continue;
                    if(!r.activityInfo.exported||!r.activityInfo.enabled)continue;
                    found.put(pkg,new App(new ComponentName(pkg,r.activityInfo.name),r.loadLabel(pm).toString()));
                }
            }
            ArrayList<App> result=new ArrayList<>(found.values());Collator collator=Collator.getInstance(new Locale("ru"));
            Collections.sort(result,(a,b)->collator.compare(a.label,b.label));
            runOnUiThread(()->{loading=false;if(isDestroyed()||isFinishing())return;apps.clear();apps.addAll(result);
                migrateYouTubeFavorite();
                pins.removeIf(key->find(key)==null);
                if(!prefs.getBoolean("pinsSet",false)){
                    String[] preferred={YOUTUBE,OLD_YOUTUBE,"com.tcl.browser","com.android.chrome","com.softwinner.TvdFileManager","com.android.gallery3d"};
                    for(String pkg:preferred)for(App a:apps)if(a.component.getPackageName().equals(pkg)&&pins.size()<4&&!pins.contains(a.key()))pins.add(a.key());
                    for(App a:apps)if(pins.size()<4&&!pins.contains(a.key()))pins.add(a.key());
                    if(!apps.isEmpty())savePins();
                }else savePins();
                build();});
        });
    }

    private void launch(App app){try{startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));}
        catch(RuntimeException e){Toast.makeText(this,"Не удалось открыть приложение",Toast.LENGTH_SHORT).show();loadApps();}}
    private void open(Intent intent){try{startActivity(intent);}catch(RuntimeException e){Toast.makeText(this,"Этот раздел недоступен на устройстве",Toast.LENGTH_SHORT).show();}}
    private void settings(){Dialog d=new Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=column();panel.setPadding(px(24),px(24),px(24),px(24));panel.setBackground(surface(SURFACE,LINE));
        panel.addView(text("Настройки",25,TEXT,false));gap(panel,16,false);
        String[] labels={"Wi-Fi","Bluetooth","Настройки проектора","Родной главный экран","Тема: "+(Palette.oled(this)?"OLED":"лаванда"),"Акцент: "+accentName(),"Веб-пульт"};
        Runnable[] actions={()->open(new Intent(Settings.ACTION_WIFI_SETTINGS)),()->open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
            ()->open(new Intent(Settings.ACTION_SETTINGS)),()->{
                Intent old=getPackageManager().getLaunchIntentForPackage(STOCK);
                if(old==null)old=getPackageManager().getLeanbackLaunchIntentForPackage(STOCK);
                if(old!=null)open(old);else Toast.makeText(this,"Штатная оболочка не найдена",Toast.LENGTH_SHORT).show();},
            this::themeMenu,()->{int[] colors={Palette.ACCENT,0xffbdcca5,0xffa9c8d1,0xffd9b78e};int i=0;while(i<colors.length&&colors[i]!=accent)i++;
                accent=colors[(i+1)%colors.length];prefs.edit().putInt("accent",accent).apply();build();},()->RemoteSettings.show(this)};
        for(int i=0;i<labels.length;i++){final int index=i;TextView b=button(labels[i],"@setting"+i,()->{d.dismiss();actions[index].run();},false);
            b.setGravity(Gravity.CENTER_VERTICAL);panel.addView(b,new LinearLayout.LayoutParams(-1,px(52)));}
        d.setContentView(panel);Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(px(490),-2);}d.show();
        if(d.getWindow()!=null)d.getWindow().setLayout(px(490),-2);
        View first=panel.findViewWithTag("@setting0");if(first!=null)first.requestFocus();
    }

    private String accentName(){return accent==Palette.ACCENT?"лаванда":accent==0xffbdcca5?"шалфей":accent==0xffa9c8d1?"лёд":accent==0xffd9b78e?"янтарь":"свой";}
    private void themeMenu(){
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=column();panel.setPadding(px(24),px(24),px(24),px(24));panel.setBackground(surface(SURFACE,LINE));
        panel.addView(text("Тема",25,TEXT,false));gap(panel,16,false);
        boolean oled=Palette.oled(this);
        String[] labels={"Лаванда · по умолчанию","OLED · чёрный фон"};
        for(int i=0;i<labels.length;i++){
            final boolean value=i==1;
            TextView option=button(labels[i],"@theme"+i,()->{prefs.edit().putBoolean("oled",value).apply();dialog.dismiss();recreate();},oled==value);
            option.setGravity(Gravity.CENTER_VERTICAL);panel.addView(option,new LinearLayout.LayoutParams(-1,px(58)));
        }
        dialog.setContentView(panel);dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(px(490),-2);}
        panel.findViewWithTag(oled?"@theme1":"@theme0").requestFocus();
    }

    private void updateClock(){Date now=new Date();Locale ru=new Locale("ru");
        if(clock!=null)clock.setText(new SimpleDateFormat("HH:mm",ru).format(now));
        if(date!=null)date.setText(new SimpleDateFormat("EEEE, d MMMM",ru).format(now).toUpperCase(ru));
        if(greeting!=null){int hour=Integer.parseInt(new SimpleDateFormat("H",Locale.ROOT).format(now));
            greeting.setText(hour>=5&&hour<12?"Неспешного утра.":hour>=12&&hour<18?"Хорошего дня.":"Устраивайся поудобнее.");}
        if(connection!=null){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities nc=cm.getNetworkCapabilities(cm.getActiveNetwork());
            connection.setText(nc==null?"○ OFFLINE":nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"● WI-FI":"● СЕТЬ");
            connection.setTextColor(nc==null?MUTED:accent);
        }catch(RuntimeException e){connection.setText("");}}
    }
    private void renderVpn(){if(vpnButton==null||vpn==null)return;
        boolean focus=vpnButton.hasFocus(),on=vpn.state==2;
        vpnButton.setText(vpn.label());vpnButton.setContentDescription("VPN: "+vpn.label());
        vpnButton.setTextColor(focus?TEXT:on?accent:MUTED);
        vpnButton.setBackground(surface(focus?SELECTION:on?SURFACE:Color.TRANSPARENT,focus?accent:0));
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){
        if(code==KeyEvent.KEYCODE_MENU&&focusedApp!=null){pinMenu(getCurrentFocus(),focusedApp);return true;}
        return super.onKeyDown(code,event);
    }
    @Override public void onBackPressed(){if(appPage){appPage=false;build();return;}
        ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
        if(home==null||home.activityInfo==null||!getPackageName().equals(home.activityInfo.packageName))finish();}

}
