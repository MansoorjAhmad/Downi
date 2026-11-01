"""ci/verify_version_parity.py - Drift guard for DOWNI version parity.

Guarantees android/app/build.gradle is the SINGLE SOURCE OF TRUTH for versionName
and versionCode. Verifies that package.json, DowniEnginePlugin.java, and www/index.html
do not declare divergent or stale hardcoded versions.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_PATH = ROOT / "android" / "app" / "build.gradle"
PACKAGE_JSON_PATH = ROOT / "package.json"
PLUGIN_JAVA_PATH = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "omnidownloader" / "app" / "DowniEnginePlugin.java"
WWW_INDEX_PATH = ROOT / "www" / "index.html"
BUNDLED_INDEX_PATH = ROOT / "android" / "app" / "src" / "main" / "assets" / "public" / "index.html"


def fail(msg: str) -> None:
    print(f"[FAIL] {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    print("Verifying version parity across DOWNI...")

    # 1. Parse Single Source of Truth: build.gradle
    if not GRADLE_PATH.exists():
        fail(f"build.gradle not found at {GRADLE_PATH}")

    gradle_text = GRADLE_PATH.read_text(encoding="utf-8")
    vc_match = re.search(r"versionCode\s+(\d+)", gradle_text)
    vn_match = re.search(r'versionName\s+["\']([^"\']+)["\']', gradle_text)

    if not vc_match or not vn_match:
        fail("Could not find versionCode or versionName in android/app/build.gradle")

    version_code = int(vc_match.group(1))
    version_name = vn_match.group(1).strip()
    print(f"Single source of truth (android/app/build.gradle):")
    print(f"  versionName: {version_name}")
    print(f"  versionCode: {version_code}")

    # 2. Check package.json
    if not PACKAGE_JSON_PATH.exists():
        fail(f"package.json not found at {PACKAGE_JSON_PATH}")

    pkg_data = json.loads(PACKAGE_JSON_PATH.read_text(encoding="utf-8"))
    pkg_ver = pkg_data.get("version")
    if pkg_ver != version_name:
        fail(f"package.json version ('{pkg_ver}') does not match build.gradle versionName ('{version_name}')")
    print(f"  [OK] package.json version matches: {pkg_ver}")

    # 3. Check DowniEnginePlugin.java
    if not PLUGIN_JAVA_PATH.exists():
        fail(f"DowniEnginePlugin.java not found at {PLUGIN_JAVA_PATH}")

    plugin_text = PLUGIN_JAVA_PATH.read_text(encoding="utf-8")
    # Must use BuildConfig.VERSION_NAME and BuildConfig.VERSION_CODE
    if "BuildConfig.VERSION_NAME" not in plugin_text or "BuildConfig.VERSION_CODE" not in plugin_text:
        fail("DowniEnginePlugin.java must read from BuildConfig.VERSION_NAME and BuildConfig.VERSION_CODE")

    # Guard against stale hardcoded literals in DowniEnginePlugin
    stale_patterns = [
        r'result\.put\("versionName",\s*"[0-9.]+"\)',
        r'result\.put\("versionCode",\s*[0-9]+L?\)',
    ]
    for pat in stale_patterns:
        if re.search(pat, plugin_text):
            fail(f"DowniEnginePlugin.java contains hardcoded version literal matching {pat}")
    print("  [OK] DowniEnginePlugin.java uses BuildConfig single source of truth")

    # 4. Check www/index.html & bundled index.html
    for html_path, label in [(WWW_INDEX_PATH, "www/index.html"), (BUNDLED_INDEX_PATH, "assets/public/index.html")]:
        if not html_path.exists():
            fail(f"{label} not found at {html_path}")
        html_text = html_path.read_text(encoding="utf-8")
        if re.search(r"let\s+installedVersion\s*=\s*['\"][0-9.]+['\"]", html_text):
            fail(f"{label} declares hardcoded installedVersion string. Must initialize empty and read from native.")
        if re.search(r'id=["\']appVersionDisplay["\'][^>]*>v[0-9.]+', html_text):
            fail(f"{label} has hardcoded version string in appVersionDisplay markup.")
        if re.search(r'id=["\']aboutVersion["\'][^>]*>v[0-9.]+', html_text):
            fail(f"{label} has hardcoded version string in aboutVersion markup.")
        print(f"  [OK] {label} has no hardcoded version drift")

    print("\nAll version parity drift checks PASSED.")


if __name__ == "__main__":
    main()
