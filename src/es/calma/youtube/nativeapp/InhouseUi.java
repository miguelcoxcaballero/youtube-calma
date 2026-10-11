package es.calma.youtube.nativeapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared Inhouse identity, using the original logo asset and release constants. */
public final class InhouseUi {
    private static int dp(Activity activity,int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    public static void footer(Activity activity,LinearLayout parent,int muted,String github) {
        View space=new View(activity);parent.addView(space,new LinearLayout.LayoutParams(1,0,1));
        LinearLayout footer=new LinearLayout(activity);footer.setOrientation(LinearLayout.VERTICAL);footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0,dp(activity,32),0,dp(activity,4));
        LinearLayout brand=new LinearLayout(activity);brand.setGravity(Gravity.CENTER);
        ImageView logo=new ImageView(activity);logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        try(java.io.InputStream input=activity.getAssets().open("inhouse-logo.png")) { logo.setImageBitmap(BitmapFactory.decodeStream(input)); }
        catch(java.io.IOException ignored) { }
        GradientDrawable outline=new GradientDrawable();outline.setColor(0xff000000);outline.setCornerRadius(dp(activity,8));
        logo.setBackground(outline);logo.setClipToOutline(true);logo.setAlpha(1f);
        brand.addView(logo,new LinearLayout.LayoutParams(dp(activity,28),dp(activity,28)));
        TextView name=new TextView(activity);name.setText("Inhouse");name.setTextSize(14);name.setTextColor(muted);name.setTypeface(Typeface.create("sans-serif-medium",0));name.setPadding(dp(activity,8),0,0,0);
        brand.addView(name);
        brand.setMinimumHeight(dp(activity,48));brand.setPadding(dp(activity,12),0,dp(activity,12),0);
        brand.setContentDescription("Inhouse Software · Abrir web");brand.setFocusable(true);
        brand.setOnClickListener(view->activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://inhouse.miguelcoxcaballero.com/"))));
        footer.addView(brand);
        TextView version=new TextView(activity);version.setText("v"+CalmaBuild.VERSION+" · "+CalmaBuild.RELEASE_DATE);version.setTextColor(muted);version.setTextSize(12);version.setGravity(Gravity.CENTER);version.setPadding(0,dp(activity,10),0,0);footer.addView(version);
        TextView link=new TextView(activity);link.setText("GitHub ↗");link.setTextSize(13);link.setTextColor(muted);link.setGravity(Gravity.CENTER);link.setMinHeight(dp(activity,48));link.setMinWidth(dp(activity,96));link.setContentDescription("GitHub: "+github);link.setFocusable(true);
        link.setOnClickListener(view->activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(github))));footer.addView(link);
        parent.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }
    public static void restart(Activity activity) {
        Intent launch=activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if(launch==null || launch.getComponent()==null) { activity.finishAffinity();return; }
        Intent restart=Intent.makeRestartActivityTask(launch.getComponent());restart.setPackage(activity.getPackageName());
        // Same foreground restart sequence as the native ReVanced settings.
        activity.startActivity(restart);System.exit(0);
    }
}
