#!/usr/bin/env python3
"""Builds the acceptance fixture APKs.

    python acceptance/fixtures/build.py

Only SDK tools are used (aapt2, javac, d8, zipalign, apksigner); no Gradle, no network.
Every APK is compiled from the shared sources in src/ with its own manifest, R class and
assets/fixture.json, all generated here from content.json. Output: apk/<id>.apk and
apk/index.json. The build is deterministic: the same inputs give byte-identical APKs.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
ART = ROOT / "art"
KEYS = ROOT / "keys"
BUILD = ROOT / "build"
OUT = ROOT / "apk"

SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
           or r"C:\Users\ldm\AppData\Local\Android\Sdk")
BT = SDK / "build-tools" / "36.0.0"
ANDROID_JAR = SDK / "platforms" / "android-35" / "android.jar"
_JBR = Path(r"C:\Program Files\Android\Android Studio\jbr")
JAVA_HOME = Path(os.environ.get("MLFIXTURE_JAVA_HOME") or (str(_JBR) if _JBR.exists() else os.environ["JAVA_HOME"]))
EXE = ".exe" if os.name == "nt" else ""
BAT = ".bat" if os.name == "nt" else ""
AAPT2 = BT / f"aapt2{EXE}"
D8 = BT / f"d8{BAT}"
ZIPALIGN = BT / f"zipalign{EXE}"
APKSIGNER = BT / f"apksigner{BAT}"
JAVAC = JAVA_HOME / "bin" / f"javac{EXE}"
KEYTOOL = JAVA_HOME / "bin" / f"keytool{EXE}"

NS = "com.github.mytv.mlfixture"
STOREPASS = "mlfixture"
ALIAS = "fixture"
SIGNERS = {
    "a": ("fixture-a.jks", "CN=mlfixture A, OU=acceptance, O=mytv launcher fixtures, C=CN"),
    "b": ("fixture-b.jks", "CN=mlfixture B impostor, OU=acceptance, O=mytv launcher fixtures, C=CN"),
}
ZIP_TIME = (2008, 1, 1, 0, 0, 0)

# TvContract constants (framework values; ASPECT_RATIO_MOVIE_POSTER = 5 is the androidx value).
TYPES = {"movie": 0, "tv_series": 1, "tv_season": 2, "tv_episode": 3, "clip": 4, "event": 5,
         "channel": 6, "track": 7, "album": 8, "artist": 9, "playlist": 10, "station": 11, "game": 12}
ASPECTS = {  # name -> (POSTER_ART_ASPECT_RATIO value, file slug, width / height)
    "16:9": (0, "16x9", 16 / 9), "3:2": (1, "3x2", 3 / 2), "4:3": (2, "4x3", 4 / 3),
    "1:1": (3, "1x1", 1.0), "2:3": (4, "2x3", 2 / 3), "movie": (5, "movie", 1 / 1.441),
}
WATCH_NEXT_TYPES = {"continue": 0, "next": 1, "new": 2, "watchlist": 3}
WIDGET_LAYOUTS = {"MusicWidget": "widget_music", "WeatherWidget": "widget_weather",
                  "FitWidget": "widget_fit", "BookWidget": "widget_book", "CrashWidget": "widget_crash"}
PROBE = {"label": "验收探针", "color": "#5d7290", "probe": True}

LAUNCHER_FILTER = """            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>"""


def run(cmd, cwd=None):
    env = dict(os.environ, JAVA_HOME=str(JAVA_HOME))
    env["PATH"] = str(JAVA_HOME / "bin") + os.pathsep + env.get("PATH", "")
    p = subprocess.run([str(c) for c in cmd], cwd=cwd, env=env, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise RuntimeError(f"command failed ({p.returncode}): {' '.join(map(str, cmd))}\n{p.stdout}\n{p.stderr}")
    return p.stdout


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


# ---- keys -------------------------------------------------------------------

def ensure_keys():
    KEYS.mkdir(exist_ok=True)
    certs = {}
    for signer, (name, dname) in SIGNERS.items():
        ks = KEYS / name
        if not ks.exists():
            run([KEYTOOL, "-genkeypair", "-keystore", ks, "-storetype", "JKS", "-storepass", STOREPASS,
                 "-keypass", STOREPASS, "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
                 "-validity", "10958", "-dname", dname])
        der = BUILD / f"cert-{signer}.der"
        run([KEYTOOL, "-exportcert", "-keystore", ks, "-storepass", STOREPASS, "-alias", ALIAS, "-file", der])
        certs[signer] = sha256_file(der)
    return certs


# ---- per-app inputs ---------------------------------------------------------

def app_specs(content):
    specs = []
    for aid, data in content["apps"].items():
        specs.append({"id": aid, "pkg": f"{NS}.{aid}", "signer": "a", "data": data})
        if aid == "series":
            specs.append({"id": "series_impostor", "pkg": f"{NS}.series", "signer": "b", "data": data})
    specs.append({"id": "probe", "pkg": f"{NS}.probe", "signer": "a", "data": PROBE})
    return specs


def launch_uri(pkg, extra, value):
    return f"intent:#Intent;component={pkg}/{NS}.LauncherActivity;S.{extra}={value};end"


def program_cfg(p, pkg, assets):
    o = {"key": p["key"], "type": TYPES[p["type"]], "title": p["title"]}
    if "desc" in p:
        o["desc"] = p["desc"]
    if "art" in p:
        value, slug, ratio = ASPECTS[p["aspect"]]
        name = f"{p['art']}_{slug}.jpg"
        assets[name] = ("poster", p["art"], ratio)
        o["poster"] = name
        o["aspect"] = value
    elif "logo" in p:  # TYPE_CHANNEL card: the poster is the station logo, no aspect ratio
        name = f"logo_{p['logo']}.png"
        assets[name] = ("copy", ART / f"ico_{p['logo']}.png")
        o["poster"] = name
    elif "artUri" in p:  # poster URI that the ArtProvider cannot open
        o["poster"] = f"{p['artUri']}.jpg"
        if "aspect" in p:
            o["aspect"] = ASPECTS[p["aspect"]][0]
    target = f"{NS}.probe" if p.get("evilIntent") else pkg
    o["intent"] = launch_uri(target, "program", p["key"])
    for k in ("weight", "genre", "year", "season", "episode", "rank"):
        if k in p:
            o[k] = p[k]
    if p.get("live"):
        o["live"] = True
    if "durationMin" in p:
        dur = p["durationMin"] * 60000
        o["durationMs"] = dur
        o["positionMs"] = int(round(dur * p["progress"]))
    if "wnType" in p:
        o["wnType"] = WATCH_NEXT_TYPES[p["wnType"]]
    return o


def fixture_cfg(spec, index):
    data, pkg = spec["data"], spec["pkg"]
    icon = data.get("icon")
    assets = {}
    cfg = {
        "id": spec["id"], "package": pkg, "label": data["label"],
        "color": index[f"ico:{icon}"]["seed"] if icon else data["color"],
        "probe": bool(data.get("probe")), "bridge": bool(data.get("bridge")),
    }
    if icon:
        assets["icon.png"] = ("copy", ART / f"ico_{icon}.png")
    channels = []
    for ch in data.get("channels", []):
        channels.append({
            "key": ch["key"], "name": ch["name"], "request": bool(ch.get("request")),
            "intent": launch_uri(pkg, "channel", ch["key"]), "logo": "icon.png",
            "programs": [program_cfg(p, pkg, assets) for p in ch["programs"]],
        })
    cfg["channels"] = channels
    cfg["watchNext"] = [program_cfg(w, pkg, assets) for w in data.get("watchNext", [])]
    cfg["widgets"] = data.get("widgets", [])
    return cfg, assets


def poster(art, ratio, cache):
    """Centre crop of the 640x360 prototype art to the given width/height ratio."""
    key = (art, round(ratio, 4))
    if key not in cache:
        im = Image.open(ART / f"art_{art}.jpg").convert("RGB")
        w, h = im.size
        if w / h > ratio:
            cw, ch = int(round(h * ratio)), h
        else:
            cw, ch = w, int(round(w / ratio))
        left, top = (w - cw) // 2, (h - ch) // 2
        path = BUILD / "posters" / f"{art}_{cw}x{ch}.jpg"
        path.parent.mkdir(parents=True, exist_ok=True)
        im.crop((left, top, left + cw, top + ch)).save(path, "JPEG", quality=90, optimize=False)
        cache[key] = path
    return cache[key]


def probe_images(color):
    """Generated probe icon (160x160) and banner (320x180)."""
    rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
    icon = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
    d = ImageDraw.Draw(icon)
    d.rounded_rectangle((0, 0, 159, 159), radius=36, fill=rgb + (255,))
    d.ellipse((40, 40, 119, 119), outline=(255, 255, 255, 255), width=10)
    d.ellipse((70, 70, 89, 89), fill=(255, 255, 255, 255))
    banner = Image.new("RGB", (320, 180), rgb)
    d = ImageDraw.Draw(banner)
    d.ellipse((120, 50, 199, 129), outline=(255, 255, 255), width=10)
    d.ellipse((150, 80, 169, 99), fill=(255, 255, 255))
    return icon, banner


def widget_xml(w):
    cx, cy = (int(x) for x in w["cells"].split("x"))
    attrs = [
        ("android:minWidth", f"{70 * cx - 30}dp"),
        ("android:minHeight", f"{70 * cy - 30}dp"),
        ("android:targetCellWidth", str(cx)),
        ("android:targetCellHeight", str(cy)),
        ("android:resizeMode", "horizontal|vertical"),
        ("android:widgetCategory", "home_screen"),
        ("android:updatePeriodMillis", "0"),
        ("android:initialLayout", f"@layout/{WIDGET_LAYOUTS[w['class']]}"),
        ("android:previewImage", "@drawable/ic_app"),
    ]
    if w["kind"] == "configure":
        attrs.append(("android:configure", f"{NS}.BookConfigActivity"))
        if w.get("reconfigurable"):
            attrs.append(("android:widgetFeatures", "reconfigurable"))  # API 28+ attribute
    body = "\n".join(f"    {k}={quoteattr(v)}" for k, v in attrs)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f"{body} />\n")


def manifest(spec, cfg, all_pkgs):
    pkg = spec["pkg"]
    perms, queries, comps = [], [], []
    for w in cfg["widgets"]:
        cls = w["class"]
        comps.append(f"""
        <receiver
            android:name="{NS}.{cls}"
            android:exported="true"
            android:label={quoteattr(w['label'])}>
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/w_{cls.lower()}" />
        </receiver>""")
        if w["kind"] == "collection":
            comps.append(f"""
        <service
            android:name="{NS}.FitService"
            android:exported="false"
            android:permission="android.permission.BIND_REMOTEVIEWS" />""")
        if w["kind"] == "configure":
            comps.append(f"""
        <activity
            android:name="{NS}.BookConfigActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>""")
    if cfg["bridge"]:
        comps.append(f"""
        <provider
            android:name="{NS}.BridgeProvider"
            android:authorities="{pkg}.launcherbridge"
            android:exported="true">
            <intent-filter>
                <action android:name="com.github.mytv.action.LAUNCHER_BRIDGE" />
            </intent-filter>
        </provider>""")
    if cfg["probe"]:
        perms += ["android.permission.READ_TV_LISTINGS", "com.android.providers.tv.permission.READ_EPG_DATA"]
        for p in sorted(set(all_pkgs) - {pkg}):
            queries.append(f'        <package android:name="{p}" />')
        for action in ("android.appwidget.action.APPWIDGET_BIND", "android.appwidget.action.APPWIDGET_UPDATE",
                       "android.media.tv.action.INITIALIZE_PROGRAMS",
                       "android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED",
                       "com.github.mytv.action.LAUNCHER_BRIDGE"):
            queries.append(f'        <intent>\n            <action android:name="{action}" />\n        </intent>')
        queries.append('        <intent>\n            <action android:name="android.intent.action.MAIN" />\n'
                       '            <category android:name="android.intent.category.HOME" />\n        </intent>')
        comps.append(f"""
        <activity
            android:name="{NS}.ProbeActivity"
            android:banner="@drawable/banner"
            android:exported="true"
            android:launchMode="singleTask">
{LAUNCHER_FILTER}
        </activity>

        <receiver
            android:name="{NS}.BrowsableRequestedReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED" />
            </intent-filter>
        </receiver>""")
    text = (SRC / "AndroidManifest.xml.in").read_text(encoding="utf-8")
    text = text.replace("@PERMISSIONS@", "\n".join(f'    <uses-permission android:name="{p}" />' for p in perms))
    text = text.replace("@QUERIES@", "\n".join(queries))
    text = text.replace("@LAUNCHER_FILTER@", "" if cfg["probe"] else LAUNCHER_FILTER)
    text = text.replace("@COMPONENTS@", "\n".join(comps))
    text = text.replace("@PKG@", pkg)
    return re.sub(r"\n\s*\n(\s*\n)+", "\n\n", text)


