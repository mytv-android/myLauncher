"""被测桌面与夹具应用的操作封装。"""
from __future__ import annotations

import json
import re
import time
import urllib.parse
import urllib.request
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
        self.http = None
        self._last_ok = 0.0
        self._alive_at = 0.0
        self._alive_ok = False

    # ---------------------------------------------------- 状态接口
    def _http(self, path: str):
        if not self.http:
            return None
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.http}{path}", timeout=2) as r:
                return r.read().decode("utf-8", "replace")
        except Exception:
            return None

    def state(self) -> dict:
        raw = None
        if self.http:
            t0 = time.time()
            while time.time() - t0 < 1.2:
                raw = self._http("/state")
                if raw:
                    break
                time.sleep(0.04)
            if raw is None and not self.dev.pid(PKG):
                raise CheckFailed("状态接口没有返回（契约 §3）")
        if raw is None:
            rows = self.dev.content_query(self.uri, col="json")
            if not rows:
                raise CheckFailed("状态接口没有返回（契约 §3）")
            raw = rows[0]
        try:
            st = json.loads(raw)
        except json.JSONDecodeError as e:
            raise CheckFailed(f"状态接口返回的不是单行 JSON：{e}")
        if st.get("contract") != 1:
            raise CheckFailed(f"状态接口 contract={st.get('contract')}，应为 1")
        return st

    def call(self, method, arg=None) -> str:
        if self.http:
            q = {"method": method}
            if arg is not None:
                q["arg"] = arg
            raw = self._http("/call?" + urllib.parse.urlencode(q))
            if raw is not None:
                return raw
        return self.dev.content_call(f"content://{AUTH}", method, arg)

    def pref(self, k, v):
        self.call("set_pref", f"{k}={v}")

    def focus(self, st=None):
        st = st or self.state()
        return (st.get("focus") or {}).get("tag") or ""

    def settle(self, timeout=1.5) -> dict:
        t0, last, same = time.time(), None, 0
        st = self.state()
        while time.time() - t0 < timeout:
            tag = self.focus(st)
            busy = (st.get("theme") or {}).get("transitioning")
            if tag == last and not busy:
                same += 1
                if same >= 1:
                    return st
            else:
                same = 0
            last = tag
            time.sleep(0.04)
            st = self.state()
        return st

    def press(self, k, long=False, settle=True) -> dict | None:
        if k == "OK":
            gap = 0.6 - (time.time() - self._last_ok)      # 契约 §5：两次 OK 之间至少 600ms
            if gap > 0:
                time.sleep(gap)
        self.dev.key(k, long=long, wait=0.03 if settle else 0.08)
        if k == "OK":
            self._last_ok = time.time()
        return self.settle() if settle else None

    def alive_fast(self, force=False) -> bool:
        now = time.time()
        if force or now - self._alive_at > 0.5:
            self._alive_ok = self.dev.resumed_pkg() == PKG
            self._alive_at = now
        return self._alive_ok

    def _wait_focus_change(self, before, timeout=1.2) -> dict:
        t0, st = time.time(), None
        while time.time() - t0 < timeout:
            time.sleep(0.02)
            st = self.state()
            if self.focus(st) != before:
                return st
        return st or self.state()

    def seek(self, target, dirs=("DOWN",), max_steps=16, match=None) -> bool:
        """沿给定方向按键直到焦点标签等于 target（或 match(tag) 为真）。"""
        ok = (lambda t: match(t)) if match else (lambda t: t == target)
        st = self.state()
        for d in dirs:
            prev, stuck = None, 0
            for _ in range(max_steps):
                cur = self.focus(st)
                if ok(cur):
                    return True
                if cur == prev:
                    # 负载下按键可能晚于等待窗口才生效，再按一次；连续三次不动才认为到边界
                    stuck += 1
                    if stuck > 2:
                        break
                else:
                    stuck = 0
                    prev = cur
                self.dev.key(d, wait=0.02)
                st = self._wait_focus_change(cur)
            if ok(self.focus(st)):
                return True
        time.sleep(0.25)
        return ok(self.focus(self.state()))

    def seek_state(self, pred, dirs=("DOWN",), max_steps=25) -> bool:
        """按状态接口的判定条件找焦点（例如按 rowId 找行）。"""
        st = self.state()
        for d in dirs:
            prev, stuck = None, 0
            for _ in range(max_steps):
                if pred(st):
                    return True
                tag = self.focus(st)
                if tag == prev:
                    stuck += 1
                    if stuck > 2:
                        break
                else:
                    stuck = 0
                    prev = tag
                self.dev.key(d, wait=0.02)
                st = self._wait_focus_change(tag)
            if pred(st):
                return True
        time.sleep(0.25)
        return pred(self.state())

    # ---------------------------------------------------- 生命周期
    def start(self):
        # 显式组件启动会把桌面放进 standard 任务，随后 HOME 再建一个 home 任务实例，
        # 进程里出现两个 Activity，无障碍树可能取到已停止的那个。统一用 HOME 意图冷启动。
        self.dev.sh(f"am force-stop {PKG}")
        self.dev.sh("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME", timeout=60)
        time.sleep(0.3)
        return self.settle(3)

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
        self.last_base = base
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
        # 本轮真正 publish 过的夹具（包名），teardown 只给它们发 unpublish，省掉一轮广播
        self.published: set[str] = getattr(dev, "fixtures_published", None) or set()
        dev.fixtures_published = self.published

    @staticmethod
    def pkg(fid):
        return f"{FIX}.{fid.replace('_impostor', '')}"

    def apk(self, fid) -> Path:
        e = self.index.get(fid) or {}
        return self.dir / (e.get("apk") or f"{fid}.apk")

    def install(self, *fids, grant=False, force=False):
        todo = [f for f in fids if force or f not in self.installed]
        if not todo:
            return
        # 单个安装约 7 秒；一次装多个时并发上传，失败的再串行重试一次
        from concurrent.futures import ThreadPoolExecutor

        failed: list[tuple[str, str]] = []

        def one(fid):
            ok, msg = self.dev.install(self.apk(fid), grant=grant)
            return fid, ok, msg

        with ThreadPoolExecutor(max_workers=3) as pool:
            for fid, ok, msg in pool.map(one, todo):
                if ok:
                    self.installed.add(fid)
                else:
                    failed.append((fid, msg))
        for fid, _ in failed:
            ok, msg = self.dev.install(self.apk(fid), grant=grant)
            if not ok:
                raise CheckFailed(f"夹具 {fid} 安装失败：{msg}")
            self.installed.add(fid)

    def uninstall(self, *fids):
        for f in fids:
            self.dev.uninstall(self.pkg(f))
            self.installed.discard(f)

    def cmd(self, fid, cmd, arg=None):
        a = f" --es arg '{arg}'" if arg is not None else ""
        if cmd == "publish":
            self.published.add(self.pkg(fid))
        elif cmd == "unpublish":
            self.published.discard(self.pkg(fid))
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
            time.sleep(0.15)
        return None

    def cleanup(self, uninstall=False, only_published=False):
        pkgs = self.dev.packages(FIX)
        if only_published:
            pkgs = [p for p in pkgs if p in self.published]
        # 卸载不会清掉夹具写进 TvProvider 的行；不先 unpublish，下一轮会在“没有内容来源”场景里
        # 读到上一轮残留的继续观看行（D103）
        for p in pkgs:
            self.dev.sh(f"am broadcast -n {p}/{FIX}.CmdReceiver --es cmd unpublish", timeout=20)
        if pkgs:
            time.sleep(0.3)
        if uninstall:
            for p in pkgs:
                self.dev.uninstall(p)
            self.installed.clear()


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
    """界面文字：禁用词与白名单都只约束桌面自身文案；豁免子树（应用名、内容等）整体跳过。"""
    cp = spec("ui_copy")
    exact = set(cp["exact"]) | set(extra_ok)
    pats = [re.compile(p) for p in cp["patterns"]]
    banned = [re.compile(p) for p in cp["banned"]]
    content = content_strings()
    # 应用磁贴上的文字来自应用名（含第三方应用），在任何界面里出现都算内容而不是产品文案
    for _, tree in dumps:
        for n in tree.nodes:
            if n.pkg != PKG:
                continue
            if any(x.rid.startswith("app:") for x in [n] + list(n.ancestors())):
                for t in (n.text, n.desc):
                    if t and t.strip():
                        content.add(t.strip())
    bad = {}
    for name, tree in dumps:
        for n in tree.nodes:
            if n.pkg != PKG:
                continue
            chain = [n] + list(n.ancestors())
            if any(x.rid.startswith(tuple(cp["exemptTagPrefixes"])) for x in chain):
                continue
            if cp.get("exemptWidgetHostSubtree") and any(re.fullmatch(r"widget:\d+", x.rid or "") for x in chain):
                continue
            # Hero 的内容标题/副标题与浏览态信息头都来自外部内容（Watch Next）；
            # 只有按钮等产品文字在 hero:* / info:* 标签下
            if next((x.rid for x in chain if x.rid), "") in ("hero", "info"):
                continue
            for t in (n.text, n.desc):
                t = (t or "").strip()
                if not t or t in content:
                    continue
                for b in banned:
                    if b.search(t):
                        bad[t] = f"禁用词 /{b.pattern}/ @ {name}"
                if t in exact or any(p.search(t) for p in pats):
                    continue
                bad.setdefault(t, f"不在 ui_copy 白名单 @ {name}")
    return [f"「{k}」{v}" for k, v in bad.items()]
