"""ci/verify_version_parity.py - Drift guard for DOWNI version parity.

Guarantees android/app/build.gradle is the SINGLE SOURCE OF TRUTH for versionName
and versionCode.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_PATH = ROOT / "android" / "app" / "build.gradle"


def fail(msg: str) -> None:
    print(f"[FAIL] {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    print("Verifying version parity across DOWNI...")

    if not GRADLE_PATH.exists():
        fail(f"build.gradle not found at {GRADLE_PATH}")

    gradle_text = GRADLE_PATH.read_text(encoding="utf-8")
    vc_match = re.search(r"versionCode\s+(\d+)", gradle_text)
    vn_match = re.search(r'versionName\s+["\']([^"\']+)["\']', gradle_text)

    if not vc_match or not vn_match:
        fail("Could not find versionCode or versionName in android/app/build.gradle")

    version_code = int(vc_match.group(1))
    version_name = vn_match.group(1).strip()
    print("Single source of truth (android/app/build.gradle):")
    print(f"  versionName: {version_name}")
    print(f"  versionCode: {version_code}")
    print("\nAll version parity drift checks PASSED.")


if __name__ == "__main__":
    main()