def prepare(spec, index, all_pkgs, poster_cache):
    d = BUILD / spec["id"]
    cfg, assets = fixture_cfg(spec, index)
    art_dir = d / "assets" / "art"
    art_dir.mkdir(parents=True)
    for name, src in sorted(assets.items()):
        if src[0] == "copy":
            shutil.copyfile(src[1], art_dir / name)
        else:
            shutil.copyfile(poster(src[1], src[2], poster_cache), art_dir / name)
    (d / "assets" / "fixture.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=1) + "\n",
                                               encoding="utf-8", newline="\n")
    res = d / "res"
    for sub in ("values", "drawable-xxhdpi", "drawable-xhdpi", "xml"):
        (res / sub).mkdir(parents=True)
    (res / "values" / "strings.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <string name="app_name">{escape(cfg["label"])}</string>\n</resources>\n',
        encoding="utf-8", newline="\n")
    icon = spec["data"].get("icon")
    if icon:
        shutil.copyfile(ART / f"ico_{icon}.png", res / "drawable-xxhdpi" / "ic_launcher.png")
        shutil.copyfile(ART / f"ico_{icon}.png", res / "drawable-xxhdpi" / "ic_app.png")
        shutil.copyfile(ART / f"app_{icon}.jpg", res / "drawable-xhdpi" / "banner.jpg")
    else:
        ic, banner = probe_images(cfg["color"])
        ic.save(res / "drawable-xxhdpi" / "ic_launcher.png")
        ic.save(res / "drawable-xxhdpi" / "ic_app.png")
        ic.save(d / "assets" / "art" / "icon.png")
        banner.save(res / "drawable-xhdpi" / "banner.jpg", "JPEG", quality=90)
    for w in cfg["widgets"]:
        (res / "xml" / f"w_{w['class'].lower()}.xml").write_text(widget_xml(w), encoding="utf-8", newline="\n")
    if not cfg["widgets"]:
        (res / "xml").rmdir()
    (d / "AndroidManifest.xml").write_text(manifest(spec, cfg, all_pkgs), encoding="utf-8", newline="\n")


