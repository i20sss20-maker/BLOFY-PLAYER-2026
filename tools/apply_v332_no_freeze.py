#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"

text = PLAYER.read_text()

old = '''        retryButton = BlofyUi.button(this, "إعادة الاتصال", true);\n        retryButton.setOnClickListener(view -> manualRetry());\n        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(dp(250), dp(58));\n        retryParams.topMargin = dp(4);\n        modal.addView(retryButton, retryParams);\n\n        errorPanel.addView(modal, new LinearLayout.LayoutParams(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT));\n'''
new = '''        retryButton = BlofyUi.button(this, "إعادة الاتصال", true);\n        retryButton.setOnClickListener(view -> manualRetry());\n        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(dp(250), dp(58));\n        retryParams.topMargin = dp(4);\n        modal.addView(retryButton, retryParams);\n\n        Button exitError = BlofyUi.button(this, "رجوع", false);\n        exitError.setOnClickListener(view -> {\n            cancelResolve(true);\n            releasePlayer();\n            finish();\n        });\n        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(dp(250), dp(54));\n        exitParams.topMargin = dp(10);\n        modal.addView(exitError, exitParams);\n        retryButton.setNextFocusDownId(exitError.getId());\n        exitError.setNextFocusUpId(retryButton.getId());\n\n        errorPanel.addView(modal, new LinearLayout.LayoutParams(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT));\n'''
if old not in text:
    raise SystemExit("error modal anchor not found")
text = text.replace(old, new, 1)

text = text.replace('''        retryButton.setText("إعادة المحاولة");\n        retryButton.requestFocus();\n''', '''        retryButton.setEnabled(true);\n        retryButton.setText("إعادة المحاولة");\n        retryButton.requestFocus();\n''', 1)

text = text.replace('''        retryButton.setText("إعادة المحاولة من البداية");\n        retryButton.requestFocus();\n''', '''        retryButton.setEnabled(true);\n        retryButton.setText("إعادة المحاولة من البداية");\n        retryButton.requestFocus();\n''', 1)

old_retry = '''    private void manualRetry() {\n        recoveryStep = preferredRecoveryStep();\n'''
new_retry = '''    private void manualRetry() {\n        if (retryButton != null && !retryButton.isEnabled()) return;\n        if (retryButton != null) {\n            retryButton.setEnabled(false);\n            retryButton.setText("جاري المحاولة…");\n        }\n        recoveryStep = preferredRecoveryStep();\n'''
if old_retry not in text:
    raise SystemExit("manualRetry anchor not found")
text = text.replace(old_retry, new_retry, 1)

PLAYER.write_text(text)
print("v332 remote-safe failure UI patch applied")
