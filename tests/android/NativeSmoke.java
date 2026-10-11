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
    private String phase;
    private Class<?> type(String name) throws Exception { return Class.forName(name,true,loader); }
    private void check(boolean value,String message) { if(!value) throw new AssertionError(message);checks++; }
    private Object call(String name,String method,Class<?>[] params,Object... values) throws Exception {
        Method m=type(name).getDeclaredMethod(method,params);m.setAccessible(true);return m.invoke(null,values);
    }
    private void collectInstallerUi(android.view.accessibility.AccessibilityNodeInfo node,List<String> texts,Set<String> packages) {
        if(node==null)return;
        if(node.getText()!=null)texts.add(node.getText().toString());
        if(node.getPackageName()!=null)packages.add(node.getPackageName().toString());
        for(int i=0;i<node.getChildCount();i++)collectInstallerUi(node.getChild(i),texts,packages);
    }
    private String verifyInstallerHandoff(Context context,android.app.Activity activity) throws Exception {
        String authority=context.getPackageName()+".update-provider";
        android.content.pm.PackageManager pm=context.getPackageManager();
        Set<String> authorities=new HashSet<>();
        for(android.content.pm.ProviderInfo provider:pm.getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_PROVIDERS).providers) {
            for(String name:provider.authority.split(";")) check(authorities.add(name),"unique provider authority "+name);
        }
        android.content.pm.ProviderInfo provider=pm.resolveContentProvider(authority,android.content.pm.PackageManager.GET_META_DATA);
        check(provider!=null && provider.name.equals("es.calma.vendor.androidx.core.content.FileProvider"),"update provider resolves to Calma");
        check(!provider.exported && provider.grantUriPermissions,"private provider with temporary grants");
        check(pm.resolveContentProvider(context.getPackageName()+".fileprovider",0).name.equals("com.google.android.apps.youtube.app.common.util.MainAppFileProviderCompat"),"original YouTube provider preserved");
        File directory=new File(context.getCacheDir(),"updates");directory.mkdirs();
        File apk=new File(directory,"inhouse-photos-installer-test.apk");
        try(InputStream in=new FileInputStream(context.getApplicationInfo().sourceDir);OutputStream out=new FileOutputStream(apk)) {
            byte[] buffer=new byte[65536];for(int size;(size=in.read(buffer))!=-1;)out.write(buffer,0,size);
        }
        Method uriMethod=type("es.calma.vendor.androidx.core.content.FileProvider").getMethod("getUriForFile",Context.class,String.class,File.class);
        android.net.Uri uri=(android.net.Uri)uriMethod.invoke(null,context,authority,apk);
        check(uri.getScheme().equals("content") && uri.getAuthority().equals(authority),"update content URI");
        try(android.os.ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(uri,"r")) {
            check(descriptor!=null && descriptor.getStatSize()==apk.length(),"provider opens entire downloaded APK");
        }
        check("application/vnd.android.package-archive".equals(context.getContentResolver().getType(uri)),"APK MIME type");
        boolean rejected=false;
        try { uriMethod.invoke(null,context,authority,new File(context.getFilesDir(),"outside.apk")); }
        catch(InvocationTargetException error) { rejected=error.getCause() instanceof IllegalArgumentException; }
        check(rejected,"files outside update cache cannot be shared");
        android.content.Intent install=new android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final Throwable[] problem={null};
        runOnMainSync(()->{try {activity.startActivity(install);}catch(Throwable error){problem[0]=error;}});
        if(problem[0]!=null)throw new AssertionError(problem[0]);
        check(true,"APK handed to Android installer with read permission");
        // finishInstrumentation force-stops the target process/provider. Observe
        // the actual installer's confirmation while its source remains alive.
        long deadline=android.os.SystemClock.uptimeMillis()+25000;
        List<String> texts=new ArrayList<>();Set<String> packages=new HashSet<>();
        boolean confirmed=false;
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            texts.clear();packages.clear();
            collectInstallerUi(getUiAutomation().getRootInActiveWindow(),texts,packages);
            String all=texts.toString().toLowerCase(java.util.Locale.ROOT);
            if(packages.toString().contains("packageinstaller") && all.contains("youtube") && all.contains("update")) {confirmed=true;break;}
            android.os.SystemClock.sleep(250);
        }
        check(confirmed,"Android installer confirms APK: "+packages+" "+texts);
        JSONObject evidence=new JSONObject();evidence.put("texts",new org.json.JSONArray(texts));evidence.put("packages",new org.json.JSONArray(packages));
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        return evidence.toString();
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args);phase=args==null?null:args.getString("profile");start(); }
    private void settingsEntry(Context context) throws Exception {
        for(String layout:Arrays.asList("settings_fragment","settings_fragment_cairo")) {
            android.content.res.XmlResourceParser xml=context.getResources().getXml(context.getResources().getIdentifier(layout,"xml",context.getPackageName()));
            boolean revanced=false,found=false;String next="";
            for(int event=xml.getEventType();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;event=xml.next()) {
                if(event!=org.xmlpull.v1.XmlPullParser.START_TAG)continue;
                if("intent".equals(xml.getName()) && "revanced_settings_intent".equals(xml.getAttributeValue("http://schemas.android.com/apk/res/android","data")))revanced=true;
                else if(revanced && "Preference".equals(xml.getName())) {
                    next=xml.getAttributeValue("http://schemas.android.com/apk/res/android","key");
                    check("inhouse_youtube_settings".equals(next),"Inhouse immediately below ReVanced: "+layout);
                    check("Inhouse YouTube settings".equals(xml.getAttributeValue("http://schemas.android.com/apk/res/android","title")),"Inhouse native label");found=true;break;
                }
            }
            xml.close();check(found,"Inhouse row exists: "+layout);
        }
    }
    private void profilePhase(Context context,SharedPreferences prefs,Bundle result) throws Exception {
        String name="es.calma.youtube.nativeapp.CalmaSwitch";
        if("disable".equals(phase)) {
            check(Boolean.TRUE.equals(call(name,"enabled",new Class[]{})),"initial enabled profile");
            prefs.edit().putString("revanced_playback_speed_default","1.25").putString("sb_local_time_saved_number_segments","4999").putString("unrelated_inhouse_test","keep").commit();
            check(Boolean.TRUE.equals(call(name,"setEnabled",new Class[]{boolean.class},false)),"disabled profile committed");
        } else {
            check(Boolean.FALSE.equals(call(name,"enabled",new Class[]{})),"disabled state survives a new process");
            android.content.Intent main=new android.content.Intent(android.content.Intent.ACTION_MAIN);
            call("es.calma.youtube.nativeapp.CalmaBootstrap","launchSubscriptions",new Class[]{android.content.Intent.class},main);
            check(android.content.Intent.ACTION_MAIN.equals(main.getAction()),"disabled launch keeps original action");
            check(Boolean.FALSE.equals(call("es.calma.youtube.nativeapp.CalmaWatch","filter",new Class[]{String.class,StringBuilder.class},"related_video_with_context.e",new StringBuilder("watch_next"))),"disabled recommendation filter");
            check(call("app.revanced.extension.youtube.patches.NavigationBarPatch","switchCreateWithNotificationButton",new Class[]{String.class},"Pixel").equals("Pixel"),"disabled navigation retains device model");
            Object setting=type("app.revanced.extension.youtube.settings.Settings").getField("HIDE_HOME_BUTTON").get(null);
            Class<?> base=type("app.revanced.extension.shared.settings.Setting");
            check(base.getMethod("get").invoke(setting).equals(base.getField("defaultValue").get(setting)),"disabled profile loads original Home preference after restart");
            check(Boolean.TRUE.equals(call(name,"setEnabled",new Class[]{boolean.class},true)),"enabled profile restored");
            check("1.25".equals(prefs.getString("revanced_playback_speed_default","")),"enabled profile remembers custom speed");
            check("4999".equals(prefs.getString("sb_local_time_saved_number_segments","")) && "keep".equals(prefs.getString("unrelated_inhouse_test","")),"statistics and unrelated preferences preserved");
            // A second complete cycle must not replace the saved enabled profile.
            call(name,"setEnabled",new Class[]{boolean.class},false);call(name,"setEnabled",new Class[]{boolean.class},true);
            check("1.25".equals(prefs.getString("revanced_playback_speed_default","")),"repeat switching preserves profile");
            prefs.edit().putString("revanced_playback_speed_default","1.15").putString("sb_local_time_saved_number_segments","3816").remove("unrelated_inhouse_test").commit();
        }
        result.putString("stream","CALMA_PROFILE_OK "+phase+" checks="+checks+"\n");finish(-1,result);
    }
    private void collectViews(View view,List<String> texts) {
        if(view instanceof android.widget.TextView)texts.add(((android.widget.TextView)view).getText().toString());
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)collectViews(((android.view.ViewGroup)view).getChildAt(i),texts);
    }
    private String brandingIcon(android.content.Context context) throws Exception {
        android.content.pm.PackageManager pm=context.getPackageManager();
        android.content.Intent launch=pm.getLaunchIntentForPackage(context.getPackageName());
        android.graphics.drawable.Drawable icon=pm.getActivityIcon(launch);
        check(icon instanceof android.graphics.drawable.AdaptiveIconDrawable,"Launcher resolves the adaptive Inhouse icon");
        android.content.pm.ActivityInfo launcher=pm.getActivityInfo(launch.getComponent(),0);
        check("inhouse_launcher".equals(context.getResources().getResourceEntryName(launcher.getIconResource())),"Enabled launcher alias uses Inhouse artwork");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(192,192,android.graphics.Bitmap.Config.ARGB_8888);
        icon.setBounds(0,0,192,192);icon.draw(new android.graphics.Canvas(bitmap));
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);bitmap.recycle();
        android.graphics.Bitmap logo;
        try(java.io.InputStream input=context.getAssets().open("inhouse-logo.png")){logo=android.graphics.BitmapFactory.decodeStream(input);}
        check(logo!=null,"Interior logo decodes");
        int corner=logo.getPixel(0,0);check(android.graphics.Color.red(corner)<12 && android.graphics.Color.green(corner)<12 && android.graphics.Color.blue(corner)<12,"Interior Inhouse logo has a black background");logo.recycle();
        return android.util.Base64.encodeToString(output.toByteArray(),android.util.Base64.NO_WRAP);
    }
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
            if(phase!=null) { profilePhase(context,prefs,result);return; }
            settingsEntry(context);
                result.putString("inhouseIconPng",brandingIcon(context));
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
            check(Boolean.FALSE.equals(call("app.revanced.extension.shared.checks.Check","shouldRun",new Class[]{})),"Manager-only distribution warning disabled");
            android.app.Activity settingsActivity=startActivitySync(new android.content.Intent().setClassName(context.getPackageName(),"es.calma.youtube.nativeapp.CalmaSettingsActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();List<String> settingsTexts=new ArrayList<>();
            runOnMainSync(()->collectViews(settingsActivity.getWindow().getDecorView(),settingsTexts));
            check(settingsTexts.contains("Activar cambios") && settingsTexts.contains("Inhouse") && settingsTexts.contains("GitHub ↗"),"actual settings contain toggle and brand footer");
            String version=(String)type("es.calma.youtube.nativeapp.CalmaBuild").getField("VERSION").get(null);
            String date=(String)type("es.calma.youtube.nativeapp.CalmaBuild").getField("RELEASE_DATE").get(null);
            check(settingsTexts.contains("v"+version+" · "+date),"actual settings display release metadata");
            String installerUi=verifyInstallerHandoff(context,settingsActivity);
            result.putString("stream","CALMA_INSTALLER_UI "+installerUi+"\nCALMA_NATIVE_TESTS_OK checks="+checks+"\n");finish(-1,result);
        } catch(Throwable error) {
            StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));result.putString("stream",text.toString());finish(0,result);
        }
    }
}
