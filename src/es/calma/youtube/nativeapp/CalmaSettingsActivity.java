package es.calma.youtube.nativeapp;

import android.app.Activity;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native port of Calma's settings.js controls, reached from Instagram's settings list. */
public final class CalmaSettingsActivity extends Activity {
    private int foreground, background, muted, line;
    private Mark master;

    @Override public void onCreate(Bundle state) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        CalmaSwitch.initialize(this);
        foreground = dark ? 0xfff5f5f5 : 0xff262626;
        background = dark ? 0xff000000 : 0xffffffff;
        muted = dark ? 0xffa8a8a8 : 0xff737373;
        line = dark ? 0xff262626 : 0xffdbdbdb;
        Window window = getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        window.getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout header = row();
        TextView back = text("‹", 36, foreground);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Volver");
        press(back, () -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text("Inhouse YouTube", 20, foreground);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(header);
        divider(root);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout masterRow = row();
        masterRow.addView(text("Activar cambios", 16, foreground), new LinearLayout.LayoutParams(0, dp(54), 1));
        master = new Mark(true);
        masterRow.addView(master, new LinearLayout.LayoutParams(dp(46), dp(30)));
        masterRow.setContentDescription("Activar cambios de Inhouse");
        masterRow.setAccessibilityDelegate(checkable("android.widget.Switch"));
        press(masterRow, () -> {
            if (CalmaSwitch.setEnabled(!CalmaSwitch.enabled())) {
                refresh(); masterRow.setEnabled(false);
                masterRow.postDelayed(() -> InhouseUi.restart(this), 220);
            } else android.widget.Toast.makeText(this,"No se ha podido guardar",android.widget.Toast.LENGTH_SHORT).show();
        });
        content.addView(masterRow);
        content.addView(text("Se reinicia al cambiar", 13, muted));
        separator(content);
        LinearLayout update = row();
        update.addView(text("Actualizaciones · A/B", 16, foreground), new LinearLayout.LayoutParams(0, dp(58), 1));
        update.addView(text("›", 28, muted), new LinearLayout.LayoutParams(dp(24), dp(58)));
        press(update, () -> startActivity(new Intent().setClassName(getPackageName(), "es.calma.youtube.UpdateActivity").putExtra("settings",true)));
        content.addView(update);
        InhouseUi.footer(this, content, muted, "https://github.com/miguelcoxcaballero/youtube-calma");
        refresh();
        setContentView(root);
    }

    private void refresh() {
        master.setChecked(CalmaSwitch.enabled());
        ((View)master.getParent()).setSelected(CalmaSwitch.enabled());

    }
    private void press(View view, Runnable action) {
        view.setFocusable(true);
        view.setClickable(true);
        android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((foreground & 0x00ffffff) | 0x18000000), null, rounded(0xffffffff));
        view.setBackground(ripple);
        view.setOnClickListener(v -> action.run());
    }
    private View.AccessibilityDelegate checkable(String type) {
        return new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(type); info.setCheckable(true); info.setChecked(view.isSelected());
            }
        };
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(8)); return drawable;
    }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setGravity(Gravity.CENTER_VERTICAL); return value; }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this); result.setText(value); result.setTextSize(size);
        result.setTextColor(color); result.setGravity(Gravity.CENTER_VERTICAL); result.setFontFeatureSettings("kern"); return result;
    }
    private void heading(LinearLayout parent, String value) {
        TextView title = text(value, 15, foreground); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMinimumHeight(dp(42)); parent.addView(title);
    }
    private void divider(LinearLayout parent) { View view = new View(this); view.setBackgroundColor(line); parent.addView(view, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void separator(LinearLayout parent) {
        View view = new View(this); view.setBackgroundColor(line);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(1)); layout.topMargin = dp(18); layout.bottomMargin = dp(14); parent.addView(view, layout);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Mark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean toggle;
        private boolean checked;
        private float progress;
        private ValueAnimator animation;
        private final ArgbEvaluator colors = new ArgbEvaluator();
        Mark(boolean toggle) { super(CalmaSettingsActivity.this); this.toggle = toggle; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setChecked(boolean value) {
            if (checked == value) return;
            checked = value;
            if (animation != null) animation.cancel();
            float target = checked ? 1f : 0f;
            if (!isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
                progress = target; invalidate(); return;
            }
            animation = ValueAnimator.ofFloat(progress, target);
            animation.setDuration(180);
            animation.setInterpolator(new DecelerateInterpolator());
            animation.addUpdateListener(frame -> { progress = (Float) frame.getAnimatedValue(); invalidate(); });
            animation.start();
        }
        @Override protected void onDetachedFromWindow() {
            if (animation != null) animation.cancel();
            progress = checked ? 1f : 0f;
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            if (toggle) {
                paint.setColor((Integer) colors.evaluate(progress, line, foreground));
                canvas.drawRoundRect(0, dp(2), getWidth(), getHeight() - dp(2), dp(20), dp(20), paint);
                paint.setColor((Integer) colors.evaluate(progress, 0xffffffff, background));
                canvas.drawCircle(dp(13) + (getWidth() - dp(26)) * progress, y, dp(10), paint);
            } else {
                paint.setColor((Integer) colors.evaluate(progress, muted, foreground)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.7f));
                canvas.drawCircle(x, y, dp(10), paint);
                if (progress > 0) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, dp(6) * progress, paint); }
            }
        }
    }
}
