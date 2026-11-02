"""静态门禁：从源码构建，检查 APK、依赖、清单、模块、源码与文案。"""
from __future__ import annotations

import json
import re
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from .base import (AAPT2, APKANALYZER, FAIL, PASS, PROJECT, SKIP, WARN, KIT, Result, kit_verify, ms_le, run, sha256_file, spec)

A = "{http://schemas.android.com/apk/res/android}"
PROJ = spec("project")
VER = spec("versions")
SKIP_DIRS = {"build", ".gradle", ".idea", "acceptance", ".git", "node_modules", ".kotlin"}


def gradlew():
    for n in ("gradlew.bat", "gradlew"):
        if (PROJECT / n).exists():
            return str(PROJECT / n)
    return None


def project_files(globpat):
    for p in PROJECT.rglob(globpat):
        rel = p.relative_to(PROJECT).parts
        if not (set(rel) & SKIP_DIRS):
            yield p


# ------------------------------------------------------------------ 构建
def build(rep, skip=False) -> dict:
    g = PROJ["gradle"]
    dbg, rel = PROJECT / g["debugApk"], PROJECT / g["releaseApk"]
    info = {"debug": dbg, "release": rel}
    rc, head, _ = run(["git", "rev-parse", "HEAD"], cwd=PROJECT, timeout=20)
    rc2, dirty, _ = run(["git", "status", "--porcelain"], cwd=PROJECT, timeout=20)
    rep.meta["git"] = head.strip() if rc == 0 else "（不是 git 仓库）"
    rep.meta["gitDirty"] = bool(dirty.strip()) if rc2 == 0 else None
    if skip:
        rep.meta["build"] = "跳过构建（--no-build），本次结果不能作为正式验收"
        return info
    gw = gradlew()
    if not gw:
        raise RuntimeError("工程根目录没有 Gradle Wrapper")
    t0 = time.time()
    rc, out, err = run([gw, "clean", g["debugTask"], g["releaseTask"], "--console=plain", "--stacktrace"], cwd=PROJECT, timeout=3600)
    log = rep.path("build", "gradle-build.log")
    log.write_text(out + "\n" + err, encoding="utf-8")
    info["log"] = log
    info["ok"] = rc == 0 and dbg.exists() and rel.exists()
    info["seconds"] = round(time.time() - t0)
    rep.meta["build"] = f"{'成功' if info['ok'] else '失败'}，{info['seconds']} 秒"
    if info["ok"]:
        rep.meta["apkDebugSha256"] = sha256_file(dbg)
        rep.meta["apkReleaseSha256"] = sha256_file(rel)
    return info


def badging(apk) -> str:
    return run([str(AAPT2), "dump", "badging", str(apk)], timeout=60)[1]


def manifest(apk) -> ET.Element:
    rc, out, err = run([str(APKANALYZER), "manifest", "print", str(apk)], timeout=120)
    if rc != 0 or "<manifest" not in out:
        raise RuntimeError(f"apkanalyzer 读取清单失败：{err[:300]}")
    return ET.fromstring(out[out.index("<manifest"):])


def dex_blob(apk) -> bytes:
    with zipfile.ZipFile(apk) as z:
        return b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))


def dex_simple_classes(apk) -> set:
    return {m.decode()[1:-1].rsplit("/", 1)[-1].split("$")[0] for m in re.findall(rb"L[A-Za-z0-9_/$]+;", dex_blob(apk))}


def comp_name(pkg, n):
    return pkg + n if n.startswith(".") else (n if "." in n else f"{pkg}.{n}")


def filters(el):
    out = []
    for f in el.findall("intent-filter"):
        out.append(({a.get(A + "name") for a in f.findall("action")}, {c.get(A + "name") for c in f.findall("category")}))
    return out


