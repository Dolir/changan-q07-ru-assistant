#!/usr/bin/env python3
"""Публикует сборку для OpenQiyuan: файл в релиз версии, подписанный манифест в релиз channel-beta.

  python publish_q07.py apk <версия> <apk>   весь мод (build_q07.sh), сбрасывает код на встроенный в него
  python publish_q07.py dex <версия>         только наш код (stand/asr-android/build/dex7/classes.dex)
  python publish_q07.py models <версия> <apk с моделями>   пакеты моделей: OpenQiyuan скачивает их один раз
  python publish_q07.py voice <версия> <имя> <название> <папка>   голос Piper из папки, по выбору в OpenQiyuan
  python publish_q07.py widget <версия> <apk>   рабочий стол с бегущей строкой, по выбору в OpenQiyuan
  python publish_q07.py app <версия> <apk>   сам OpenQiyuan; версия сверяется с APK, код версии берётся оттуда

Ключ подписи: ../openqiyuan/keys/update_signing.pem (в git не хранится), открытая часть — в AssistantManager.kt.
Требует gh и openssl, для app ещё aapt2 из Android SDK.
"""
import datetime
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

REPO = "Dolir/changan-q07-ru-assistant"
CHANNEL = "channel-beta"
HERE = os.path.dirname(os.path.abspath(__file__))
KEY = os.path.join(HERE, "..", "openqiyuan", "keys", "update_signing.pem")
BASE = f"https://github.com/{REPO}/releases/download"
GH = shutil.which("gh") or r"C:\Program Files\GitHub CLI\gh.exe"
OPENSSL = shutil.which("openssl") or r"C:\Program Files\Git\usr\bin\openssl.exe"
MODELS = {"gigaam": "распознавание речи", "tera": "голос"}
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


def model_packs(version, src, work):
    """Пакет на каждую модель: по файлам с размерами OpenQiyuan видит, что модель уже на месте."""
    packs = {}
    with zipfile.ZipFile(src) as apk:
        for name, title in MODELS.items():
            prefix = f"assets/{name}/"
            pack = os.path.join(work, f"{name}.zip")
            files = {}
            with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as out:
                for info in apk.infolist():
                    if info.filename.startswith(prefix) and not info.is_dir() and not info.filename.endswith("/.sig"):
                        rel = info.filename[len(prefix):]
                        files[rel] = info.file_size
                        with apk.open(info) as data, out.open(rel, "w", force_zip64=True) as to:
                            shutil.copyfileobj(data, to, 1 << 20)
            entry = release_file(version, pack, f"{name}-{version}.zip", work + "/out")
            packs[name] = {**entry, "title": title, "files": files}
    return packs


def voice_pack(name, title, version, src, work):
    """Голос из папки: один .onnx, tokens.txt и espeak-ng-data."""
    pack = os.path.join(work, f"{name}.zip")
    files = {}
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as out:
        for root, _, names in os.walk(src):
            for n in names:
                path = os.path.join(root, n)
                rel = os.path.relpath(path, src).replace(os.sep, "/")
                files[rel] = os.path.getsize(path)
                out.write(path, rel)
    entry = release_file(version, pack, f"{name}-{version}.zip", work + "/out")
    return {**entry, "title": title, "files": files}


def app_entry(version, src, work):
    """Отдельный тег: у OpenQiyuan своя нумерация и своя лицензия, а не сборка ассистента."""
    aapt = sorted(glob.glob(os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "build-tools", "*", "aapt2*")))[-1]
    badging = subprocess.run([aapt, "dump", "badging", src], check=True, capture_output=True, encoding="utf8", errors="replace").stdout
    found = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if found.group(1) != "org.openqiyuan.app" or found.group(3) != version:
        sys.exit(f"в APK {found.group(1)} {found.group(3)}, ожидался org.openqiyuan.app {version}")
    tag = f"openqiyuan-v{version}"
    name = f"openqiyuan-{version}.apk"
    path = os.path.join(work, name)
    shutil.copy(src, path)
    publish(tag, f"OpenQiyuan {version} for Q07, GPL-3.0. Not affiliated with Changan.", path)
    return {"version": version, "code": int(found.group(2)), "url": f"{BASE}/{tag}/{name}", "sha256": sha256(path),
            "size": os.path.getsize(path), "title": "OpenQiyuan"}


def current_manifest():
    """Подпись проверяем до правки: иначе подменённый на GitHub манифест ушёл бы на машины с нашей подписью."""
    with tempfile.TemporaryDirectory() as tmp:
        body, sig = os.path.join(tmp, "manifest.json"), os.path.join(tmp, "manifest.json.sig")
        for path in (body, sig):
            with urllib.request.urlopen(f"{BASE}/{CHANNEL}/{os.path.basename(path)}") as r, open(path, "wb") as f:
                shutil.copyfileobj(r, f)
        if subprocess.run([OPENSSL, "dgst", "-sha256", "-prverify", KEY, "-signature", sig, body], capture_output=True).returncode != 0:
            sys.exit("подпись текущего манифеста неверна: CDN мог отдать файлы разных публикаций, повтори позже; если не проходит — манифест подменён")
        with open(body, encoding="utf-8") as f:
            return json.load(f)


def main():
    kind, version = sys.argv[1], sys.argv[2]
    work = tempfile.mkdtemp()
    os.makedirs(work + "/out")
    manifest = current_manifest()
    if kind == "app":
        manifest["app"] = app_entry(version, sys.argv[3], work)
    elif kind == "widget":
        manifest["widget"] = release_file(version, sys.argv[3], f"launcher-q07-v{version}.apk", work)
    elif kind == "apk":
        manifest.pop("dex", None)
        manifest["apk"] = release_file(version, sys.argv[3], f"speechassistant-q07-v{version}.apk", work)
    elif kind == "models":
        manifest["models"] = {**manifest.get("models", {}), **model_packs(version, sys.argv[3], work)}
    elif kind == "voice":
        name, title = sys.argv[3], sys.argv[4]
        manifest.setdefault("models", {})[name] = voice_pack(name, title, version, sys.argv[5], work)
    else:
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
