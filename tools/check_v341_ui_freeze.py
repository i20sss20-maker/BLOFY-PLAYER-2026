#!/usr/bin/env python3
"""CI guard for the exact BLOFY PLAYER v340 R6 presentation.

Run this after recreating the v340 R6 source and applying the v341 playback
patch.  Playback internals may change, but the approved theme, QR/registration
flow, TV navigation, screen composition, and player controls must remain
identical to the v340 R6 APK.

The two player activities intentionally are not hashed as whole files because
they still contain the playback engines.  Their presentation is protected by
hashing UI methods, View fields, and a projection of UI-building API calls.
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP_MAIN = ROOT / "BLOFY-ANDROID-2026" / "app" / "src" / "main"
JAVA = APP_MAIN / "java" / "tv" / "blofy" / "player"


# SHA-256 values are from the effective v340 R6 source after the complete R6
# patch chain, not from the raw 7220dc78 branch checkout.
HARD_FROZEN_FILES = {
    "AndroidManifest.xml":
        "d660fff1e9a4a212374185df1038cdfcd641e1732f98cb93d1aba117156e2473",
    "java/tv/blofy/player/BlofyUi.java":
        "df269d53110f77a568375739a197d3a02c72beb476f5b3600fcf789e8c4c0993",
    "java/tv/blofy/player/MainActivity.java":
        "6ed66ba6e1707f5ef9f879cd9d1c90bcde2d1fe8282fab44d7443ad2021276ee",
    "java/tv/blofy/player/SevenMaxActivity.java":
        "4b4d1669ad1c4336671a891620445ed851f20d420e75cecd4f5551ce2c123aef",
    "java/tv/blofy/player/DetailsActivity.java":
        "7acbf2334a121b93148186e09cc7adbae568793a920c72a11b006906ac6480dc",
    "java/tv/blofy/player/SettingsActivity.java":
        "0c3f6d8be1c29fa8578f731bdbd7dbc99a77b84ac97670fde151eba61dd06c34",
    "java/tv/blofy/player/LiveChannelOverlay.java":
        "a5458ae9d1d3717a6897aa858a029887fd57e898f317caf479bc5932cfad46e6",
    "java/tv/blofy/player/ImageLoader.java":
        "ad214ea3226a69f1973363001722c9039663fb06ae29787859aeab2e03924c55",
    # Protect the public device ID/pairing material displayed beside the QR.
    "java/tv/blofy/player/DeviceIdentity.java":
        "ddec3973a71604e5481efb4bd9ec7f41ef8880cb40d083f0f881c7f67da1ab43",
    "res/drawable/blofy_logo.png":
        "b00aa9368a0bf76d224911980fd829cddd14aeccfafc700babfd4c2aefd9a12b",
    "res/drawable/tv_banner.xml":
        "f95bd270454d8c8f872140c23f04924f0c44e1881cf32f5c973b3b2cb2d9925c",
    "res/values/colors.xml":
        "bb71c07288a6cbdb1b13abcddbbd1aafc99ac1825dbc5957a6b5c6263315a6a8",
    "res/values/strings.xml":
        "1efc726373edcc345f5aede8c96759cb62be475105cd70649035be630967171b",
    "res/values/styles.xml":
        "005b6d1bd84b0a82e0b94614def1efe67bb5b99049263c61e7f005aca5a6fd61",
}


# No new presentation resource directory/file may silently bypass the hashes
# above. res/xml is deliberately outside this set because transport/security
# configuration is not presentation and may require a separately reviewed fix.
EXPECTED_PRESENTATION_RESOURCES = {
    "res/drawable/blofy_logo.png",
    "res/drawable/tv_banner.xml",
    "res/values/colors.xml",
    "res/values/strings.xml",
    "res/values/styles.xml",
}
PRESENTATION_RESOURCE_PREFIXES = (
    "anim",
    "animator",
    "color",
    "drawable",
    "font",
    "layout",
    "menu",
    "mipmap",
    "transition",
    "values",
)


# Exact v340 R6 hashes of methods that define player presentation, controls,
# focus/remote navigation, error panels, subtitle appearance, and system UI.
PROTECTED_METHODS = {
    "PlayerActivity.java": {
        "private int media3ResizeMode()":
            "faf5d3b621370dfd01fddd7eba34ada27d7268ef03c9e0ea665b949de9131adb",
        "private void applySubtitleStyle()":
            "327535cca94865873c821de050b4afc390eae24a386d5f1e7abb0c46d0810349",
        "private void applyVlcAspect(org.videolan.libvlc.MediaPlayer target)":
            "0dd4d5331df08b47a3adf1033b4580a0c380f5a4668afbc02c05236bd45c48ce",
        "private int dp(int value)":
            "b56f2fafac57988021caee24b6f276b975fa0dd37913352d13170ed44f055429",
        "private void buildUi()":
            "e9628f885b183b636b962d638687df85915653a4325d94e759fb463df035c0e0",
        "private void showResolveError(String message)":
            "19c15ad03f2911ba8df8b06d7e7cd32762d48add93caa7e35a9c3462f4833fc1",
        "private void requestPlaybackFocus()":
            "8f4e8fa94990747d000f3036b8f49f8d654756722925ac6b86221b287cf5618b",
        "@Override public boolean dispatchKeyEvent(KeyEvent event)":
            "b8c3305cdd81f3437c97025b7181ff5fc60cc07fe126c3e5e4ab136a06f158a8",
        "@Override public void onBackPressed()":
            "1b11b6785a743c426fa04dd7ad4bd8481f3cbb94ebb1a1ad6f6412dcd31de5b3",
        "private void hideSystemUi()":
            "abd422c7491895884ca3cd9ef16225c27b02e5c72f4b26f5da9ce98444a9707e",
        "@Override public void onWindowFocusChanged(boolean hasFocus)":
            "714bb5ecded836d806c7d087198c7780d81b8a78d5ba15b2bd2d8fddfc924314",
    },
    "VodPlayerActivity.java": {
        "private void applySubtitleStyle()":
            "c306677b5ff09afb709e851ce1f7190127dc862be26aadf7e408d49973599e42",
        "private void applyVlcAspect(org.videolan.libvlc.MediaPlayer target)":
            "17b7e14313e35de4c3ebd9e86984eb864262aae1b98d21361daa6b90713e9119",
        "private void buildUi()":
            "14c644d4744ade320f72eebdc009e8072558d0767ef7c578c7d9a4a0ac300f98",
        "private void showFinalPlaybackError(String reason)":
            "0740ec7bc0f2de45c44cca59e85396d782480c94c73f15dac20346c71c88b96d",
        "private void showError(String message)":
            "a4b5f11f4c51f86776ce9518513af451fd0972002610926bb412a221a43eea9b",
        "private void showNextEpisodePrompt(PlaybackProgress.NextEpisode next)":
            "c380aa9a06a294efae7bb3fcd80428e437e7b3b16ff94872f0ae21ce6e5d66d6",
        "private void showControls()":
            "9270479b866c4ec1faa3734312eb98e65f8324a98d4e77bfa2c603e34e232bda",
        "private boolean optionFocused()":
            "6a622662f162f202cb171f91400cb42364e0294fa3ab4d1c028e19631a8e0f53",
        "@Override public boolean dispatchKeyEvent(KeyEvent event)":
            "4146f61abc2928a25ccb977df1b9c1b8cd3d5e0f007ddaf97567756519aeedf0",
        "private void focusTrackControls()":
            "ee306f8f264e1ab73c85335097092899c084ccd50d45041d642a696e78adc2a0",
        "private void moveOptionFocus(boolean right)":
            "20044c45c1dc5f1fda17735da053d02aca6036638bbafe0152b486a6bf61a7a3",
        "private GradientDrawable playerControlsGradient()":
            "b7d47a3d3e69180339582f8b8d226d239060426550fede6a6661d525cd566e71",
        "private GradientDrawable cinemaPanel(int fill, int radiusDp, int strokeDp, int stroke)":
            "0a4457fe40b81bc8d0b694e890a42231c5a2e38f4e3d5d8bcc87aea08842bca8",
        "private StateListDrawable retryButtonBackground()":
            "e50ef04422fafe6b2f2b07562f76e7adafada38d73c8f626e5e2ef1fb72d8035",
        "private TextView transportBadge(String label)":
            "c3033e24f4d25b9655b0c10e3ec0581b305e81d709a393712f1e18ac0cf99692",
        "private TextView playerOptionButton(String label)":
            "e41e16a686e624f6dc122d7b8b334a93a81f14f3d8d1f9b1e5fd24f19316d67e",
        "private int dp(int value)":
            "748f400212db23c17a990c48f0a3234146adbe5a96bf1830e4705986a100176a",
        "private void hideSystemUi()":
            "a9091248d51871da6c3070c723585b44aaffa6a6a2d6d31ceb5974454d3d45bc",
        "@Override public void onWindowFocusChanged(boolean hasFocus)":
            "714bb5ecded836d806c7d087198c7780d81b8a78d5ba15b2bd2d8fddfc924314",
    },
}


VIEW_FIELD_PROJECTION_HASHES = {
    "PlayerActivity.java":
        "d0570e5045180a99ea04f506bc92918cf8666f3a7f49e698fd0e687de45cbfd3",
    "VodPlayerActivity.java":
        "8d31f2baa946fa2cf7a044e9334a54d2ec66ebf0ffe0ee91db12e4ced2b126f8",
}

UI_API_PROJECTION_HASHES = {
    "PlayerActivity.java":
        "2f49306313c0a3835112686fc19b815eae69e392c6f4054af9d23e7201921e18",
    "VodPlayerActivity.java":
        "efd4fe874b1b35e75d786fd9dd99a1269ea5757651b8232f430fde20b28d3396",
}


VIEW_FIELD_RE = re.compile(
    r"^\s*private\s+(?:final\s+)?(?:Button|EditText|FrameLayout|GridLayout|"
    r"ImageView|LinearLayout|PlayerView|ProgressBar|RecyclerView|SurfaceView|"
    r"TextView|View)\s+[A-Za-z_$][\w$]*(?:\s*=\s*[^;]+)?;\s*$",
    re.MULTILINE,
)

# This catches presentation added outside the known UI methods, while leaving
# resolver, retry, decoder, buffering, and engine lifecycle code editable.
UI_API_RE = re.compile(
    r"(?:new\s+(?:FrameLayout|LinearLayout|GridLayout|TextView|ImageView|Button|"
    r"EditText|ProgressBar|PlayerView|SurfaceView|RecyclerView)\b|"
    r"setContentView\s*\(|addView\s*\(|removeView\s*\(|"
    r"setBackground(?:Color|Resource|TintList)?\s*\(|setTextColor\s*\(|"
    r"setTextSize\s*\(|setPadding\s*\(|setGravity\s*\(|setTypeface\s*\(|"
    r"setAlpha\s*\(|setScale[XY]\s*\(|setLayoutParams\s*\(|"
    r"(?:FrameLayout|LinearLayout|GridLayout|RecyclerView)\.LayoutParams\s*\(|"
    r"setOnFocusChangeListener\s*\(|requestFocus\s*\(|setFocusable\s*\(|"
    r"setNextFocus|setOnKeyListener\s*\(|Color\.|GradientDrawable|"
    r"StateListDrawable|BlofyUi\.|SYSTEM_UI_FLAG|setSystemUiVisibility\s*\(|"
    r"KEYCODE_DPAD|KEYCODE_BACK|setOnClickListener\s*\()"
)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def strip_java_comments(text: str) -> str:
    """Remove comments but preserve strings, chars, newlines, and positions."""
    output: list[str] = []
    state = "code"
    escaped = False
    index = 0

    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""

        if state == "code":
            if char == '"':
                state = "string"
                output.append(char)
            elif char == "'":
                state = "char"
                output.append(char)
            elif char == "/" and following == "/":
                state = "line_comment"
                output.extend((" ", " "))
                index += 1
            elif char == "/" and following == "*":
                state = "block_comment"
                output.extend((" ", " "))
                index += 1
            else:
                output.append(char)
        elif state in ("string", "char"):
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif (state == "string" and char == '"') or (
                state == "char" and char == "'"
            ):
                state = "code"
        elif state == "line_comment":
            if char == "\n":
                output.append(char)
                state = "code"
            else:
                output.append(" ")
        else:
            if char == "*" and following == "/":
                output.extend((" ", " "))
                index += 1
                state = "code"
            elif char == "\n":
                output.append(char)
            else:
                output.append(" ")
        index += 1

    return "".join(output)


def extract_java_method(text: str, signature: str) -> str:
    """Return one complete method block, ignoring braces in comments/strings."""
    occurrences = text.count(signature)
    if occurrences != 1:
        raise ValueError(
            f"expected one occurrence of {signature!r}, found {occurrences}"
        )

    signature_start = text.find(signature)
    block_start = text.rfind("\n", 0, signature_start) + 1
    opening_brace = text.find("{", signature_start)
    if opening_brace < 0:
        raise ValueError(f"opening brace not found for {signature!r}")

    depth = 0
    state = "code"
    escaped = False
    index = opening_brace
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""

        if state == "code":
            if char == '"':
                state = "string"
            elif char == "'":
                state = "char"
            elif char == "/" and following == "/":
                state = "line_comment"
                index += 1
            elif char == "/" and following == "*":
                state = "block_comment"
                index += 1
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return text[block_start:index + 1]
        elif state in ("string", "char"):
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif (state == "string" and char == '"') or (
                state == "char" and char == "'"
            ):
                state = "code"
        elif state == "line_comment":
            if char == "\n":
                state = "code"
        elif char == "*" and following == "/":
            state = "code"
            index += 1
        index += 1

    raise ValueError(f"closing brace not found for {signature!r}")


def projection_hash(lines: list[str]) -> str:
    return sha256_bytes("\n".join(sorted(lines)).encode("utf-8"))


def presentation_resource_files() -> set[str]:
    resources = APP_MAIN / "res"
    actual: set[str] = set()
    if not resources.is_dir():
        return actual
    for path in resources.rglob("*"):
        if not path.is_file():
            continue
        resource_dir = path.relative_to(resources).parts[0]
        if resource_dir.startswith(PRESENTATION_RESOURCE_PREFIXES):
            actual.add(path.relative_to(APP_MAIN).as_posix())
    return actual


def check_hard_frozen_files(errors: list[str]) -> None:
    for relative_path, expected in HARD_FROZEN_FILES.items():
        path = APP_MAIN / relative_path
        if not path.is_file():
            errors.append(f"missing protected file: {relative_path}")
            continue
        actual = sha256_file(path)
        if actual != expected:
            errors.append(
                f"protected file changed: {relative_path}\n"
                f"    expected {expected}\n"
                f"    actual   {actual}"
            )

    actual_resources = presentation_resource_files()
    if actual_resources != EXPECTED_PRESENTATION_RESOURCES:
        for path in sorted(EXPECTED_PRESENTATION_RESOURCES - actual_resources):
            errors.append(f"missing protected presentation resource: {path}")
        for path in sorted(actual_resources - EXPECTED_PRESENTATION_RESOURCES):
            errors.append(f"new presentation resource is not allowed: {path}")


def check_player_presentation(errors: list[str]) -> None:
    for filename, methods in PROTECTED_METHODS.items():
        path = JAVA / filename
        if not path.is_file():
            errors.append(f"missing protected player source: {path.relative_to(ROOT)}")
            continue

        text = path.read_text(encoding="utf-8")
        for signature, expected in methods.items():
            try:
                method = extract_java_method(text, signature)
            except ValueError as failure:
                errors.append(f"{filename}: {failure}")
                continue
            actual = sha256_bytes(method.encode("utf-8"))
            if actual != expected:
                errors.append(
                    f"protected UI method changed: {filename}: {signature}\n"
                    f"    expected {expected}\n"
                    f"    actual   {actual}"
                )

        clean = strip_java_comments(text)
        view_fields = [match.group(0).strip() for match in VIEW_FIELD_RE.finditer(clean)]
        actual_fields = projection_hash(view_fields)
        expected_fields = VIEW_FIELD_PROJECTION_HASHES[filename]
        if actual_fields != expected_fields:
            errors.append(
                f"player View field structure changed: {filename}\n"
                f"    expected {expected_fields}\n"
                f"    actual   {actual_fields}"
            )

        ui_api_lines = [
            line.strip()
            for line in clean.splitlines()
            if UI_API_RE.search(line)
        ]
        actual_ui_api = projection_hash(ui_api_lines)
        expected_ui_api = UI_API_PROJECTION_HASHES[filename]
        if actual_ui_api != expected_ui_api:
            errors.append(
                f"player presentation API surface changed: {filename}\n"
                f"    expected {expected_ui_api}\n"
                f"    actual   {actual_ui_api}"
            )


def main() -> int:
    errors: list[str] = []
    if not APP_MAIN.is_dir():
        print(f"[v341-ui-freeze] Android source not found: {APP_MAIN}", file=sys.stderr)
        return 2

    check_hard_frozen_files(errors)
    check_player_presentation(errors)

    if errors:
        print("[v341-ui-freeze] FAILED", file=sys.stderr)
        print(
            "v341 may change playback internals only; restore the exact v340 R6 "
            "theme, QR/registration flow, navigation, and player presentation.",
            file=sys.stderr,
        )
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(
        "[v341-ui-freeze] OK: v340 R6 theme, QR, navigation, and player UI are unchanged"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