# ------------------------------------------------------------------ 检查
def static_checks(rep, milestone, info) -> list[Result]:
    R = []

    def add(id_, title, ok, msg="", ev=None, warn=False, data=None):
        r = Result(id_, title, PASS if ok else (WARN if warn else FAIL), "static", msg, ev or [], data or {})
        rep.add(r)
        R.append(r)

    ok, msg = kit_verify()
    add("S01", "验收工具包未被修改", ok, msg)
    rep.meta["kitManifestRoot"] = msg if ok else "（无效）"

    # S15 工作区
    rc, out, _ = run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=PROJECT, timeout=30)
    if rc != 0:
        add("S15", "工程是 git 仓库且工作区干净", False, "不是 git 仓库")
    else:
        dirty = [l[3:] for l in out.splitlines() if l.strip() and not re.match(r"acceptance/(out|.*__pycache__)/", l[3:].strip('"'))]
        add("S15", "工程是 git 仓库且工作区干净", not dirty, "未提交：" + "，".join(dirty[:15]) if dirty else "",
            warn=bool(rep.meta.get("build", "").startswith("跳过")))

    # S16 冻结的文档
    fd = KIT / "spec" / "frozen_docs.json"
    msgs = []
    if not fd.exists():
        msgs.append("缺少 spec/frozen_docs.json")
    else:
        for n, h in json.loads(fd.read_text(encoding="utf-8")).items():
            f = PROJECT / n
            if not f.exists():
                msgs.append(f"{n} 不存在")
            elif sha256_file(f) != h:
                msgs.append(f"{n} 被修改")
    add("S16", "设计稿、PLAN.md、AGENTS.md 未被修改", not msgs, "；".join(msgs))

    built = info.get("ok", info["debug"].exists() and info["release"].exists())
    add("S02", "从源码构建 standardDebug 与 standardRelease", built, rep.meta.get("build", ""),
        [rep.rel(info["log"])] if info.get("log") else [])
    if not built:
        return R
    dbg, rel = info["debug"], info["release"]

    # S03 包名与 minSdk
    msgs = []
    for name, apk in (("debug", dbg), ("release", rel)):
        b = badging(apk)
        pk = re.search(r"package: name='([^']+)'", b)
        ms = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", b)
        if not pk or pk.group(1) != PROJ["package"]:
            msgs.append(f"{name} 包名 {pk.group(1) if pk else '?'}")
        if not ms or int(ms.group(1)) != VER["minSdk"]:
            msgs.append(f"{name} minSdk {ms.group(1) if ms else '?'}")
    add("S03", f"包名 {PROJ['package']}，minSdk {VER['minSdk']}", not msgs, "；".join(msgs))

    # S04 APK 内版本文件
    msgs, found = [], {}
    with zipfile.ZipFile(rel) as z:
        names = set(z.namelist())
        for f, pat in VER["apkVersionFiles"].items():
            n = "META-INF/" + f
            if n not in names:
                msgs.append(f"缺少 {n}（不得在 packaging 中排除 *.version）")
                continue
            v = z.read(n).decode().strip()
            found[f] = v
            if not re.search(pat, v):
                msgs.append(f"{f} = {v}，要求 /{pat}/")
    add("S04", "APK 内 androidx 版本（material3 1.5.0-alpha25，Compose 1.12.x）", not msgs, "；".join(msgs), data=found)

    # S05 依赖图
    gw = gradlew()
    rc, out, err = run([gw, "-q", ":app:dependencies", "--configuration", PROJ["gradle"]["depsConfiguration"]], cwd=PROJECT, timeout=900)
    dp = rep.path("build", "dependencies.txt")
    dp.write_text(out + err, encoding="utf-8")
    msgs = []
    if rc != 0:
        msgs.append("依赖报告任务失败")
    for pat in VER["forbiddenDeps"]:
        m = re.search(pat, out)
        if m:
            msgs.append(f"禁止的依赖：{out[max(0, m.start() - 20):m.end() + 30].strip()}")
    for ms, deps in VER["requiredDepsByMilestone"].items():
        if ms_le(ms, milestone):
            for d in deps:
                if d not in out:
                    msgs.append(f"{ms} 要求的依赖缺失：{d}")
    m3 = set(re.findall(r"androidx\.compose\.material3:material3:\S*?(?: -> (\S+)|:(\S+))", out))
    resolved = {a or b for a, b in m3} - {""}
    if resolved and resolved != {VER["material3"]}:
        msgs.append(f"material3 解析版本 {sorted(resolved)}")
    add("S05", "依赖图：锁定版本、必需依赖、禁止依赖", not msgs, "；".join(msgs), [rep.rel(dp)])

    # S06 模块与依赖方向
    settings = next((p for p in (PROJECT / "settings.gradle.kts", PROJECT / "settings.gradle") if p.exists()), None)
    st = settings.read_text(encoding="utf-8") if settings else ""
    mods = set(re.findall(r"""["'](:[\w:.-]+)["']""", st))
    msgs = []
    for ms, need in VER["modulesByMilestone"].items():
        if ms_le(ms, milestone):
            msgs += [f"{ms} 要求的模块缺失：{m}" for m in need if m not in mods]
    graph = {}
    for m in mods:
        d = PROJECT.joinpath(*m.strip(":").split(":"))
        bf = next((p for p in (d / "build.gradle.kts", d / "build.gradle") if p.exists()), None)
        if not bf:
            continue
        t = bf.read_text(encoding="utf-8")
        deps = set(re.findall(r"""project\(\s*(?:path\s*=\s*)?["'](:[\w:.-]+)["']""", t))
        for acc in re.findall(r"projects\.([\w.]+)", t):
            deps.add(":" + ":".join(re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", s).lower() for s in acc.split(".")))
        graph[m] = deps
        for rule in VER["moduleRules"]:
            if m.startswith(rule["from"]):
                for x in deps:
                    if x != m and any(x.startswith(b) for b in rule["mustNotDependOn"]):
                        msgs.append(f"{m} 不应依赖 {x}")
    add("S06", "模块齐全且依赖方向正确（设计稿 §16）", not msgs, "；".join(msgs), data={"modules": sorted(mods)})

    # S07 源码规则
    kt = [p for p in project_files("*.kt")]
    java = [p for p in project_files("*.java")]
    msgs = []
    for rule in VER["sourceRules"]:
        rx = re.compile(rule["pattern"])
        scope = rule["scope"]
        files = kt + java
        if scope == "kotlin-main":
            files = [p for p in kt if "/src/main/" in p.as_posix()]
        elif scope.startswith("outside:"):
            ex = scope.split(":", 1)[1]
            files = [p for p in kt if ex not in p.relative_to(PROJECT).as_posix()]
        for p in files:
            src = p.read_text(encoding="utf-8", errors="replace")
            if rule["id"] == "no-cjk-literals":
                src = strip_comments(src)
            for i, line in enumerate(src.splitlines(), 1):
                if rx.search(line):
                    msgs.append(f"[{rule['id']}] {p.relative_to(PROJECT).as_posix()}:{i}")
                    break
    add("S07", "源码规则（无 androidx.tv、无 TextureView、Expressive 实验 API 只在 designsystem、无中文字面量）", not msgs,
        "；".join(msgs[:30]) + (f" …共 {len(msgs)} 处" if len(msgs) > 30 else ""))

    # S08 必需符号
    allsrc = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in kt)
    gsrc = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in list(project_files("*.gradle.kts")) + list(project_files("*.gradle")) + list(project_files("*.toml")))
    msgs = []
    for s in VER["requiredSymbols"]:
        if ms_le(s["milestone"], milestone) and not re.search(s["pattern"], gsrc if s.get("files") == "gradle" else allsrc):
            msgs.append(f"缺少 {s['id']}（/{s['pattern']}/）")
    classes = dex_simple_classes(dbg)
    for ms, need in VER.get("requiredDexClassesByMilestone", {}).items():
        if ms_le(ms, milestone):
            msgs += [f"{ms} 要求 APK 中存在类 {c}" for c in need if c not in classes]
    add("S08", "必需的 API 与实现（MaterialExpressiveTheme、testTagsAsResourceId、MCU 等）", not msgs, "；".join(msgs))

    # S09 清单
    add_manifest_checks(add, milestone, dbg, rel)

    # S10 测试钩子只在 debug
    msgs = []
    md, mr = manifest(dbg), manifest(rel)
    auth = PROJ["stateAuthority"]
    prov_d = [p for p in md.iter("provider") if auth in (p.get(A + "authorities") or "")]
    if not prov_d:
        msgs.append(f"debug 缺少状态接口 Provider（{auth}）")
    elif prov_d[0].get(A + "readPermission") != "android.permission.DUMP":
        msgs.append("状态接口 Provider 未设置 readPermission=android.permission.DUMP")
    if any(auth in (p.get(A + "authorities") or "") for p in mr.iter("provider")):
        msgs.append("release 清单含状态接口 Provider")
    blob = dex_blob(rel)
    for needle in (PROJ["fixturePackagePrefix"], auth):
        if needle.encode() in blob:
            msgs.append(f"release dex 含 {needle}")
    cert = PROJ.get("fixtureCertA", "")
    if re.fullmatch(r"[0-9a-f]{64}", cert) and (cert.encode() in blob or cert.upper().encode() in blob):
        msgs.append("release dex 含夹具证书摘要")
    add("S10", "测试钩子只存在于 debug（契约 §3、§6、§7）", not msgs, "；".join(msgs))

    # S11 文案白名单
    v = strings_violations()
    add("S11", "strings.xml 文案全部在白名单内、无提示性文字", not v, "；".join(v[:25]) + (f" …共 {len(v)} 条" if len(v) > 25 else ""))

    if ms_le("M4", milestone):
        add_media_license_check(add)
    if ms_le("M5", milestone):
        with zipfile.ZipFile(rel) as z:
            ok = "assets/dexopt/baseline.prof" in z.namelist()
        add("S12", "release APK 含 Baseline Profile", ok)
    if milestone == "M0":
        add_m0_report_check(add)
    if milestone != "M0":
        add_required_tests_check(add, rep, milestone, run_tests=not rep.meta.get("build", "").startswith("跳过"))
    return R


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    out = []
    for line in src.splitlines():
        q, cut = 0, None
        for i, ch in enumerate(line):
            if ch == '"' and (i == 0 or line[i - 1] != "\\"):
                q ^= 1
            elif ch == "/" and not q and line[i:i + 2] == "//":
                cut = i
                break
        out.append(line[:cut] if cut is not None else line)
    return "\n".join(out)


