package es.calma.youtube.tests;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.MenuItem;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Executes the existing APK's actual settings, filters and navigation callback. */
public final class NativeSmoke extends Instrumentation {
    private ClassLoader loader;
    private int checks;
    private Class<?> type(String name) throws Exception { return Class.forName(name,true,loader); }
    private void check(boolean value,String message) { if(!value) throw new AssertionError(message);checks++; }
    private Object call(String name,String method,Class<?>[] params,Object... values) throws Exception {
        Method m=type(name).getDeclaredMethod(method,params);m.setAccessible(true);return m.invoke(null,values);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();loader=context.getClassLoader();
            // am instrument starts a fresh process without MainActivity. This
            // base APK initializes the ReVanced context from that activity.
            android.content.Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            check(launch!=null,"launcher available");
            android.app.Activity activity=startActivitySync(launch);
            waitForIdleSync();
            check(activity!=null,"native activity initialized");
            check(call("app.revanced.extension.shared.Utils","getContext",new Class[]{})!=null,"ReVanced context initialized by native activity");
            SharedPreferences prefs=context.getSharedPreferences("revanced_prefs",0);
            check(prefs.getBoolean("calma_preset_v1",false),"preset must load during Application.onCreate");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=context.getAssets().open("calma-preferences.json")) { byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)bytes.write(b,0,n); }
            JSONObject expected=new JSONObject(bytes.toString("UTF-8"));
            Class<?> setting=type("app.revanced.extension.shared.settings.Setting");
            Map<String,Object> actual=new HashMap<>();
            for(String name:Arrays.asList("app.revanced.extension.shared.settings.BaseSettings","app.revanced.extension.shared.settings.YouTubeAndMusicSettings","app.revanced.extension.youtube.settings.Settings")) {
                for(Field f:type(name).getFields()) if(Modifier.isStatic(f.getModifiers()) && setting.isAssignableFrom(f.getType())) {
                    Object instance=f.get(null);String key=(String)setting.getField("key").get(instance);
                    actual.put(key,setting.getMethod("get").invoke(instance));
                }
            }
            for(Iterator<String> keys=expected.keys();keys.hasNext();) {
                String key=keys.next();Object value=actual.get(key);check(value!=null,"missing setting "+key);
                String wanted=expected.get(key).toString(), got=value.toString();
                if(value instanceof Number) check(Math.abs(Double.parseDouble(got)-Double.parseDouble(wanted))<0.00001,"numeric setting "+key);
                else check(got.equals(wanted),"setting "+key+" expected "+wanted+" got "+got);
            }
            String bootstrap="es.calma.youtube.nativeapp.CalmaBootstrap";
            for(String id:Arrays.asList("FEwhat_to_watch","FEsubscriptions","FEactivity","FEhistory","UCexample")) {
                Object got=call(bootstrap,"homeBrowseId",new Class[]{String.class},id);
                check(got.equals(id.equals("FEwhat_to_watch")?"FEsubscriptions":id),"home route "+id);
            }
            check(call("app.revanced.extension.youtube.patches.litho.AdsFilter","hideAds",new Class[]{String.class},"Pixel").equals("Android Automotive"),"notification client context");
            Class<?> nav=type("app.revanced.extension.youtube.shared.NavigationBar");
            Class<?> button=type("app.revanced.extension.youtube.shared.NavigationBar$NavigationButton");
            Method callback=nav.getDeclaredMethod("navigationTabCreatedCallback",button,View.class);callback.setAccessible(true);
            for(String name:Arrays.asList("HOME","SHORTS","CREATE","NOTIFICATIONS","SUBSCRIPTIONS","LIBRARY")) {
                final Throwable[] problem={null};
                runOnMainSync(()->{try {
                    View view=new View(context);callback.invoke(null,button.getField(name).get(null),view);
                    check(view.getVisibility()==(name.equals("HOME")||name.equals("SHORTS")||name.equals("CREATE")?View.GONE:View.VISIBLE),"navigation "+name);
                } catch(Throwable error) {problem[0]=error;}});
                if(problem[0]!=null)throw new AssertionError(problem[0]);
            }
            Class<?> icon=type("axlk");
            for(Object value:icon.getEnumConstants()) {
                String name=((Enum<?>)value).name();
                if(!Arrays.asList("TAB_ACTIVITY","TAB_ACTIVITY_CAIRO","CREATION_ENTRY","CREATION_ENTRY_V2","SEARCH","TAB_HOME").contains(name))continue;
                boolean[] hidden={false};
                MenuItem menu=(MenuItem)Proxy.newProxyInstance(loader,new Class[]{MenuItem.class},(proxy,method,args)->{
                    if(method.getName().equals("setVisible")){hidden[0]=!((Boolean)args[0]);return proxy;}return null;
                });
                call(bootstrap,"toolbarItem",new Class[]{Enum.class,MenuItem.class},value,menu);
                check(hidden[0]==(name.startsWith("TAB_ACTIVITY")||name.startsWith("CREATION_ENTRY")),"toolbar "+name);
            }
            Class<?> shorts=type("app.revanced.extension.youtube.patches.litho.ShortsFilter");
            Object filter=shorts.getConstructor().newInstance();Method hide=shorts.getDeclaredMethod("shouldHideShortsFeedItems");hide.setAccessible(true);
            check(Boolean.TRUE.equals(hide.invoke(filter)),"Shorts hidden on all feed surfaces");
            for(String layout:Arrays.asList("revanced_prefs","revanced_prefs_icons","revanced_prefs_icons_bold")) {
                int id=context.getResources().getIdentifier(layout,"xml",context.getPackageName());
                android.content.res.XmlResourceParser xml=context.getResources().getXml(id);boolean found=false;
                for(int event=xml.getEventType();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;event=xml.next()) {
                    if(event==org.xmlpull.v1.XmlPullParser.START_TAG && "Preference".equals(xml.getName())
                            && "calma_updates".equals(xml.getAttributeValue("http://schemas.android.com/apk/res/android","key"))) found=true;
                }
                xml.close();check(found,"manual updater in "+layout);
            }
            for(String action:Arrays.asList(android.content.Intent.ACTION_MAIN,android.content.Intent.ACTION_VIEW)) {
                android.content.Intent intent=new android.content.Intent(action);
                call(bootstrap,"launchSubscriptions",new Class[]{android.content.Intent.class},intent);
                check(intent.getAction().equals(action.equals(android.content.Intent.ACTION_MAIN)?"com.google.android.youtube.action.open.subscriptions":action),"launch action "+action);
            }
            Class<?> playerType=type("app.revanced.extension.youtube.shared.PlayerType");
            for(String state:Arrays.asList("WATCH_WHILE_MAXIMIZED","WATCH_WHILE_MINIMIZED")) {
                call("es.calma.youtube.nativeapp.CalmaWatch","playerType",new Class[]{Enum.class},playerType.getField(state).get(null));
                for(String row:Arrays.asList("related_video_with_context.e","compact_video.e","watch_metadata.e","comment_thread.e","video_description.e")) {
                    boolean removed=(Boolean)call("es.calma.youtube.nativeapp.CalmaWatch","filter",new Class[]{String.class,StringBuilder.class},row,new StringBuilder("root|"+row));
                    check(removed==(row.startsWith("related_")||(row.startsWith("compact_")&&state.equals("WATCH_WHILE_MAXIMIZED"))),"watch row "+state+" "+row);
                }
            }
            // Reinitialization must not reset counters or user choices.
            String key="sb_local_time_saved_number_segments";String before=prefs.getString(key,"");prefs.edit().putString(key,"4000").commit();
            call(bootstrap,"initialize",new Class[]{Context.class},context);check(prefs.getString(key,"").equals("4000"),"preserve accumulated stats");prefs.edit().putString(key,before).commit();
            result.putString("stream","CALMA_NATIVE_TESTS_OK checks="+checks+"\n");finish(-1,result);
        } catch(Throwable error) {
            StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));result.putString("stream",text.toString());finish(0,result);
        }
    }
}
