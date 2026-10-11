package es.calma.youtube.nativeapp;

import android.content.Context;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Inserts the original notification pivot model before the original renderer binds the bar. */
public final class NativeInbox {
    private static boolean logged;
    private static Class<?> type(String name)throws Exception {return Class.forName(name,true,NativeInbox.class.getClassLoader());}
    private static Object field(Object object,String name)throws Exception {return object.getClass().getField(name).get(object);}
    private static void field(Object object,String name,Object value)throws Exception {object.getClass().getField(name).set(object,value);}
    private static Object builder(String name)throws Exception {Object value=type(name).getField("a").get(null);return value.getClass().getMethod("createBuilder").invoke(value);}
    private static Object build(Object builder)throws Exception {return builder.getClass().getMethod("build").invoke(builder);}
    private static Object message(String name,Object... values)throws Exception {
        Object builder=builder(name),instance=field(builder,"instance");
        for(int i=0;i<values.length;i+=2)field(instance,(String)values[i],values[i+1]);
        return build(builder);
    }
    private static String identity(Object tab)throws Exception {
        Object optional=field(tab,"d");return (String)optional.getClass().getMethod("orElse",Object.class).invoke(optional,"");
    }
    private static boolean notification(Object tab)throws Exception {
        String id=identity(tab);if("FEactivity".equals(id)||"FEnotifications_inbox".equals(id))return true;
        Object proto=field(tab,"a");if(!proto.getClass().getName().equals("bbhx") || (Integer)field(proto,"c")!=5)return false;
        Object icon=field(proto,"d");Object value=type("axlk").getMethod("a",int.class).invoke(null,field(icon,"c"));
        return value instanceof Enum && ((Enum<?>)value).name().startsWith("TAB_ACTIVITY");
    }
    private static Object notificationTab()throws Exception {
        Object endpoint=message("aupr","b",1,"c","FEactivity");
        Object commandBuilder=builder("avfw");
        commandBuilder.getClass().getMethod("e",type("atdw"),Object.class).invoke(commandBuilder,type("aupv").getField("a").get(null),endpoint);
        Object command=build(commandBuilder);
        Object bell=type("axlk").getMethod("a",int.class).invoke(null,1156);
        if(!(bell instanceof Enum) || !"TAB_ACTIVITY_CAIRO".equals(((Enum<?>)bell).name()))throw new IllegalStateException("Notification icon schema changed");
        int number=(Integer)bell.getClass().getMethod("getNumber").invoke(bell);
        Object icon=message("axll","b",1,"c",number);
        String label="Notifications";
        Context context=(Context)type("app.revanced.extension.shared.Utils").getMethod("getContext").invoke(null);
        if(context!=null){int resource=context.getResources().getIdentifier("revanced_change_start_page_entry_notifications","string",context.getPackageName());if(resource!=0)label=context.getString(resource);}
        Object text=message("awyk","b",1,"d",label);
        Object pivot=message("bbhx","b",1|4|32,"e","FEactivity","c",5,"d",icon,"g",command,"h",text);
        Object wrapper=message("bbic","b",117501096,"c",pivot);
        Object optional=type("antv").getMethod("a",type("bbic")).invoke(null,wrapper);
        return optional.getClass().getMethod("get").invoke(optional);
    }
    public static Object tabs(Object original) {
        if(!CalmaSwitch.enabled() || !(original instanceof List))return original;
        try {
            List<?> input=(List<?>)original;ArrayList<Object> result=new ArrayList<>();Object inbox=null,account=null;int subscription=-1;
            for(Object tab:input){
                if(notification(tab)){if(inbox==null)inbox=tab;continue;}
                if("FElibrary".equals(identity(tab))){account=tab;continue;}
                result.add(tab);if("FEsubscriptions".equals(identity(tab)))subscription=result.size()-1;
            }
            if(subscription<0 || account==null)return original;
            if(inbox==null)inbox=notificationTab();
            // Retain the original account model, including its avatar and navigation command.
            result.add(subscription+1,account);
            result.add(subscription+2,inbox);
            if(result.equals(input))return original;
            return type("arbh").getMethod("n",Iterable.class).invoke(null,result);
        }catch(Exception failure){if(!logged){logged=true;Log.e("InhouseInbox","Could not bind native notification tab",failure);}return original;}
    }
}