def strings_violations() -> list[str]:
    cp = spec("ui_copy")
    exact = set(cp["exact"])
    pats = [re.compile(p) for p in cp["patterns"]]
    banned = [re.compile(p) for p in cp["banned"]]
    bad = []
    for f in project_files("strings.xml"):
        if "/res/values" not in f.as_posix():
            continue
        default = "/res/values/" in f.as_posix()
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            bad.append(f"{f.name} 解析失败 {e}")
            continue
        items = []
        for el in root:
            name = el.get("name", "")
            if el.tag == "string":
                items.append((name, "".join(el.itertext())))
            elif el.tag in ("plurals", "string-array"):
                items += [(name, "".join(i.itertext())) for i in el.findall("item")]
        for name, val in items:
            v = val.strip().strip('"').replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
            sample = re.sub(r"%(\d+\$)?s", "样例", v)
            sample = re.sub(r"%(\d+\$)?d", "3", sample).replace("%%", "%")
            for b in banned:
                if b.search(sample):
                    bad.append(f"{name}「{v}」禁用词 /{b.pattern}/")
            if not default or name == "app_name":
                continue
            if sample not in exact and not any(p.search(sample) for p in pats):
                bad.append(f"{name}「{v}」不在白名单")
    return bad


def add_manifest_checks(add, milestone, dbg, rel):
    m = manifest(rel)
    pkg = m.get("package")
    msgs = []
    acts = list(m.iter("activity")) + list(m.iter("activity-alias"))
    home = [a for a in acts if any("android.intent.action.MAIN" in ac and {"android.intent.category.HOME", "android.intent.category.DEFAULT"} <= ca for ac, ca in filters(a))]
    lean = [a for a in acts if any("android.intent.action.MAIN" in ac and "android.intent.category.LEANBACK_LAUNCHER" in ca for ac, ca in filters(a))]
    if not home:
        msgs.append("没有 HOME + DEFAULT 的 Activity")
    if not lean:
        msgs.append("没有 LEANBACK_LAUNCHER 入口")
    feats = {f.get(A + "name"): f.get(A + "required") for f in m.iter("uses-feature")}
    if feats.get("android.hardware.touchscreen") not in ("false",):
        msgs.append("未声明 android.hardware.touchscreen required=false")
    perms = {p.get(A + "name") for p in m.iter("uses-permission")}
    for bad in ("android.permission.ACCESS_ALL_EPG_DATA", "android.permission.BIND_APPWIDGET"):
        if bad in perms:
            msgs.append(f"standard 变体声明了 {bad}")
    if "android.permission.READ_TV_LISTINGS" not in perms:
        msgs.append("缺少 READ_TV_LISTINGS")
    # HOME 兼容（§14，M1 起）
    full = {"android.intent.category.HOME", "android.intent.category.DEFAULT", "android.intent.category.LAUNCHER", "android.intent.category.COMMON_HOME"}
    main = [a for a in acts if any("android.intent.action.MAIN" in ac and full <= ca for ac, ca in filters(a))]
    if not main:
        msgs.append("HOME Activity 的 intent-filter 未同时声明 HOME、DEFAULT、LAUNCHER、COMMON_HOME（§14）")
    else:
        a = main[0]
        if not any({"android.intent.category.HOME.CC", "android.intent.category.DEFAULT"} <= ca for _, ca in filters(a)):
            msgs.append("HOME Activity 缺少 HOME.CC + DEFAULT 的 intent-filter")
        metas = {x.get(A + "name"): x.get(A + "value") for x in list(a.findall("meta-data")) + list(m.find("application").findall("meta-data"))}
        if metas.get("com.tcl.app.type") != "com.tcl.ui":
            msgs.append("缺少 meta-data com.tcl.app.type=com.tcl.ui")
        want = {"launchMode": ("singleTask", "2"), "stateNotNeeded": ("true",), "clearTaskOnLaunch": ("true",),
                "excludeFromRecents": ("true",), "resumeWhilePausing": ("true",), "screenOrientation": ("landscape", "0")}
        for k, ok in want.items():
            if a.get(A + k) not in ok:
                msgs.append(f"HOME Activity 的 {k}={a.get(A + k)}，应为 {ok[0]}")
    if ms_le("M3", milestone):
        for loc in ("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION"):
            if loc in perms:
                msgs.append(f"不得申请定位权限：{loc}（§12）")
    if ms_le("M4", milestone):
        cr = [r for r in m.iter("receiver") if (r.get(A + "name") or "").endswith(".config.ConfigReceiver")]
        if not cr or cr[0].get(A + "exported") != "true" or cr[0].get(A + "permission") != "android.permission.WRITE_SECURE_SETTINGS":
            msgs.append("缺少导出的 .config.ConfigReceiver（permission=WRITE_SECURE_SETTINGS，§14 §16）")
        for pm in ("android.permission.SYSTEM_ALERT_WINDOW", "android.permission.PACKAGE_USAGE_STATS"):
            if pm not in perms:
                msgs.append(f"未声明 {pm}（§14 儿童模式）")
    if ms_le("M5", milestone):
        for pm in ("android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"):
            if pm not in perms:
                msgs.append(f"未声明 {pm}（§16 应用内更新）")
        if not [x for x in m.iter("provider") if "FileProvider" in (x.get(A + "name") or "")]:
            msgs.append("缺少 FileProvider（§16 更新回退）")
    if ms_le("M3", milestone):
        if feats.get("android.software.app_widgets") != "false":
            msgs.append("未声明 android.software.app_widgets required=false")
        rec = [r for r in m.iter("receiver") if any("android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED" in ac for ac, _ in filters(r))]
        if not rec or rec[0].get(A + "exported") != "true":
            msgs.append("缺少导出的 CHANNEL_BROWSABLE_REQUESTED 接收器")
        if not [a for a in acts if any("android.media.tv.action.REQUEST_CHANNEL_BROWSABLE" in ac for ac, _ in filters(a))]:
            msgs.append("缺少处理 REQUEST_CHANNEL_BROWSABLE 的 Activity")
        if not [a for a in acts if any("android.content.pm.action.CONFIRM_PIN_APPWIDGET" in ac for ac, _ in filters(a))]:
            msgs.append("缺少处理 CONFIRM_PIN_APPWIDGET 的 Activity")
        q = m.find("queries")
        qa = {a.get(A + "name") for a in q.iter("action")} if q is not None else set()
        if "com.github.mytv.action.LAUNCHER_BRIDGE" not in qa:
            msgs.append("<queries> 未声明 com.github.mytv.action.LAUNCHER_BRIDGE")
    if ms_le("M4", milestone):
        dreams = [s for s in m.iter("service") if any("android.service.dreams.DreamService" in ac for ac, _ in filters(s))]
        if not dreams or dreams[0].get(A + "permission") != "android.permission.BIND_DREAM_SERVICE":
            msgs.append("缺少屏保 DreamService（需 BIND_DREAM_SERVICE 权限）")
    add("S09", "清单：HOME、LEANBACK、权限、频道与小组件入口", not msgs, "；".join(msgs))


