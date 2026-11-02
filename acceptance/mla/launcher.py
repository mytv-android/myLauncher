"""被测桌面与夹具应用的操作封装。"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path

from .base import KIT, CheckFailed, spec
from .device import Device, UiTree

PROJ = spec("project")
PKG = PROJ["package"]
AUTH = PROJ["stateAuthority"]
FIX = PROJ["fixturePackagePrefix"]


class Launcher:
    def __init__(self, dev: Device, rep, home_component: str):
        self.dev, self.rep, self.comp = dev, rep, home_component
        self.uri = f"content://{AUTH}/state"
        self.shots = 0
        self.dumps: list[tuple[str, UiTree]] = []

    # ---------------------------------------------------- 状态接口
    def state(self) -> dict:
        rows = self.dev.content_query(self.uri, col="json")
        if not rows:
            raise CheckFailed("状态接口没有返回（契约 §3）")
        try:
            st = json.loads(rows[0])
        except json.JSONDecodeError as e:
            raise CheckFailed(f"状态接口返回的不是单行 JSON：{e}")
        if st.get("contract") != 1:
            raise CheckFailed(f"状态接口 contract={st.get('contract')}，应为 1")
        return st

    def call(self, method, arg=None) -> str:
        return self.dev.content_call(f"content://{AUTH}", method, arg)

    def pref(self, k, v):
        self.call("set_pref", f"{k}={v}")

    def focus(self, st=None):
        st = st or self.state()
        return (st.get("focus") or {}).get("tag") or ""

    def settle(self, timeout=2.5) -> dict:
        t0, last, same = time.time(), None, 0
        st = self.state()
        while time.time() - t0 < timeout:
            tag = self.focus(st)
            busy = (st.get("theme") or {}).get("transitioning")
            if tag == last and not busy:
                same += 1
                if same >= 2:
                    return st
            else:
                same = 0
            last = tag
            time.sleep(0.15)
            st = self.state()
        return st

    def press(self, k, long=False, settle=True) -> dict | None:
        self.dev.key(k, long=long, wait=0.05 if settle else 0.25)
        if k == "OK":
            time.sleep(0.6)
        return self.settle() if settle else None

    def seek(self, target, dirs=("DOWN",), max_steps=16, match=None) -> bool:
        """沿给定方向按键直到焦点标签等于 target（或 match(tag) 为真）。"""
        ok = (lambda t: match(t)) if match else (lambda t: t == target)
        for d in dirs:
            prev = None
            for _ in range(max_steps):
                cur = self.focus()
                if ok(cur):
                    return True
                if cur == prev:
                    break
                prev = cur
                self.press(d)
            if ok(self.focus()):
                return True
        return False

    # ---------------------------------------------------- 生命周期
    def start(self):
        self.dev.sh(f"am start -W -n {self.comp}", timeout=60)
        time.sleep(1.5)
        return self.settle(4)

    def stop(self):
        self.dev.sh(f"am force-stop {PKG}")

    def clear(self):
        self.dev.sh(f"pm clear {PKG}")

    def alive(self) -> bool:
        return self.dev.resumed_pkg() == PKG

    # ---------------------------------------------------- 证据
    def snap(self, name, dump=True):
        """截图 + 无障碍树 + 状态，存入报告目录，返回 (img, tree, state, [证据路径])。"""
        self.shots += 1
        base = f"{self.dev.name}/{self.shots:03d}-{name}"
        img = self.dev.screencap()
        p = self.rep.path(base + ".png")
        img.save(p)
        ev = [self.rep.rel(p)]
        tree = None
        if dump:
            tree = self.dev.dump()
            px = self.rep.path(base + ".xml")
            px.write_text(tree.xml, encoding="utf-8")
            ev.append(self.rep.rel(px))
            self.dumps.append((base, tree))
        st = self.state()
        ps = self.rep.path(base + ".state.json")
        ps.write_text(json.dumps(st, ensure_ascii=False, indent=1), encoding="utf-8")
        ev.append(self.rep.rel(ps))
        return img, tree, st, ev

    def consistent(self, tree: UiTree, st: dict) -> str:
        """状态接口与无障碍树对照，返回空串表示一致，否则返回差异说明。"""
        def contract_tag(n):
            return n.rid and ":id/" not in n.rid
        tag = self.focus(st)
        foc = [n for n in tree.focused(PKG) if contract_tag(n)]
        if not foc:
            # 焦点在 AppWidgetHostView 等原生 View 内部：取最深焦点节点最近的带契约标签的祖先
            fs = sorted(tree.focused(PKG), key=lambda n: len(list(n.ancestors())))
            hit = next((a for a in fs[-1].ancestors() if contract_tag(a)), None) if fs else None
            foc = [hit] if hit else []
        if not foc:
            return f"无障碍树里没有带标签的焦点节点（状态接口焦点 {tag}）"
        if foc[-1].rid != tag:
            return f"焦点不一致：无障碍树 {foc[-1].rid}，状态接口 {tag}"
        rows_ui = sorted([n for n in tree.prefix("row:") if n.pkg == PKG], key=lambda n: n.bounds[1])
        ids_ui = [n.rid[4:] for n in rows_ui if n.bounds[3] > n.bounds[1]]
        ids_st = [r["id"] for r in st.get("rows", [])]
        it = iter(ids_st)
        if not all(any(x == y for y in it) for x in ids_ui):
            return f"行顺序不一致：屏幕 {ids_ui}，状态接口 {ids_st}"
        return ""


# ======================================================================== 夹具
class Fixtures:
    def __init__(self, dev: Device):
        self.dev = dev
        self.dir = KIT / "fixtures" / "apk"
        idx = self.dir / "index.json"
        raw = json.loads(idx.read_text(encoding="utf-8")) if idx.exists() else {}
        self.certs = {k: raw.get(k) for k in ("certA", "certB")}
        self.index = {e["id"]: {"apk": e["file"], **e} for e in raw.get("apks", [])}
        self.installed: set[str] = set()

    @staticmethod
    def pkg(fid):
        return f"{FIX}.{fid.replace('_impostor', '')}"

    def apk(self, fid) -> Path:
        e = self.index.get(fid) or {}
        return self.dir / (e.get("apk") or f"{fid}.apk")

    def install(self, *fids, grant=False):
        for f in fids:
            ok, msg = self.dev.install(self.apk(f), grant=grant)
            if not ok:
                raise CheckFailed(f"夹具 {f} 安装失败：{msg}")
            self.installed.add(f)

    def uninstall(self, *fids):
        for f in fids:
            self.dev.uninstall(self.pkg(f))
            self.installed.discard(f)

    def cmd(self, fid, cmd, arg=None):
        a = f" --es arg '{arg}'" if arg is not None else ""
        return self.dev.sh(f"am broadcast -n {self.pkg(fid)}/{FIX}.CmdReceiver --es cmd {cmd}{a}")

    def log(self, fid) -> list[dict]:
        out = []
        for l in self.dev.content_query(f"content://{self.pkg(fid)}.log/events", col="line"):
            try:
                out.append(json.loads(l))
            except json.JSONDecodeError:
                pass
        return out

    def wait(self, fid, pred, timeout=15.0):
        t0 = time.time()
        while time.time() - t0 < timeout:
            for e in self.log(fid):
                if pred(e):
                    return e
            time.sleep(0.7)
        return None

    def cleanup(self):
        for p in self.dev.packages(FIX):
            self.dev.uninstall(p)


def content_strings() -> set[str]:
    c = json.loads((KIT / "fixtures" / "content.json").read_text(encoding="utf-8"))
    s = {"验收探针"}
    for a in c["apps"].values():
        s.add(a["label"])
        for ch in a.get("channels", []):
            s.add(ch["name"])
            for p in ch["programs"]:
                s.update(x for x in (p.get("title"), p.get("desc"), p.get("genre")) if x)
        for w in a.get("watchNext", []):
            s.update(x for x in (w.get("title"), w.get("desc")) if x)
        for w in a.get("widgets", []):
            s.add(w["label"])
            s.add(f"{a['label']} · {w['label']}")
    return s


def copy_violations(dumps, extra_ok=()) -> list[str]:
    """界面文字：禁用词对全部文字生效（夹具内容除外）；白名单对非豁免文字生效。"""
    cp = spec("ui_copy")
    exact = set(cp["exact"]) | set(extra_ok)
    pats = [re.compile(p) for p in cp["patterns"]]
    banned = [re.compile(p) for p in cp["banned"]]
    content = content_strings()
    bad = {}
    for name, tree in dumps:
        for n in tree.nodes:
            if n.pkg != PKG:
                continue
            for t in (n.text, n.desc):
                t = (t or "").strip()
                if not t or t in content:
                    continue
                for b in banned:
                    if b.search(t):
                        bad[t] = f"禁用词 /{b.pattern}/ @ {name}"
                chain = [n] + list(n.ancestors())
                if any(x.rid.startswith(tuple(cp["exemptTagPrefixes"])) for x in chain):
                    continue
                if cp.get("exemptWidgetHostSubtree") and any(re.fullmatch(r"widget:\d+", x.rid or "") for x in chain):
                    continue
                if t in exact or any(p.search(t) for p in pats):
                    continue
                bad.setdefault(t, f"不在 ui_copy 白名单 @ {name}")
    return [f"「{k}」{v}" for k, v in bad.items()]
