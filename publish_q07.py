#!/usr/bin/env python3
"""Публикует сборку для OpenQiyuan: файл в релиз версии, подписанный манифест в релиз channel-beta.

  python publish_q07.py apk <версия> <apk>   весь мод (build_q07.sh), сбрасывает код на встроенный в него
  python publish_q07.py dex <версия>         только наш код (stand/asr-android/build/dex7/classes.dex)

Ключ подписи: ../openqiyuan/keys/update_signing.pem (в git не хранится), открытая часть — в AssistantManager.kt.
Требует gh и openssl.
"""
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request

REPO = "Dolir/changan-q07-ru-assistant"
CHANNEL = "channel-beta"
HERE = os.path.dirname(os.path.abspath(__file__))
KEY = os.path.join(HERE, "..", "openqiyuan", "keys", "update_signing.pem")
BASE = f"https://github.com/{REPO}/releases/download"
GH = shutil.which("gh") or r"C:\Program Files\GitHub CLI\gh.exe"
OPENSSL = shutil.which("openssl") or r"C:\Program Files\Git\usr\bin\openssl.exe"
NOTES = ("Q07 test build {}. Noncommercial use only, see LICENSE. Contains proprietary stock components owned by "
         "their rights holders. Not affiliated with Changan.")


def gh(*args, check=True):
    return subprocess.run([GH, *args, "-R", REPO], check=check, capture_output=not check)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def publish(tag, notes, *files):
    if gh("release", "view", tag, check=False).returncode != 0:
        gh("release", "create", tag, "--prerelease", "--latest=false", "--title", tag, "--notes", notes)
    gh("release", "upload", tag, "--clobber", *files)


def release_file(version, src, name, work):
    """Кладёт файл в релиз версии и возвращает его запись для манифеста."""
    tag = f"v{version}"
    path = os.path.join(work, name)
    shutil.copy(src, path)
    publish(tag, NOTES.format(version), path)
    return {"version": version, "url": f"{BASE}/{tag}/{name}", "sha256": sha256(path), "size": os.path.getsize(path)}


def main():
    kind, version = sys.argv[1], sys.argv[2]
    work = tempfile.mkdtemp()
    if kind == "apk":
        manifest = {"apk": release_file(version, sys.argv[3], f"speechassistant-q07-v{version}.apk", work)}
    else:
        with urllib.request.urlopen(f"{BASE}/{CHANNEL}/manifest.json") as r:
            manifest = json.load(r)
        dex = os.path.join(HERE, "stand", "asr-android", "build", "dex7", "classes.dex")
        manifest["dex"] = release_file(version, dex, f"classes-{version}.dex", work)
    manifest["schema"] = 1
    manifest["issued"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    body = os.path.join(work, "manifest.json")
    with open(body, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    sig = body + ".sig"
    subprocess.run([OPENSSL, "dgst", "-sha256", "-sign", KEY, "-out", sig, body], check=True)
    publish(CHANNEL, "Signed update manifest for OpenQiyuan. Not a build.", body, sig)
    shutil.rmtree(work)
    print(json.dumps(manifest, ensure_ascii=False, indent=1))


main()