def add_media_license_check(add):
    files = [p for p in project_files("playlist.json") if "/assets/ambient/" in p.as_posix()]
    msgs = []
    if not files:
        msgs.append("缺少 data/media 的 assets/ambient/playlist.json")
    for f in files:
        try:
            items = json.loads(f.read_text(encoding="utf-8"))
            items = items.get("clips", items) if isinstance(items, dict) else items
            for i, c in enumerate(items):
                for k in ("url", "title", "license", "source"):
                    if not str(c.get(k, "")).strip():
                        msgs.append(f"{f.name}[{i}] 缺少 {k}")
        except Exception as e:
            msgs.append(f"{f.name} 无法解析：{e}")
    add("S13", "航拍片单每条都有许可信息（§13）", not msgs, "；".join(msgs[:20]))


def add_m0_report_check(add):
    p = PROJECT / "docs" / "m0" / "m0-results.json"
    msgs = []
    if not p.exists():
        add("S14", "M0 报告 docs/m0/m0-results.json 结构完整", False, "文件不存在")
        return
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        add("S14", "M0 报告 docs/m0/m0-results.json 结构完整", False, f"无法解析：{e}")
        return
    for k in map(str, range(1, 12)):
        it = (d.get("items") or {}).get(k)
        if not it:
            msgs.append(f"缺少第 {k} 项")
            continue
        for f in ("title", "conclusion", "data", "evidence"):
            if not it.get(f):
                msgs.append(f"第 {k} 项缺少 {f}")
        for e in it.get("evidence", []):
            if not (PROJECT / e).exists():
                msgs.append(f"第 {k} 项证据不存在：{e}")
    add("S14", "M0 报告 docs/m0/m0-results.json 结构完整", not msgs, "；".join(msgs))