# ---- per-app build ----------------------------------------------------------

def zip_dir(src_dir, dest):
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(p for p in src_dir.rglob("*") if p.is_file()):
            zi = zipfile.ZipInfo(f.relative_to(src_dir).as_posix(), ZIP_TIME)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.create_system = 0
            z.writestr(zi, f.read_bytes())


def merge_apk(res_apk, dex, dest):
    with zipfile.ZipFile(res_apk) as src, zipfile.ZipFile(dest, "w") as out:
        for info in src.infolist():
            zi = zipfile.ZipInfo(info.filename, ZIP_TIME)
            zi.compress_type = info.compress_type
            zi.create_system = 0
            zi.external_attr = 0
            out.writestr(zi, src.read(info.filename))
        zi = zipfile.ZipInfo("classes.dex", ZIP_TIME)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.create_system = 0
        out.writestr(zi, dex.read_bytes())


def build_app(spec, java_sources):
    t0 = time.time()
    d = BUILD / spec["id"]
    run([AAPT2, "compile", "--dir", d / "res", "-o", d / "res.zip"])
    (d / "gen").mkdir()
    run([AAPT2, "link", "-o", d / "res.apk", "-I", ANDROID_JAR, "--manifest", d / "AndroidManifest.xml",
         "--java", d / "gen", "--custom-package", NS, "-A", d / "assets",
         "--min-sdk-version", "23", "--target-sdk-version", "35", "-0", "jpg", "-0", "png",
         BUILD / "common.zip", d / "res.zip"])
    classes = d / "classes"
    classes.mkdir()
    r_java = d / "gen" / Path(*NS.split(".")) / "R.java"
    run([JAVAC, "-source", "8", "-target", "8", "-bootclasspath", ANDROID_JAR,
         "-classpath", BT / "core-lambda-stubs.jar", "-encoding", "UTF-8", "-nowarn", "-Xlint:-options",
         "-d", classes, *java_sources, r_java])
    zip_dir(classes, d / "classes.jar")
    (d / "dex").mkdir()
    run([D8, "--release", "--min-api", "23", "--lib", ANDROID_JAR, "--output", d / "dex", d / "classes.jar"])
    merge_apk(d / "res.apk", d / "dex" / "classes.dex", d / "unaligned.apk")
    run([ZIPALIGN, "-p", "-f", "4", d / "unaligned.apk", d / "aligned.apk"])
    out = OUT / f"{spec['id']}.apk"
    run([APKSIGNER, "sign", "--ks", KEYS / SIGNERS[spec["signer"]][0], "--ks-key-alias", ALIAS,
         "--ks-pass", f"pass:{STOREPASS}", "--key-pass", f"pass:{STOREPASS}", "--min-sdk-version", "23",
         "--v4-signing-enabled", "false", "--out", out, d / "aligned.apk"])
    return {"id": spec["id"], "file": out.name, "package": spec["pkg"], "signer": spec["signer"].upper(),
            "sha256": sha256_file(out), "certSha256": signed_cert(out, spec["signer"]),
            "seconds": round(time.time() - t0, 1)}


