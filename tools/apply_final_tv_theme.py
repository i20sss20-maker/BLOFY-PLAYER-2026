from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f'{label}: pattern not found')
    return text.replace(old, new, 1)

# A tighter television-first BLOFY visual language: flatter panels and clearer focus.
ui_path = ROOT / 'BlofyUi.java'
ui = ui_path.read_text(encoding='utf-8')
ui = replace_once(ui,
'''    static final int BLACK = Color.rgb(4, 4, 8);\n    static final int PANEL = Color.rgb(22, 22, 28);\n    static final int PANEL_ALT = Color.rgb(36, 35, 43);\n    static final int PURPLE = Color.rgb(116, 55, 238);\n    static final int PURPLE_LIGHT = Color.rgb(179, 133, 255);''',
'''    static final int BLACK = Color.rgb(3, 3, 5);\n    static final int PANEL = Color.rgb(15, 15, 18);\n    static final int PANEL_ALT = Color.rgb(28, 27, 32);\n    static final int PURPLE = Color.rgb(111, 45, 234);\n    static final int PURPLE_LIGHT = Color.rgb(188, 145, 255);''', 'theme colors')
ui = ui.replace('Math.min(radiusDp, 12)', 'Math.min(radiusDp, 7)')
ui = ui.replace('focus.setCornerRadius(dp(context, 11));', 'focus.setCornerRadius(dp(context, 6));')
ui = ui.replace('idle.setCornerRadius(dp(context, 11));', 'idle.setCornerRadius(dp(context, 6));')
ui = ui.replace('Color.rgb(67, 65, 73)', 'Color.rgb(58, 57, 64)')
ui = ui.replace('new int[]{Color.rgb(5, 5, 8), Color.rgb(14, 10, 20), Color.rgb(6, 5, 10)}',
                'new int[]{Color.rgb(2, 2, 4), Color.rgb(10, 7, 16), Color.rgb(3, 3, 6)}')
ui_path.write_text(ui, encoding='utf-8')

# TV shell spacing/columns: closer to the compact 7 Max television layout while retaining BLOFY branding.
seven_path = ROOT / 'SevenMaxActivity.java'
seven = seven_path.read_text(encoding='utf-8')
seven = seven.replace('body.setPadding(dp(36), dp(12), dp(36), dp(28));', 'body.setPadding(dp(26), dp(8), dp(26), dp(22));')
seven = seven.replace('new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.WRAP_CONTENT)', 'new LinearLayout.LayoutParams(dp(235), ViewGroup.LayoutParams.WRAP_CONTENT)')
seven = seven.replace('top.setPadding(dp(26), dp(12), dp(26), dp(10));', 'top.setPadding(dp(22), dp(7), dp(22), dp(7));')
seven = seven.replace('new LinearLayout.LayoutParams(dp(250), dp(72))', 'new LinearLayout.LayoutParams(dp(220), dp(62))')
seven = seven.replace('new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84))', 'new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72))')
seven = seven.replace('columns.setPadding(dp(18), 0, dp(18), dp(18));', 'columns.setPadding(dp(10), 0, dp(10), dp(10));')
seven = seven.replace('new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT)', 'new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT)', 1)
seven = seven.replace('new LinearLayout.LayoutParams(dp(410), ViewGroup.LayoutParams.MATCH_PARENT)', 'new LinearLayout.LayoutParams(dp(390), ViewGroup.LayoutParams.MATCH_PARENT)')
seven = seven.replace('preview.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));', 'preview.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));')
seven = seven.replace('TextView name = BlofyUi.title(this, "اختر قناة", 23);', 'TextView name = BlofyUi.title(this, "اختر قناة", 21);')
# More TV-like menu proportions.
seven = seven.replace('p.height=dp(span==2?190:145);', 'p.height=dp(span==2?176:134);')
seven_path.write_text(seven, encoding='utf-8')

# Replace generic Media3 controller on Live with a BLOFY television overlay.
player_path = ROOT / 'PlayerActivity.java'
player = player_path.read_text(encoding='utf-8')
player = replace_once(player,
'''    private TextView titleView;\n    private Button retryButton;''',
'''    private TextView titleView;\n    private TextView liveInfoView;\n    private Button retryButton;''', 'player overlay field')
player = replace_once(player,
'''        playerView = new PlayerView(this);\n        playerView.setUseController(true);\n        playerView.setControllerAutoShow(true);\n        playerView.setControllerShowTimeoutMs(4500);''',
'''        playerView = new PlayerView(this);\n        playerView.setUseController(!isLive());\n        playerView.setControllerAutoShow(!isLive());\n        playerView.setControllerShowTimeoutMs(4500);''', 'live custom controller')
needle = '''        root.addView(titleView, new FrameLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP));\n\n        progress = new ProgressBar(this);'''
replacement = '''        root.addView(titleView, new FrameLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP));\n\n        liveInfoView = new TextView(this);\n        liveInfoView.setTextColor(Color.WHITE);\n        liveInfoView.setTextSize(14);\n        liveInfoView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);\n        liveInfoView.setPadding(dp(24), dp(8), dp(24), dp(8));\n        liveInfoView.setBackgroundColor(Color.argb(205, 8, 7, 12));\n        liveInfoView.setText("BLOFY LIVE   •   OK معلومات   •   BACK رجوع");\n        liveInfoView.setVisibility(isLive() ? View.VISIBLE : View.GONE);\n        FrameLayout.LayoutParams liveInfoParams = new FrameLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM);\n        liveInfoParams.setMargins(dp(16), 0, dp(16), dp(16));\n        root.addView(liveInfoView, liveInfoParams);\n\n        progress = new ProgressBar(this);'''
player = replace_once(player, needle, replacement, 'live info overlay')
player = replace_once(player,
'''            titleView.setText(title);\n            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);''',
'''            titleView.setText(title);\n            if (liveInfoView != null && isLive()) {\n                liveInfoView.setText(title + "   •   " + extension.toUpperCase() + "   •   " + activeTransportName());\n                liveInfoView.setVisibility(View.VISIBLE);\n                liveInfoView.postDelayed(() -> liveInfoView.setVisibility(View.GONE), 3500);\n            }\n            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);''', 'ready overlay')
# OK toggles the TV overlay, Back exits, media keys continue to work.
player = replace_once(player,
'''                case KeyEvent.KEYCODE_BACK:\n                    finish();\n                    return true;''',
'''                case KeyEvent.KEYCODE_BACK:\n                    finish();\n                    return true;\n                case KeyEvent.KEYCODE_DPAD_CENTER:\n                case KeyEvent.KEYCODE_ENTER:\n                    if (isLive() && liveInfoView != null) {\n                        titleView.setVisibility(View.VISIBLE);\n                        liveInfoView.setVisibility(View.VISIBLE);\n                        titleView.setText(title);\n                        liveInfoView.setText(title + "   •   " + extension.toUpperCase() + "   •   " + activeTransportName());\n                        titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 4000);\n                        liveInfoView.postDelayed(() -> liveInfoView.setVisibility(View.GONE), 4000);\n                        return true;\n                    }\n                    break;''', 'remote overlay')
player_path.write_text(player, encoding='utf-8')

print('BLOFY final TV theme applied')
