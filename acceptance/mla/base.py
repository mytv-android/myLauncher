"""基础设施：路径、外部命令、规格文件、结果与报告。"""
from __future__ import annotations

import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

KIT = Path(__file__).resolve().parents[1]          # acceptance/
PROJECT = KIT.parent                                 # 工程根目录

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# ---------------------------------------------------------------- 工具路径
def _sdk() -> Path:
    for k in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(k)
        if v and Path(v).is_dir():
            return Path(v)
    return Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"


SDK = _sdk()
EXE = ".exe" if os.name == "nt" else ""
BAT = ".bat" if os.name == "nt" else ""
ADB = SDK / "platform-tools" / f"adb{EXE}"
EMULATOR = SDK / "emulator" / f"emulator{EXE}"


def _build_tools() -> Path:
    bt = SDK / "build-tools"
    cands = sorted([p for p in bt.iterdir() if (p / f"aapt2{EXE}").exists()], key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    return cands[-1]


BUILD_TOOLS = _build_tools()
AAPT2 = BUILD_TOOLS / f"aapt2{EXE}"
APKSIGNER = BUILD_TOOLS / f"apksigner{BAT}"
APKANALYZER = SDK / "cmdline-tools" / "latest" / "bin" / f"apkanalyzer{BAT}"


def java_home() -> str:
    for c in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Android\Android Studio\jbr", r"C:\Program Files\Android\Android Studio\jre"):
        if c and (Path(c) / "bin" / f"java{EXE}").exists():
            return c
    raise RuntimeError("找不到 JDK：请设置 JAVA_HOME 或安装 Android Studio")


def tool_env() -> dict:
    env = dict(os.environ)
    jh = java_home()
    env["JAVA_HOME"] = jh
    env["PATH"] = str(Path(jh) / "bin") + os.pathsep + env.get("PATH", "")
    env.setdefault("ANDROID_HOME", str(SDK))
    return env


# ---------------------------------------------------------------- 外部命令
def run(cmd, timeout=120, cwd=None, env=None, input=None, binary=False):
    """运行命令，返回 (returncode, stdout, stderr)。不抛异常。"""
    try:
        p = subprocess.run(cmd, cwd=cwd, env=env or tool_env(), input=input, capture_output=True, timeout=timeout,
                           shell=isinstance(cmd, str))
    except subprocess.TimeoutExpired as e:
        return 124, (e.stdout or b"") if binary else (e.stdout or b"").decode("utf-8", "replace"), "TIMEOUT"
    except FileNotFoundError as e:
        return 127, b"" if binary else "", str(e)
    out = p.stdout if binary else p.stdout.decode("utf-8", "replace")
    return p.returncode, out, p.stderr.decode("utf-8", "replace")


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def spec(name: str) -> dict:
    return json.loads((KIT / "spec" / f"{name}.json").read_text(encoding="utf-8"))


MILESTONES = ["M0", "M1", "M2", "M3", "M4", "M5"]


def ms_le(a: str, b: str) -> bool:
    return MILESTONES.index(a) <= MILESTONES.index(b)


# ---------------------------------------------------------------- 结果
PASS, FAIL, WARN, SKIP, MANUAL = "PASS", "FAIL", "WARN", "SKIP", "MANUAL"


@dataclass
class Result:
    id: str
    title: str
    status: str
    device: str = "static"
    message: str = ""
    evidence: list = field(default_factory=list)   # 相对 out 目录的路径
    data: dict = field(default_factory=dict)
    seconds: float = 0.0


class CheckFailed(Exception):
    pass


class Skip(Exception):
    pass


class Report:
    def __init__(self, milestone: str, label: str):
        ts = time.strftime("%Y%m%d-%H%M%S")
        self.milestone = milestone
        self.out = KIT / "out" / f"{ts}-{milestone}-{label}"
        self.out.mkdir(parents=True, exist_ok=True)
        self.results: list[Result] = []
        self.meta: dict = {"milestone": milestone, "started": ts, "project": str(PROJECT)}
        self.texts: dict[str, list] = {}         # 设备 -> 收集到的界面文字

    def path(self, *parts) -> Path:
        p = self.out.joinpath(*parts)
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    def rel(self, p: Path) -> str:
        return p.relative_to(self.out).as_posix()

    def add(self, r: Result):
        self.results.append(r)
        mark = {PASS: "  ok ", FAIL: " FAIL", WARN: " warn", SKIP: " skip", MANUAL: "  man"}[r.status]
        print(f"[{mark}] {r.device:7s} {r.id:6s} {r.title}  {('- ' + r.message) if r.message else ''}", flush=True)

    def counts(self) -> dict:
        c = {}
        for r in self.results:
            c[r.status] = c.get(r.status, 0) + 1
        return c

    def verdict(self) -> str:
        return "通过" if not any(r.status == FAIL for r in self.results) and self.results else "不通过"

    def write(self):
        self.meta["finished"] = time.strftime("%Y%m%d-%H%M%S")
        self.meta["counts"] = self.counts()
        self.meta["verdict"] = self.verdict()
        data = {"meta": self.meta, "results": [r.__dict__ for r in self.results]}
        (self.out / "results.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        (self.out / "report.html").write_text(self._html(), encoding="utf-8")
        return self.out / "report.html"

    def _html(self) -> str:
        e = html.escape
        color = {PASS: "#1e7a3c", FAIL: "#b3261e", WARN: "#8a5a00", SKIP: "#666", MANUAL: "#3355aa"}
        rows = []
        for r in self.results:
            ev = " ".join(
                (f'<a href="{e(x)}"><img src="{e(x)}" loading="lazy"></a>' if x.endswith(".png") else f'<a href="{e(x)}">{e(Path(x).name)}</a>')
                for x in r.evidence)
            rows.append(f'<tr><td><b style="color:{color[r.status]}">{r.status}</b></td><td>{e(r.device)}</td><td>{e(r.id)}</td>'
                        f'<td>{e(r.title)}<div class="m">{e(r.message)}</div></td><td class="ev">{ev}</td></tr>')
        m = self.meta
        head = "".join(f"<tr><th>{e(str(k))}</th><td>{e(json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else str(v))}</td></tr>" for k, v in m.items())
        return f"""<!doctype html><meta charset="utf-8"><title>验收报告 {e(m['milestone'])}</title>
<style>body{{font:14px/1.5 system-ui,"Microsoft YaHei";margin:24px;color:#1b1b1f;background:#fbfbfe}}
table{{border-collapse:collapse;width:100%;margin:12px 0}}td,th{{border-bottom:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top}}
.m{{color:#555;font-size:12.5px;white-space:pre-wrap}}.ev img{{height:120px;margin:2px;border:1px solid #ccc}}
h1{{font-size:22px}}.v{{font-size:20px;font-weight:700;color:{'#1e7a3c' if self.verdict()=='通过' else '#b3261e'}}}</style>
<h1>验收报告 · {e(m['milestone'])}</h1><div class="v">结论：{self.verdict()} {e(json.dumps(self.counts(), ensure_ascii=False))}</div>
<table>{head}</table>
<table><tr><th>结果</th><th>设备</th><th>编号</th><th>检查</th><th>证据</th></tr>{''.join(rows)}</table>
<p>MANUAL 项需要人工对照 <code>tv-launcher-design-v3.html</code> 中的原型判断。WARN 不阻塞验收，但需在里程碑说明中解释。</p>"""


# ---------------------------------------------------------------- 工具包完整性
EXCLUDE_DIRS = {"out", "__pycache__", ".pytest_cache", "build"}


def kit_files() -> list[Path]:
    files = []
    for p in sorted(KIT.rglob("*")):
        if p.is_file() and not (set(p.relative_to(KIT).parts) & EXCLUDE_DIRS) and p.name != "MANIFEST.sha256":
            files.append(p)
    return files


def kit_manifest() -> str:
    return "".join(f"{sha256_file(p)}  {p.relative_to(KIT).as_posix()}\n" for p in kit_files())


def kit_verify() -> tuple[bool, str]:
    mf = KIT / "MANIFEST.sha256"
    if not mf.exists():
        return False, "缺少 MANIFEST.sha256（工具包尚未冻结）"
    want = mf.read_text(encoding="utf-8")
    got = kit_manifest()
    if want == got:
        return True, hashlib.sha256(want.encode()).hexdigest()
    a = dict(reversed(l.split("  ", 1)) for l in want.splitlines() if l)
    b = dict(reversed(l.split("  ", 1)) for l in got.splitlines() if l)
    diff = sorted(set(a.items()) ^ set(b.items()))
    names = sorted({k for k, _ in diff})
    return False, "被修改的文件：" + ", ".join(names[:20])


FROZEN_DOCS = ["AGENTS.md", "CLAUDE.md", "PLAN.md", "tv-launcher-design-v3.html", "tv-launcher-design.html"]


def kit_freeze() -> tuple[str, Path, str]:
    docs = {n: sha256_file(PROJECT / n) for n in FROZEN_DOCS if (PROJECT / n).exists()}
    (KIT / "spec" / "frozen_docs.json").write_text(json.dumps(docs, indent=1), encoding="utf-8")
    txt = kit_manifest()
    (KIT / "MANIFEST.sha256").write_text(txt, encoding="utf-8")
    root = hashlib.sha256(txt.encode()).hexdigest()
    zip_base = PROJECT.parent / "myLauncher-acceptance"
    z = shutil.make_archive(str(zip_base), "zip", root_dir=PROJECT, base_dir="acceptance")
    # 去掉 out/ 与缓存
    import zipfile
    clean = Path(str(zip_base) + ".zip")
    tmp = clean.with_suffix(".tmp")
    with zipfile.ZipFile(z) as zi, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zo:
        for it in zi.infolist():
            parts = Path(it.filename).parts
            if set(parts) & EXCLUDE_DIRS:
                continue
            zo.writestr(it, zi.read(it.filename))
    tmp.replace(clean)
    return root, clean, sha256_file(clean)