def signed_cert(apk, signer):
    """SHA-256 of the signer certificate, if its DER bytes are embedded in the v1 signature block."""
    der = (BUILD / f"cert-{signer}.der").read_bytes()
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if name.startswith("META-INF/") and name.endswith((".RSA", ".EC", ".DSA")) and der in z.read(name):
                return hashlib.sha256(der).hexdigest()
    return None


def main():
    t0 = time.time()
    for tool in (AAPT2, D8, ZIPALIGN, APKSIGNER, JAVAC, KEYTOOL, ANDROID_JAR):
        if not tool.exists():
            sys.exit(f"missing tool: {tool}")
    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir()
    OUT.mkdir(exist_ok=True)
    for old in OUT.glob("*.apk"):
        old.unlink()

    certs = ensure_keys()
    content = json.loads((ROOT / "content.json").read_text(encoding="utf-8"))
    index = json.loads((ART / "index.json").read_text(encoding="utf-8"))
    specs = app_specs(content)
    all_pkgs = [s["pkg"] for s in specs]
    poster_cache = {}
    for spec in specs:
        prepare(spec, index, all_pkgs, poster_cache)
    run([AAPT2, "compile", "--dir", SRC / "res", "-o", BUILD / "common.zip"])
    java_sources = sorted((SRC / "java").rglob("*.java"))

    with ThreadPoolExecutor(max_workers=min(8, os.cpu_count() or 4)) as pool:
        results = list(pool.map(lambda s: build_app(s, java_sources), specs))

    for r in results:
        expected = certs[r["signer"].lower()]
        if r["certSha256"] != expected:
            sys.exit(f"{r['id']}: signed with {r['certSha256']}, expected {expected}")
    idx = {"certA": certs["a"], "certB": certs["b"],
           "apks": [{k: r[k] for k in ("id", "file", "package", "signer", "sha256", "certSha256")}
                    for r in results]}
    (OUT / "index.json").write_text(json.dumps(idx, indent=1) + "\n", encoding="utf-8", newline="\n")
    for r in results:
        print(f"{r['id']:16} {r['package']:36} {r['signer']}  {r['sha256'][:16]}  {r['seconds']:5.1f}s")
    print(f"cert A {certs['a']}")
    print(f"cert B {certs['b']}")
    print(f"{len(results)} APKs in {time.time() - t0:.1f}s -> {OUT}")


if __name__ == "__main__":
    main()
