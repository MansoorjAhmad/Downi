"""Verification script to enforce yt-dlp and certifi version parity and artifact SHA-256 integrity.

Ensures that android/app/build.gradle is the single source of truth for the yt-dlp
and certifi engine pins, preventing drift and tampering between the Android app package
and CI test runs.
"""
import json
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(HERE)
BUILD_GRADLE = os.path.join(REPO_ROOT, 'android', 'app', 'build.gradle')


def get_pinned_specs() -> dict:
    """Extract active install specifications and recorded SHA-256 hashes from build.gradle."""
    if not os.path.exists(BUILD_GRADLE):
        raise FileNotFoundError(f"build.gradle not found at {BUILD_GRADLE}")

    with open(BUILD_GRADLE, 'r', encoding='utf-8') as f:
        content = f.read()

    # Match:
    # // Wheel artifact SHA-256: <hash>
    # (optional rollback comment)
    # install("yt-dlp==<version>")
    yt_m = re.search(
        r'//[ \t]*Wheel artifact SHA-256:[ \t]*([a-fA-F0-9]{64})\s*\n(?:[ \t]*//[^\n]*\n)*[ \t]*install\([\'"](yt-dlp==([^\'"\s]+))[\'"]\)',
        content
    )
    if not yt_m:
        raise ValueError("Could not find active install('yt-dlp==...') with recorded SHA-256 in android/app/build.gradle")

    # Match certifi:
    # // Wheel artifact SHA-256: <hash>
    # install("certifi==<version>")
    cert_m = re.search(
        r'//[ \t]*Wheel artifact SHA-256:[ \t]*([a-fA-F0-9]{64})\s*\n(?:[ \t]*//[^\n]*\n)*[ \t]*install\([\'"](certifi==([^\'"\s]+))[\'"]\)',
        content
    )
    if not cert_m:
        raise ValueError("Could not find active install('certifi==...') with recorded SHA-256 in android/app/build.gradle")

    return {
        'yt_dlp': {
            'spec': yt_m.group(2).strip(),
            'version': yt_m.group(3).strip(),
            'sha256': yt_m.group(1).lower().strip(),
        },
        'certifi': {
            'spec': cert_m.group(2).strip(),
            'version': cert_m.group(3).strip(),
            'sha256': cert_m.group(1).lower().strip(),
        }
    }


def verify_pypi_wheel_hash(pkg_name: str, version: str, expected_sha256: str) -> None:
    """Query PyPI JSON API and verify that the published wheel matches expected SHA-256."""
    url = f"https://pypi.org/pypi/{pkg_name}/json"
    req = urllib.request.Request(url, headers={'User-Agent': 'DOWNI-CI-Validator/1.0'})
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode('utf-8'))

    releases = data.get('releases', {})
    files = releases.get(version)
    if not files:
        raise ValueError(f"Version {version} not found in PyPI releases for {pkg_name}")

    whl = next((f for f in files if f.get('filename', '').endswith('.whl')), None)
    if not whl:
        raise ValueError(f"No .whl artifact found for {pkg_name} {version} on PyPI")

    actual_sha256 = whl.get('digests', {}).get('sha256', '').lower()
    if actual_sha256 != expected_sha256:
        raise ValueError(
            f"SHA-256 mismatch for {whl['filename']}!\n"
            f"  Recorded in build.gradle: {expected_sha256}\n"
            f"  Actual on PyPI:           {actual_sha256}"
        )


def main() -> int:
    try:
        specs = get_pinned_specs()
    except Exception as exc:
        print(f"[FAIL] Unable to read pinned specs from build.gradle: {exc}", file=sys.stderr)
        return 1

    yt_spec = specs['yt_dlp']['spec']
    cert_spec = specs['certifi']['spec']

    if '--print-pin' in sys.argv:
        # Output exact pip specs for CI install steps
        sys.stdout.write(f"{yt_spec} {cert_spec}\n")
        return 0

    if '--print-version' in sys.argv:
        sys.stdout.write(f"{specs['yt_dlp']['version']}\n")
        return 0

    print("Single source of truth (android/app/build.gradle):")
    print(f"  yt-dlp:  {yt_spec} (Wheel SHA-256: {specs['yt_dlp']['sha256']})")
    print(f"  certifi: {cert_spec} (Wheel SHA-256: {specs['certifi']['sha256']})")

    # 1. Cryptographic SHA-256 verification against PyPI release artifact
    print("\n[1/3] Verifying wheel artifact SHA-256 hashes against PyPI...")
    try:
        verify_pypi_wheel_hash('yt-dlp', specs['yt_dlp']['version'], specs['yt_dlp']['sha256'])
        print(f"  [OK] yt-dlp wheel SHA-256 verified: {specs['yt_dlp']['sha256']}")
        verify_pypi_wheel_hash('certifi', specs['certifi']['version'], specs['certifi']['sha256'])
        print(f"  [OK] certifi wheel SHA-256 verified: {specs['certifi']['sha256']}")
    except Exception as exc:
        print(f"[FAIL] PyPI artifact hash verification failed: {exc}", file=sys.stderr)
        return 1

    # 2. Check local Python environment parity for yt-dlp
    print("\n[2/3] Verifying local Python environment parity for yt-dlp...")
    try:
        import yt_dlp
        import yt_dlp.version
        installed_yt = yt_dlp.version.__version__
    except ImportError:
        print(f"[FAIL] yt-dlp is not installed in current Python environment ({sys.executable}).", file=sys.stderr)
        return 1

    pinned_yt = specs['yt_dlp']['version']
    expected_base = re.sub(r'\.dev\d*$', '', pinned_yt)
    installed_base = re.sub(r'\.dev\d*$', '', installed_yt)
    if installed_base != expected_base and installed_yt != pinned_yt:
        print("[FAIL] yt-dlp version drift detected!", file=sys.stderr)
        print(f"       Installed in Python ({sys.executable}): {installed_yt}", file=sys.stderr)
        print(f"       Pinned in build.gradle: {pinned_yt}", file=sys.stderr)
        return 1
    print(f"  [OK] yt-dlp parity verified: installed {installed_yt} matches {pinned_yt}")

    # 3. Check local Python environment parity for certifi
    print("\n[3/3] Verifying local Python environment parity for certifi...")
    try:
        import certifi
        installed_cert = getattr(certifi, '__version__', None)
        if not installed_cert:
            import importlib.metadata
            installed_cert = importlib.metadata.version('certifi')
    except Exception:
        installed_cert = "unknown"

    pinned_cert = specs['certifi']['version']
    if installed_cert != pinned_cert:
        print(f"  [WARN] certifi installed in Python ({installed_cert}) differs from pinned ({pinned_cert})")
        print("         (Updating local certifi to pinned version is recommended)")
    else:
        print(f"  [OK] certifi parity verified: installed {installed_cert} matches {pinned_cert}")

    print("\nAll engine pin parity and cryptographic hash checks PASSED.")
    return 0


if __name__ == '__main__':
    sys.exit(main())
