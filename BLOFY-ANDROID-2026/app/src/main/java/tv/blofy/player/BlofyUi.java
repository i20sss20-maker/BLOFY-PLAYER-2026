package tv.blofy.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class BlofyUi {
    static final int BLACK = Color.rgb(5, 5, 11);
    static final int PANEL = Color.rgb(14, 15, 27);
    static final int PANEL_ALT = Color.rgb(21, 21, 38);
    static final int PURPLE = Color.rgb(124, 50, 255);
    static final int PURPLE_LIGHT = Color.rgb(187, 121, 255);
    static final int TEXT = Color.rgb(250, 249, 255);
    static final int MUTED = Color.rgb(167, 163, 184);
    static final int SUCCESS = Color.rgb(73, 222, 164);
    static final int ERROR = Color.rgb(255, 103, 135);

    private BlofyUi() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static boolean isTv(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    static TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        view.setPadding(dp(context, 8), dp(context, 5), dp(context, 8), dp(context, 5));
        return view;
    }

    static TextView title(Context context, String value, int sp) {
        TextView view = text(context, value, sp, TEXT);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    static EditText input(Context context, String hint, boolean numeric) {
        EditText view = new EditText(context);
        view.setSingleLine(true);
        view.setHint(hint);
        view.setTextColor(TEXT);
        view.setHintTextColor(Color.rgb(111, 108, 132));
        view.setTextSize(15);
        view.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        view.setBackground(focusDrawable(context, PANEL_ALT, Color.rgb(55, 48, 85), PURPLE));
        view.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        view.setFocusable(true);
        return view;
    }

    static Button button(Context context, String label, boolean primary) {
        Button view = new Button(context);
        view.setText(label);
        view.setTextColor(TEXT);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        view.setBackground(primary
                ? focusDrawable(context, PURPLE, Color.rgb(147, 71, 255), Color.WHITE)
                : focusDrawable(context, PANEL_ALT, Color.rgb(45, 42, 68), PURPLE_LIGHT));
        view.setFocusable(true);
        return view;
    }

    static Drawable panel(Context context, int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    static Drawable focusDrawable(Context context, int normal, int focused, int focusStroke) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(focused);
        focus.setCornerRadius(dp(context, 13));
        focus.setStroke(dp(context, 2), focusStroke);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(normal);
        idle.setCornerRadius(dp(context, 13));
        idle.setStroke(dp(context, 1), Color.rgb(47, 45, 66));
        states.addState(new int[]{android.R.attr.state_focused}, focus);
        states.addState(new int[]{android.R.attr.state_pressed}, focus);
        states.addState(new int[]{}, idle);
        return states;
    }

    static Drawable screenGradient() {
        return new Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas canvas) {
                paint.setShader(new LinearGradient(0, 0, canvas.getWidth(), canvas.getHeight(),
                        new int[]{Color.rgb(5, 5, 12), Color.rgb(14, 7, 31), Color.rgb(5, 5, 11)},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(getBounds(), paint);
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }

    static LinearLayout brand(Context context, String subtitle) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(logo, new LinearLayout.LayoutParams(dp(context, 74), dp(context, 74)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = title(context, "BLOFY", 24);
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        name.setGravity(Gravity.LEFT);
        TextView player = text(context, subtitle == null ? "P L A Y E R" : subtitle, 11, PURPLE_LIGHT);
        player.setTextDirection(View.TEXT_DIRECTION_LTR);
        player.setGravity(Gravity.LEFT);
        labels.addView(name);
        labels.addView(player);
        row.addView(labels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    static ColorStateList progressColors() { return ColorStateList.valueOf(PURPLE_LIGHT); }
}
