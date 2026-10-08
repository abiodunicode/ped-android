#!/usr/bin/env python3
"""Read APK version and signing information before using the PED publishing page.
Usage: python3 scripts/inspect_release.py path/to/ped.apk
Requires the Android SDK build-tools already used to build the app.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit(__doc__)
apk = Path(sys.argv[1]).resolve(strict=True)
roots = [os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'), str(Path.home() / 'Library/Android/sdk'), str(Path.home() / 'Android/Sdk')]
build_tools = next((Path(root) / 'build-tools' for root in roots if root and (Path(root) / 'build-tools').is_dir()), None)
if build_tools is None:
    raise SystemExit('Set ANDROID_HOME to your Android SDK directory.')
tools = next((p for p in sorted(build_tools.iterdir(), reverse=True) if (p / 'aapt').is_file() and (p / 'apksigner').is_file()), None)
if tools is None:
    raise SystemExit('Install Android SDK build-tools containing aapt and apksigner.')
env = os.environ.copy()
mac_java = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
if not env.get('JAVA_HOME') and mac_java.is_dir():
    env['JAVA_HOME'] = str(mac_java)
try:
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], env=env, text=True, stderr=subprocess.STDOUT)
    signing = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], env=env, text=True, stderr=subprocess.STDOUT)
except subprocess.CalledProcessError as error:
    raise SystemExit('APK inspection/signature verification failed:\n' + error.output)
package = re.search(r'A: package="([^"]+)"', badging)
code = re.search(r'android:versionCode[^=]*=\(type 0x[0-9a-f]+\)(0x[0-9a-f]+)', badging)
version = re.search(r'android:versionName[^=]*="([^"]+)"', badging)
if not package or not code or not version:
    raise SystemExit('Could not read the APK version.')
certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signing)
if not certificates:
    raise SystemExit('Could not read the signing certificate.')
sha = hashlib.sha256()
with apk.open('rb') as file:
    for chunk in iter(lambda: file.read(1024 * 1024), b''):
        sha.update(chunk)
print(json.dumps({'apk': apk.name, 'package': package[1], 'version_code': int(code[1], 16), 'version_name': version[1],
    'debuggable': bool(re.search(r'android:debuggable[^=]*=\(type 0x12\)0xffffffff', badging)), 'size_bytes': apk.stat().st_size,
    'sha256': sha.hexdigest(), 'signer_sha256': certificates}, indent=2))