def add_required_tests_check(add, rep, milestone, run_tests=True):
    req = [t for t in spec("required_tests")["tests"] if ms_le(t["milestone"], milestone)]
    gw = gradlew()
    log = ""
    if run_tests and gw:
        rc, out, err = run([gw, "test", "--continue", "--console=plain"], cwd=PROJECT, timeout=5400)
        p = rep.path("build", "gradle-test.log")
        p.write_text(out + chr(10) + err, encoding="utf-8")
        log = rep.rel(p)
    cases = {}                      # (module 路径, 类简单名) -> [(用例名, 状态)]
    for x in PROJECT.rglob("TEST-*.xml"):
        rel = x.relative_to(PROJECT).as_posix()
        if "/build/test-results/" not in rel or rel.startswith(("acceptance/", "spikes/")):
            continue
        mod = ":" + rel.split("/build/test-results/")[0].replace("/", ":")
        try:
            root = ET.parse(x).getroot()
        except ET.ParseError:
            continue
        for tc in root.iter("testcase"):
            cls = (tc.get("classname") or "").rsplit(".", 1)[-1]
            st = "fail" if tc.find("failure") is not None or tc.find("error") is not None else ("skip" if tc.find("skipped") is not None else "pass")
            cases.setdefault((mod, cls), []).append((tc.get("name"), st))
    msgs, data = [], {}
    for t in req:
        got = cases.get((t["module"], t["class"]), [])
        data[t["id"]] = {"class": t["class"], "module": t["module"], "tests": len(got)}
        if not got:
            msgs.append(f"{t['id']} 缺少 {t['module']} 中的 {t['class']}")
            continue
        bad = [n for n, st in got if st != "pass"]
        if bad:
            msgs.append(f"{t['id']} {t['class']} 未通过或被跳过：{bad[:3]}")
        if len(got) < t["minTests"]:
            msgs.append(f"{t['id']} {t['class']} 只有 {len(got)} 个测试，至少 {t['minTests']} 个")
    all_bad = [f"{m}/{c}.{n}" for (m, c), v in cases.items() for n, st in v if st == "fail"]
    if all_bad:
        msgs.append("其他失败的单元测试：" + "，".join(all_bad[:10]))
    add("S17", "必需的单元测试存在且全部通过（spec/required_tests.json）", not msgs, "；".join(msgs[:20]),
        [log] if log else [], data=data)
