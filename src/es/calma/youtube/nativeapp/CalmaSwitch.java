package es.calma.youtube.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;

/** Two persistent profiles; changing the switch never erases account data or counters. */
public final class CalmaSwitch {
    private static volatile SharedPreferences preferences;
    private static final List<String> keys = new ArrayList<>();
    public static synchronized void initialize(Context context) {
        if (preferences != null) return;
        SharedPreferences p=context.getSharedPreferences("revanced_prefs",0);
        try (InputStream input=context.getAssets().open("calma-preferences.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
            for(int count;(count=input.read(buffer))!=-1;)bytes.write(buffer,0,count);
            JSONObject preset=new JSONObject(bytes.toString("UTF-8"));
            keys.clear();
            for(Iterator<String> names=preset.keys();names.hasNext();) {
                String key=names.next();
                // Identity and accumulated statistics are independent of content controls.
                if(!key.startsWith("sb_local_time_saved_") && !key.startsWith("revanced_custom_branding_")) keys.add(key);
            }
            if(!p.contains("inhouse_base_profile")) {
                // Older Calma builds did not retain a pre-preset profile. Missing
                // keys restore the provided ReVanced APK's own built-in defaults.
                JSONObject base=snapshot(p,p.getBoolean("calma_preset_v1",false));
                if(!p.edit().putString("inhouse_base_profile",base.toString()).commit()) return;
            }
            preferences=p;
        } catch(Exception error) { android.util.Log.e("InhouseSettings","Could not initialize profiles",error); }
    }
    public static boolean enabled() {
        SharedPreferences p=preferences;return p==null || p.getBoolean("inhouse_enabled",true);
    }
    private static JSONObject snapshot(SharedPreferences p,boolean defaults) throws Exception {
        JSONObject result=new JSONObject();java.util.Map<String,?> values=p.getAll();
        for(String key:keys) result.put(key,defaults ? JSONObject.NULL : values.containsKey(key)?values.get(key):JSONObject.NULL);
        return result;
    }
    public static synchronized boolean setEnabled(boolean value) {
        SharedPreferences p=preferences;if(p==null)return false;
        if(value==enabled())return true;
        try {
            String save=value?"inhouse_base_profile":"inhouse_active_profile";
            String restore=value?"inhouse_active_profile":"inhouse_base_profile";
            JSONObject target=new JSONObject(p.getString(restore,"{}"));
            SharedPreferences.Editor edit=p.edit().putString(save,snapshot(p,false).toString());
            for(String key:keys) {
                Object setting=target.opt(key);
                if(setting==null || setting==JSONObject.NULL)edit.remove(key);
                else if(setting instanceof Boolean)edit.putBoolean(key,(Boolean)setting);
                else edit.putString(key,setting.toString());
            }
            return edit.putBoolean("inhouse_enabled",value).commit();
        } catch(Exception error) { android.util.Log.e("InhouseSettings","Could not save profile",error);return false; }
    }
}
