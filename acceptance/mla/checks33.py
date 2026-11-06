"""v3.3 增补（aerial-tv 对照）：文件夹、按键音效、触屏、高对比焦点框、时钟与天气、信号源键、
自己的视频、解码器交接、屏保兜底、开机目标、儿童 / 长辈模式、默认桌面、诊断、onTrimMemory、根证书、更新。"""
from __future__ import annotations

import http.client
import json
import re
import socket
import threading
import time

import numpy as np

from .base import KIT, PASS, CheckFailed, Skip, sha256_file
from .checks import PROTO_APPS, api, check, need
from .checks3 import widgets_page
from .checks45 import open_qs
from .launcher import FIX, PKG, PROJ, Fixtures
from .mock import MockServer, file_route, json_route, open_meteo

P = Fixtures.pkg
ACT = f"{FIX}.LauncherActivity"
MEDIA = KIT / "fixtures" / "media"


# ===================================================================== 通用
def app_tag(fid):
    return f"app:{P(fid)}/{ACT}"


def label(fid):
    c = json.loads((KIT / "fixtures" / "content.json").read_text(encoding="utf-8"))
    return c["apps"][fid]["label"]


def launches(ctx, fid):
    return [e for e in ctx.F.log(fid) if e.get("event") == "launched"]


def open_settings(ctx, item=None):
    open_qs(ctx)
    need(ctx.L.seek("qs:launcher_settings", ("DOWN", "UP"), 20), "快速设置里找不到「桌面设置」")
    ctx.L.press("OK")
    st = ctx.L.settle()
    need(st.get("page") == "settings", f"page={st.get('page')}")
    if item:
        need(ctx.L.seek(item, ("DOWN",), 40), f"桌面设置里找不到 {item}")
        ctx.L.press("OK")
        return ctx.L.settle(3)
    return st


def center(n):
    x1, y1, x2, y2 = n.bounds
    return (x1 + x2) // 2, (y1 + y2) // 2


def ring_sides(ctx, img, node) -> int:
    """高对比焦点框：在节点四条边附近找 ≥2.5dp 的白色带并紧邻 ≥1dp 的黑色带，返回命中的边数。"""
    a = np.asarray(img).astype(int)
    h, w, _ = a.shape
    x1, y1, x2, y2 = node.bounds
    rng = int(ctx.dev.px(14))
    wmin, bmin = max(2, int(ctx.dev.px(2.5))), max(1, int(ctx.dev.px(1)))
    white = lambda p: p.min() >= 225
    black = lambda p: p.max() <= 40

    def scan(pixels):
        kinds = ["w" if white(p) else ("b" if black(p) else "o") for p in pixels]
        runs, cur, n = [], None, 0
        for k in kinds:
            if k == cur:
                n += 1
            else:
                if cur:
                    runs.append((cur, n))
                cur, n = k, 1
        runs.append((cur, n))
        for i in range(len(runs) - 1):
            (k1, n1), (k2, n2) = runs[i], runs[i + 1]
            if (k1, k2) in (("w", "b"), ("b", "w")) and (n1 if k1 == "w" else n2) >= wmin and (n2 if k2 == "b" else n1) >= bmin:
                return True
        return False

    my, mx = (y1 + y2) // 2, (x1 + x2) // 2
    lines = [a[my, max(0, x1 - rng):min(w, x1 + rng)], a[my, max(0, x2 - rng):min(w, x2 + rng)][::-1],
             a[max(0, y1 - rng):min(h, y1 + rng), mx], a[max(0, y2 - rng):min(h, y2 + rng), mx][::-1]]
    return sum(1 for l in lines if len(l) and scan(l))


# ---------------------------------------------------------------- 手机与电脑会话
class Organizer:
    def __init__(self, ctx):
        self.ctx = ctx

    def __enter__(self):
        ctx = self.ctx
        open_settings(ctx, "settings:organizer")
        time.sleep(1.5)
        img, tree, st = ctx.snap("organizer")
        o = st.get("organizer") or {}
        need(o.get("running") and o.get("address") and o.get("port") and o.get("token"), f"状态接口 organizer={o}（CONTRACT §8）")
        need(tree.find("organizer:qr"), "「手机与电脑」页没有二维码（organizer:qr）")
        self.token = o["token"]
        self.host = f"{o['address']}:{int(o['port'])}"
        with socket.socket() as so:
            so.bind(("127.0.0.1", 0))
            self.local = so.getsockname()[1]
        ctx.dev.adb("forward", f"tcp:{self.local}", f"tcp:{int(o['port'])}")
        self.session = None
        return self

    def __exit__(self, *a):
        self.ctx.dev.adb("forward", "--remove", f"tcp:{self.local}")

    def req(self, method, path, body=None, headers=None, timeout=20):
        c = http.client.HTTPConnection("127.0.0.1", self.local, timeout=timeout)
        h = dict(headers or {})
        h.setdefault("Host", self.host)
        if self.session and "X-Session" not in h:
            h["X-Session"] = self.session
        try:
            c.request(method, path, body=body, headers=h)
            r = c.getresponse()
            return r.status, r.read()
        except Exception as e:
            return str(e), b""
        finally:
            c.close()

    def refresh(self):
        """重新读取状态接口里的当前令牌（允许或拒绝一次连接后令牌会刷新）。"""
        time.sleep(0.2)
        o = self.ctx.L.state().get("organizer") or {}
        need(o.get("token"), f"状态接口 organizer={o}")
        self.token = o["token"]
        return self.token

    def login(self):
        """用当前令牌请求会话，电视上弹出 confirm_phone 时允许。"""
        code, body = self.confirmed("POST", "/api/session", headers={"X-Token": self.token}, allow=True, timeout=70)
        need(code == 200, f"POST /api/session（电视允许）返回 {code}")
        self.session = json.loads(body)["session"]
        return self.session

    def confirmed(self, method, path, body=None, headers=None, allow=True, timeout=40):
        """后台发请求；电视上出现 confirm_phone 时按允许 / 拒绝；返回 (状态码, 正文)。"""
        out = {}
        t = threading.Thread(target=lambda: out.update(r=self.req(method, path, body, headers, timeout=timeout)), daemon=True)
        t.start()
        t0 = time.time()
        while time.time() - t0 < 15 and t.is_alive():
            if self.ctx.L.state().get("overlay") == "confirm_phone":
                tag = "confirm_phone:allow" if allow else "confirm_phone:deny"
                need(self.ctx.L.seek(tag, ("LEFT", "RIGHT", "DOWN", "UP"), 4), f"确认框里找不到 {tag}")
                self.ctx.L.press("OK")
                break
            time.sleep(0.1)
        t.join(timeout)
        return out.get("r", ("timeout", b""))


# ===================================================================== M1
@check("D119", "文件夹：移入新建（预设名）、3×3 磁贴、打开与 BACK、移空后消失", "M1")
def d119(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    need(ctx.seek_row("apps"), "到不了应用行")
    # 应用行只显示前 10 个；用行内实际存在的两个夹具，避免被真实应用挤出
    t0, items, fids = time.time(), [], []
    while time.time() - t0 < 10:
        items = ctx.row_items(ctx.L.state(), "apps") or []
        fids = [k.split(f"app:{FIX}.", 1)[1].split("/")[0] for k in items if k.startswith(f"app:{FIX}.")]
        if len(fids) >= 2:
            break
        time.sleep(0.1)
    need(len(fids) >= 2, f"应用行夹具不足（行内 {items}）")
    tags = [app_tag(fids[0]), app_tag(fids[1])]
    for i, tag in enumerate(tags):
        need(ctx.seek_row("apps") and ctx.L.seek(tag, ("RIGHT", "LEFT"), 20), f"应用行找不到 {tag}")
        ctx.L.press("OK", long=True)
        need(ctx.L.seek("menu:folder", ("DOWN", "UP"), 10), "菜单里没有「移到文件夹」（menu:folder）")
        ctx.L.press("OK")
        st = ctx.L.settle()
        need(st.get("overlay") == "folder_pick", f"overlay={st.get('overlay')}")
        if i == 0:
            need(ctx.L.seek("folder_pick:new", ("DOWN", "UP"), 10), "没有「新建文件夹」")
            ctx.L.press("OK")
            need(ctx.L.seek("folder_name:tools", ("DOWN", "UP", "RIGHT", "LEFT"), 10), "预设名称里没有「工具」")
            ctx.L.press("OK")
        else:
            fid0 = ctx.L.state()["folders"][0]["id"]
            need(ctx.L.seek(f"folder_pick:{fid0}", ("DOWN", "UP"), 10), f"找不到已有文件夹 folder_pick:{fid0}")
            ctx.L.press("OK")
    st = ctx.L.settle()
    fs = st.get("folders") or []
    need(len(fs) == 1 and fs[0].get("name") == "工具" and fs[0].get("apps") == tags, f"folders={fs}")
    ftag = f"folder:{fs[0]['id']}"
    st = ctx.home()
    items = ctx.row_items(st, "apps") or []
    need(ftag in items and tags[0] not in items, f"应用行 {items}")
    need(ctx.seek_row("apps") and ctx.L.seek(ftag, ("RIGHT", "LEFT"), 12), f"应用行找不到 {ftag}")
    img, tree, st = ctx.snap("folder-tile")
    n = tree.find(ftag)
    need(n and any("工具" in t for t in n.texts()), "文件夹磁贴下没有名称")
    ctx.L.press("OK")
    st = ctx.L.settle()
    t0 = time.time()
    while time.time() - t0 < 2 and not (st.get("overlay") == "folder_open" and ctx.L.focus(st).startswith("app:")):
        time.sleep(0.1)
        st = ctx.L.state()
    need(st.get("overlay") == "folder_open" and ctx.L.focus(st).startswith("app:"), f"打开后 overlay={st.get('overlay')} 焦点 {ctx.L.focus(st)}")
    ctx.snap("folder-open")
    st = ctx.L.press("BACK")
    t0 = time.time()
    while time.time() - t0 < 1.5 and (st.get("overlay") or ctx.L.focus(st) != ftag):
        time.sleep(0.1)
        st = ctx.L.state()
    need(not st.get("overlay") and ctx.L.focus(st) == ftag, f"BACK 后 overlay={st.get('overlay')} 焦点 {ctx.L.focus(st)}")
    for _ in range(2):
        ctx.L.press("OK")
        ctx.L.settle()
        ctx.L.press("OK", long=True)
        need(ctx.L.seek("menu:unfolder", ("DOWN", "UP"), 10), "菜单里没有「移出文件夹」（menu:unfolder）")
        ctx.L.press("OK")
        st = ctx.L.settle()
        if not st.get("folders"):
            break
        ctx.home()
        ctx.seek_row("apps")
        ctx.L.seek(ftag, ("RIGHT", "LEFT"), 12)
    st = ctx.home()
    need(not st.get("folders") and ftag not in (ctx.row_items(st, "apps") or []), f"移空后文件夹仍在：{st.get('folders')}")


@check("D120", "触屏与鼠标：点按打开一次、按住打开菜单、点浮层外关闭浮层", "M1")
def d120(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    need(ctx.seek_row("apps"), "到不了应用行")
    # 应用行按更新时间取前 10 个，真实应用可能挤掉某个夹具；用行内实际存在的两个夹具
    t0, items = time.time(), []
    while time.time() - t0 < 10:
        items = ctx.row_items(ctx.L.state(), "apps") or []
        if sum(1 for k in items if k.startswith(f"app:{FIX}.")) >= 2:
            break
        time.sleep(0.1)
    fids = [k.split(f"app:{FIX}.", 1)[1].split("/")[0] for k in items if k.startswith(f"app:{FIX}.")]
    need(len(fids) >= 2, f"应用行夹具不足（行内 {items}）")
    fid, fid2 = fids[0], fids[1]
    tag = app_tag(fid)
    need(ctx.L.seek(tag, ("RIGHT", "LEFT"), 20), f"找不到 {tag}")
    tree = ctx.dev.dump()
    n = tree.find(tag)
    need(n, f"应用行没有 {tag}（行内 {items}）")
    ctx.F.cmd(fid, "clear_log")
    x, y = center(n)
    ctx.dev.sh(f"input tap {x} {y}")
    ctx.F.wait(fid, lambda e: e.get("event") == "launched", 8)
    need(len(launches(ctx, fid)) == 1, f"点按后 launched {len(launches(ctx, fid))} 次")
    ctx.dev.key("BACK", wait=1.5)
    st = ctx.L.settle()
    need(ctx.L.alive(), "返回后桌面不在前台")
    tag2 = app_tag(fid2)
    need(ctx.L.seek(tag2, ("RIGHT", "LEFT"), 20), "找不到第二个应用卡片")
    tree = ctx.dev.dump()
    n = tree.find(tag2)
    need(n, "找不到第二个应用卡片")
    x, y = center(n)
    ctx.dev.sh(f"input swipe {x} {y} {x} {y} 1500")
    st = ctx.L.settle()
    need(st.get("overlay") == "menu", f"按住 1.5 秒后 overlay={st.get('overlay')}")
    ctx.L.press("BACK")
    open_qs(ctx)
    ctx.L.settle()
    img, tree, st = ctx.snap("qs-for-tap")
    q = tree.find("qs")
    need(q, "快速设置面板没有 qs 标签")
    w, h = ctx.dev.size
    tx = q.bounds[0] // 2 if q.bounds[0] > w * 0.3 else (q.bounds[2] + w) // 2
    ctx.dev.sh(f"input tap {tx} {h // 2}")
    st = ctx.L.settle()
    need(not st.get("overlay") and ctx.L.alive(), f"点面板外后 overlay={st.get('overlay')}，前台 {ctx.dev.resumed()}")


@check("D121", "按键音效：方向键每次移动一次 FX_FOCUS_NAVIGATION，程序移动与触屏不发声", "M1")
def d121(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    need(ctx.seek_row("apps"), "到不了应用行")
    ctx.L.press("LEFT")
    ctx.L.call("reset_counters")
    moved = 0
    for _ in range(3):
        before = ctx.L.focus()
        st = ctx.L.press("RIGHT")
        moved += ctx.L.focus(st) != before
    se = (ctx.L.state().get("counters") or {}).get("soundEffects") or {}
    need(se.get("nav") == moved, f"移动 {moved} 次，nav 音效 {se.get('nav')} 次")
    for _ in range(12):
        ctx.L.press("RIGHT")
    se = (ctx.L.state().get("counters") or {}).get("soundEffects") or {}
    need((se.get("invalid") or 0) >= 1, f"到行尾继续按右键没有 FX_KEYPRESS_INVALID：{se}")
    ctx.L.call("reset_counters")
    ctx.dev.key("HOME", wait=1.5) if ctx.dev.api >= 24 else ctx.L.start()
    tree = ctx.dev.dump()
    n = tree.find("nav:apps")
    if n:
        ctx.dev.sh("input tap %d %d" % center(n))
        time.sleep(1.2)
    se = (ctx.L.state().get("counters") or {}).get("soundEffects") or {}
    need(not any(se.values()), f"HOME 恢复焦点或触屏操作发出了音效：{se}")


# ===================================================================== M2
@check("D215", "高对比焦点框：4dp 白框加 2dp 黑描边", "M2")
def d215(ctx):
    ctx.F.install(*PROTO_APPS)
    try:
        ctx.L.pref("high_contrast_focus", "true")
        ctx.home()
        need(ctx.seek_row("apps"), "到不了应用行")
        ctx.L.press("RIGHT")
        time.sleep(1.2)
        img, tree, st = ctx.snap("high-contrast")
        n = tree.find(ctx.L.focus(st))
        need(n, "找不到焦点节点")
        sides = ring_sides(ctx, img, n)
        ctx.data["sides"] = sides
        need(sides >= 2, f"焦点四边中只有 {sides} 边找到白框 + 黑描边")
    finally:
        ctx.L.pref("high_contrast_focus", "false")


# ===================================================================== M3
def clock_text(ctx):
    t0, n = time.time(), None
    while time.time() - t0 < 5:
        n = ctx.dev.dump().find("clock")
        if n:
            break
        time.sleep(0.2)
    need(n, "顶栏没有时钟（clock）")
    return " ".join(n.texts())


@check("D327", "12 / 24 小时制：TIME_12_24 为空时 24 小时制，只有为 12 时才用 12 小时制", "M3")
def d327(ctx):
    orig = ctx.dev.sh("settings get system time_12_24").strip()
    try:
        res = {}
        for val in (None, "12", "24"):
            ctx.dev.sh("settings delete system time_12_24" if val is None else f"settings put system time_12_24 {val}")
            ctx.L.stop()
            ctx.L.start()
            ctx.home()
            t = clock_text(ctx)
            st = ctx.L.state()
            res[str(val)] = (t, (st.get("clock") or {}).get("is24h"))
        ctx.data["clock"] = res
        h12 = re.compile(r"(上午|下午|AM|PM)")
        need(not h12.search(res["None"][0]) and res["None"][1] is True, f"未设置时：{res['None']}")
        need(h12.search(res["12"][0]) and res["12"][1] is False, f"设为 12 时：{res['12']}")
        need(not h12.search(res["24"][0]) and res["24"][1] is True, f"设为 24 时：{res['24']}")
    finally:
        ctx.dev.sh("settings delete system time_12_24" if orig in ("", "null") else f"settings put system time_12_24 {orig}")


@check("D328", "天气：按设置的城市请求一次，显示温度；重启不重复请求", "M3")
def d328(ctx):
    with MockServer(ctx.dev, {"/v1/forecast": json_route(open_meteo())}) as m:
        try:
            ctx.L.pref("weather_city", "北京")
            ctx.L.pref("weather_base_url", m.base)
            ctx.L.stop()
            ctx.L.start()
            t0 = time.time()
            while time.time() - t0 < 40 and not m.hits("/v1/forecast"):
                time.sleep(0.25)
            hits = m.hits("/v1/forecast")
            need(hits, "40 秒内没有请求天气接口")
            q = hits[0]["query"]
            ctx.data["query"] = q
            for k in ("latitude", "longitude"):
                need(k in q and re.fullmatch(r"-?\d+(\.\d{1,2})?", q[k]) and abs(float(q[k]) - (39.9 if k == "latitude" else 116.4)) < 0.6,
                     f"{k}={q.get(k)}（应为北京的经纬度，最多两位小数）")
            t0 = time.time()
            w = {}
            while time.time() - t0 < 12:
                w = (ctx.L.state().get("weather") or {})
                if w.get("visible") and w.get("tempC") is not None:
                    break
                time.sleep(0.25)
            need(w.get("visible") and round(float(w.get("tempC", -99))) == 23, f"状态接口 weather={w}")
            widgets_page(ctx)
            time.sleep(1.5)
            img, tree, st = ctx.snap("weather-widget")
            n = tree.find("widget:builtin:clock")
            need(n and any("23°" in t for t in n.texts()), f"时钟与天气小组件没有显示 23°：{n.texts() if n else None}")
            for _ in range(1):
                ctx.L.stop()
                ctx.L.start()
                t1 = time.time()
                # 重启后仍观察 3.5 秒：若出现重复请求，计数会提前变化
                while time.time() - t1 < 3.5 and len(m.hits("/v1/forecast")) == 1:
                    time.sleep(0.2)
            hs = m.hits("/v1/forecast")
            need(len(hs) == 1, f"一小时内请求了 {len(hs)} 次：{[round(h.get('t', 0) % 1000, 1) for h in hs]}")
        finally:
            ctx.L.pref("weather_base_url", "")


# ===================================================================== M4
@check("D407", "信号源键：KEYCODE_TV_INPUT 打开信号源页，BACK 关闭", "M4")
def d407(ctx):
    ctx.home()
    ctx.dev.key("KEYCODE_TV_INPUT", wait=1.5)
    st = ctx.L.settle()
    via_key = st.get("overlay") == "inputs" and ctx.L.alive()
    if not via_key:
        # 部分平台的窗口策略层会消费 KEYCODE_TV_INPUT，事件不送达应用窗口；
        # 用快速设置的「信号源」入口验证同一个浮层，按键路径本身记为未验证。
        open_qs(ctx)
        need(ctx.L.seek("qs:inputs", ("DOWN", "UP"), 12), "快速设置里没有信号源（qs:inputs）")
        ctx.L.press("OK")
        st = ctx.L.settle()
        need(st.get("overlay") == "inputs" and ctx.L.alive(), f"overlay={st.get('overlay')}，前台 {ctx.dev.resumed()}")
    ctx.snap("inputs")
    st = ctx.L.press("BACK")
    need(not st.get("overlay"), f"BACK 后 overlay={st.get('overlay')}")
    if not via_key:
        return PASS, "KEYCODE_TV_INPUT 未被平台送达应用窗口，已用快速设置「信号源」验证同一浮层"


@check("D406", "手机与电脑：Host 校验、连接经电视确认（拒绝 403 / 允许 200）、令牌一次性、无会话 401、改名生效、离开即停止", "M4")
def d406(ctx):
    ctx.F.install(*PROTO_APPS)
    comp = f"{P('dolphin')}/{ACT}"
    with Organizer(ctx) as o:
        code, _ = o.req("GET", "/", headers={"Host": "attacker.example"})
        need(code == 421, f"Host 不符时 GET / 返回 {code}")
        code, _ = o.req("GET", "/api/apps")
        need(code == 401, f"没有会话时 GET /api/apps 返回 {code}")
        first = o.token
        code, _ = o.confirmed("POST", "/api/session", headers={"X-Token": first}, allow=False, timeout=70)
        need(code == 403, f"电视上拒绝连接后返回 {code}")
        need(o.refresh() != first, "拒绝之后令牌没有刷新")
        code, _ = o.req("POST", "/api/session", headers={"X-Token": first})
        need(code == 401, f"已作废的令牌再次使用返回 {code}")
        used = o.token
        o.login()
        need(o.refresh() != used, "允许之后令牌没有刷新")
        code, _ = o.req("POST", "/api/session", headers={"X-Token": used})
        need(code == 401, f"一次性令牌第二次使用返回 {code}")
        code, body = o.req("GET", "/api/apps")
        need(code == 200, f"带会话 GET /api/apps 返回 {code}")
        apps = json.loads(body)
        need(any(a.get("component") == comp for a in apps), f"应用列表里没有 {comp}")
        code, _ = o.req("POST", "/api/apps/label", json.dumps({"component": comp, "label": "改名测试"}).encode(),
                        {"Content-Type": "application/json"})
        need(code == 200, f"会话内改名返回 {code}")
        need(ctx.L.state().get("overlay") != "confirm_phone", "会话已确认，改名时不应再弹出确认")
        ctx.dev.key("BACK", wait=1)
        ctx.dev.key("BACK", wait=1)
        st = ctx.home()
        # 5 秒内服务必须停止：轮询直到连接失败，超过 5 秒仍在响应即失败
        t0 = time.time()
        code = 200
        while time.time() - t0 < 5:
            code, _ = o.req("GET", "/api/apps", timeout=5)
            if not isinstance(code, int):
                break
            time.sleep(0.2)
        need(not isinstance(code, int), f"离开页面 5 秒后服务仍在响应：{code}")
    st = ctx.home()
    ctx.wait_item("apps", app_tag("dolphin"))
    need(ctx.L.seek("nav:home", ("UP",), 8) and ctx.L.seek("nav:apps", ("RIGHT",), 4), "到不了应用页")
    t0 = time.time()
    while time.time() - t0 < 4 and ctx.L.state().get("page") != "apps":
        time.sleep(0.1)
    ctx.L.press("DOWN")
    # 应用页网格只渲染视口内的格子；逐行下移并读树，比盲目按键更快也更稳
    n = None
    for _ in range(10):
        tree = ctx.dev.dump()
        n = tree.find(app_tag("dolphin"))
        if n:
            break
        before = ctx.L.focus()
        ctx.L.press("DOWN", settle=False)
        ctx.L._wait_focus_change(before, 0.6)
    need(n, "应用页找不到海豚视频")
    need(any("改名测试" in t for t in n.texts()), f"改名后应用页仍显示 {n.texts()}")


@check("D408", "自己的视频：流式上传、非视频拒绝、按 Content-Length 预检超限", "M4")
def d408(ctx):
    with Organizer(ctx) as o:
        o.login()
        code, body = o.req("POST", "/api/clips", (MEDIA / "garbage.mp4").read_bytes(),
                           {"X-Filename": "garbage.mp4", "Content-Type": "video/mp4"}, timeout=40)
        need(code == 415 and "无法播放这个视频" in body.decode("utf-8", "replace"), f"非视频文件：{code} {body[:120]!r}")
        code, body = o.req("POST", "/api/clips", (MEDIA / "sample.mp4").read_bytes(),
                           {"X-Filename": "sample.mp4", "Content-Type": "video/mp4"}, timeout=40)
        need(code == 200 and json.loads(body).get("id"), f"H.264 视频：{code} {body[:120]!r}")
        # 只发请求头，不发正文：桌面必须按 Content-Length 预检，直接返回 413
        head = (f"POST /api/clips HTTP/1.1\r\nHost: {o.host}\r\nX-Session: {o.session}\r\n"
                f"X-Filename: huge.mp4\r\nContent-Type: video/mp4\r\nContent-Length: {3 << 30}\r\n\r\n")
        buf = b""
        try:
            with socket.create_connection(("127.0.0.1", o.local), timeout=0.5) as s:
                s.sendall(head.encode("ascii"))
                t0 = time.time()
                while time.time() - t0 < 30 and b"\r\n" not in buf:
                    try:
                        d = s.recv(4096)
                        if not d:
                            break
                        buf += d
                    except (socket.timeout, TimeoutError):
                        pass
        except OSError as e:
            buf = buf or str(e).encode()
        m = re.match(rb"HTTP/1\.[01] (\d{3})", buf)
        code = int(m.group(1)) if m else buf[:80]
        need(code == 413, f"Content-Length 3GB 返回 {code}，应在接收正文前返回 413")
    ctx.dev.key("BACK", wait=1)


@check("D409", "解码器交接：其他应用来到前台后 2.5 秒内释放播放器", "M4")
def d409(ctx):
    ctx.F.install("dolphin")
    try:
        ctx.L.pref("ambient_idle_s", "5")
        ctx.home()
        t0 = time.time()
        st = ctx.L.state()
        while time.time() - t0 < 20 and (st.get("ambient") or {}).get("player") != "playing":
            time.sleep(0.5)
            st = ctx.L.state()
        if (st.get("ambient") or {}).get("player") != "playing":
            raise Skip(f"20 秒内环境模式没有开始播放视频（ambient={st.get('ambient')}，可能没有网络）")
        ctx.dev.sh(f"am start -n {P('dolphin')}/{ACT}")
        t0 = time.time()
        a = ctx.L.state().get("ambient") or {}
        while time.time() - t0 < 3.5 and a.get("player") not in ("released", "none"):
            time.sleep(0.1)
            a = ctx.L.state().get("ambient") or {}
        need(a.get("player") in ("released", "none"), f"其他应用来到前台 3.5 秒后 player={a.get('player')}")
        ctx.dev.key("BACK", wait=1.5)
    finally:
        ctx.L.pref("ambient_idle_s", "0")


@check("D410", "屏保兜底：系统未选本应用屏保时，空闲后进入桌面内屏保；快速设置可立即启动；任意键回到原焦点", "M4")
def d410(ctx):
    ctx.F.install(*PROTO_APPS)
    try:
        ctx.L.pref("saver_idle_s", "5")
        ctx.home()
        need(ctx.seek_row("apps"), "到不了应用行")
        ctx.L.press("RIGHT")
        want = ctx.L.focus()
        t0 = time.time()
        st = ctx.L.state()
        while time.time() - t0 < 10 and st.get("mode") != "saver":
            time.sleep(0.25)
            st = ctx.L.state()
        need(st.get("mode") == "saver" and ctx.L.alive(), f"空闲 10 秒后 mode={st.get('mode')}，前台 {ctx.dev.resumed()}")
        _, _, _, ev = ctx.L.snap("saver", dump=False)
        ctx.ev += ev
        ctx.dev.key("DOWN", wait=1.5)
        st = ctx.L.settle()
        need(st.get("mode") != "saver" and ctx.L.focus(st) == want, f"按键后 mode={st.get('mode')} 焦点 {ctx.L.focus(st)}，应为 {want}")
        ctx.L.pref("saver_idle_s", "0")
        open_qs(ctx)
        ctx.L.settle(2)
        need(ctx.L.seek("qs:saver", ("DOWN", "UP"), 20), "快速设置里没有屏保（qs:saver）")
        ctx.L.press("OK")
        t0 = time.time()
        st = ctx.L.state()
        while time.time() - t0 < 2 and st.get("mode") != "saver":
            time.sleep(0.1)
            st = ctx.L.state()
        need(st.get("mode") == "saver", "「立即启动」没有进入屏保")
        ctx.dev.key("BACK", wait=1.5)
    finally:
        ctx.L.pref("saver_idle_s", "0")


def reboot(ctx):
    ctx.dev.adb("reboot", timeout=60)
    time.sleep(3)
    t0 = time.time()
    while time.time() - t0 < 300:
        if ctx.dev.sh("getprop sys.boot_completed").strip() == "1":
            time.sleep(8)
            ctx.dev.sh("input keyevent KEYCODE_WAKEUP")
            return
        time.sleep(0.5)
    raise CheckFailed("重启后 5 分钟未完成启动")


@check("D411", "开机 / 唤醒目标：唤醒和开机后各打开一次，回到主页不再打开", "M4", when=api(24))
def d411(ctx):
    ctx.F.install("dolphin")
    comp = f"{P('dolphin')}/{ACT}"
    try:
        ctx.L.pref("boot_target", f"app:{comp}")
        ctx.home()
        ctx.dev.key("KEYCODE_SLEEP", wait=0.5)
        t0 = time.time()
        while time.time() - t0 < 5 and "mWakefulness=Asleep" not in ctx.dev.sh("dumpsys power"):
            time.sleep(0.2)
        # 熄屏广播可能晚于 mWakefulness 更新；等屏幕稳定再唤醒，否则会在唤醒后收到迟到的 SCREEN_OFF
        time.sleep(1.5)
        ctx.F.cmd("dolphin", "clear_log")
        time.sleep(0.3)
        ctx.dev.key("KEYCODE_WAKEUP", wait=0.5)
        ctx.F.wait("dolphin", lambda e: e.get("event") == "launched", 8)
        time.sleep(1.5)
        n1 = len(launches(ctx, "dolphin"))
        bootlog = ctx.dev.sh("logcat -d -s MLBOOT:D").strip().splitlines()[-6:]
        need(n1 == 1, f"唤醒后打开 {n1} 次：{[e.get('ts') for e in launches(ctx, 'dolphin')][:4]} / {bootlog}")
        ctx.dev.key("HOME", wait=1.5)
        need(len(launches(ctx, "dolphin")) == 1, "回到主页后又打开了目标应用")
        reboot(ctx)
        t0 = time.time()
        n2 = len(launches(ctx, "dolphin"))
        while time.time() - t0 < 30 and n2 < 2:
            time.sleep(0.5)
            n2 = len(launches(ctx, "dolphin"))
        need(n2 == 2, f"开机后目标应用打开次数 {n2 - 1}")
        ctx.dev.key("HOME", wait=1.5)
        need(len(launches(ctx, "dolphin")) == 2, "开机后回到主页又打开了目标应用")
    finally:
        try:
            ctx.L.pref("boot_target", "home")
            ctx.dev.set_home(ctx.L.comp)
        except Exception:
            pass


def restricted(st):
    return st.get("restricted") or {}


def enter_pin(ctx, pin, expect_error=False, known_open=False):
    if known_open:
        st = ctx.L.state()
    else:
        st = ctx.L.settle()
    need(st.get("overlay") == "pin", f"overlay={st.get('overlay')}，应为 PIN 输入")
    for d in pin:
        ctx.dev.key(f"KEYCODE_{d}", wait=0.1)
    if expect_error:
        # 错误 PIN 不会关闭浮层，状态里没有可等待的信号，留一拍给应用处理
        time.sleep(0.8)
        return ctx.L.state()
    t0 = time.time()
    while time.time() - t0 < 3:
        st = ctx.L.state()
        if st.get("overlay") != "pin":
            return st
        time.sleep(0.1)
    return st


def config_receiver(ctx):
    from .static import A, manifest
    from .base import PROJECT
    m = manifest(PROJECT / PROJ["gradle"]["debugApk"])
    for r in m.iter("receiver"):
        n = r.get(A + "name") or ""
        if n.endswith(".config.ConfigReceiver"):
            return f"{PKG}/{n}"
    raise CheckFailed("清单里没有 .config.ConfigReceiver")


@check("D412", "儿童模式：只显示允许项、拦截不允许的应用、额度用完拉起锁屏、PIN 加时、PIN 不存明文、ADB 清除 PIN", "M4", when=api(26))
def d412(ctx):
    ctx.F.install(*PROTO_APPS)
    pin = "9731"
    for op in ("SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS"):
        ctx.dev.sh(f"appops set {PKG} {op} allow")
    phases, t_start = {}, time.time()
    def mark(k):
        phases[k] = round(time.time() - t_start, 1)
    try:
        ctx.L.pref("restricted_allowed", P("dolphin"))
        ctx.L.pref("pin", pin)
        ctx.L.pref("kids_quota_s", "8")
        ctx.L.pref("restricted_mode", "kids")
        st = ctx.home()
        img, tree, st = ctx.snap("kids-home")
        mark("home")
        need(restricted(st).get("mode") == "kids", f"restricted={restricted(st)}")
        rows = [r["id"] for r in st.get("rows", [])]
        need(not (st.get("hero") or {}).get("visible") and not any(r.startswith(("ch:", "continue", "widgets")) for r in rows), f"儿童模式主页行 {rows}")
        apps = [k for k in (ctx.row_items(st, "apps") or []) if k.startswith("app:")]
        need(apps == [app_tag("dolphin")], f"儿童模式应用行 {apps}")
        ctx.dev.sh(f"am start -n {P('store')}/{ACT}")
        t0 = time.time()
        while time.time() - t0 < 5 and ctx.dev.resumed_pkg() == P("store"):
            time.sleep(0.2)
        need(ctx.dev.resumed_pkg() != P("store"), "不在允许列表的应用仍在运行")
        ctx.home()
        mark("blocked")
        need(ctx.seek_row("apps") and ctx.L.seek(app_tag("dolphin"), ("RIGHT", "LEFT"), 4), "找不到允许的应用")
        ctx.L.press("OK")
        t0 = time.time()
        while time.time() - t0 < 5 and ctx.dev.resumed_pkg() != P("dolphin"):
            time.sleep(0.2)
        need(ctx.dev.resumed_pkg() == P("dolphin"), f"允许的应用没有打开：{ctx.dev.resumed()}")
        t0 = time.time()
        while time.time() - t0 < 12:
            if ctx.dev.resumed_pkg() == PKG and ctx.L.state().get("page") == "kids_lock":
                break
            time.sleep(0.5)
        mark("lock")
        img, tree, st = ctx.snap("kids-lock")
        need(st.get("page") == "kids_lock" and restricted(st).get("locked"), f"额度用完后没有拉起锁屏：前台 {ctx.dev.resumed()}，page={st.get('page')}")
        need(ctx.L.seek("kids_lock:extend15", ("DOWN", "RIGHT", "UP", "LEFT"), 8), "锁屏上没有「+15 分钟」")
        ctx.L.press("OK")
        st = enter_pin(ctx, pin)
        mark("pin_ok")
        r = restricted(st)
        need(not r.get("locked") and (r.get("remainingS") or 0) >= 14 * 60, f"输入 PIN 加时后 restricted={r}")
        out = ctx.dev.sh(f"run-as {PKG} sh -c 'cat files/datastore/* shared_prefs/* files/*.json 2>/dev/null'")
        need(not re.search(rf"(?<![0-9A-Za-z]){pin}(?![0-9A-Za-z])", out), "桌面数据文件中出现了明文 PIN")
        t0, st = time.time(), ctx.L.state()
        if st.get("overlay") == "pin":
            t_qs = t_parent = 0.0
        else:
            if st.get("overlay") != "quick_settings":
                open_qs(ctx)
            t_qs = round(time.time() - t0, 1)
            # 一次注入 10 个 DOWN 越过快速设置的大部分条目，再按状态找「家长」
            ctx.dev.sh("input keyevent " + " ".join(["KEYCODE_DPAD_DOWN"] * 10), timeout=40)
            ctx.L.settle()
            need(ctx.L.seek("qs:parent", ("DOWN", "UP"), 12), "快速设置里没有「家长」（qs:parent）")
            ctx.L.press("OK")
            t_parent = round(time.time() - t0, 1)
            st = ctx.L.state()
            need(st.get("overlay") == "pin", f"家长入口没有打开 PIN 浮层：overlay={st.get('overlay')}")
        # 连续 5 次错误 PIN：一次注入 20 个数字键（应用每 4 位校验一次），比逐次往返快得多
        t_inject = time.time()
        ctx.dev.sh("input keyevent " + " ".join(["KEYCODE_0"] * 20), timeout=120)
        t0, r = time.time(), restricted(ctx.L.state())
        while time.time() - t0 < 10 and (r.get("pinLockedUntil") or 0) <= 0:
            time.sleep(0.2)
            r = restricted(ctx.L.state())
        ctx.data["pinWrong"] = {"t_qs": t_qs, "t_parent": t_parent, "t_inject": round(time.time() - t_inject, 1)}
        mark("pin_wrong5")
        need((r.get("pinLockedUntil") or 0) > 0, f"连续输错 5 次后没有锁定：{r}")
        ctx.dev.key("BACK", wait=1)
        out = ctx.dev.sh(f"am broadcast -n {config_receiver(ctx)} --ez clear_pin true")
        time.sleep(1.5)
        need(not restricted(ctx.L.state()).get("hasPin"), f"ADB 清除 PIN 后仍有 PIN：{out.strip()[:100]}")
        mark("done")
        ctx.data["phases"] = phases
    finally:
        ctx.L.pref("restricted_mode", "off")
        ctx.L.pref("kids_quota_s", "0")
        ctx.L.pref("restricted_allowed", "")


@check("D413", "长辈模式：大磁贴每排 4 个、名称常显、强制高对比焦点框", "M4")
def d413(ctx):
    allowed = ["dolphin", "spark", "cloud", "pulse", "sky", "files"]
    ctx.F.install(*allowed)
    try:
        ctx.L.pref("restricted_allowed", ",".join(P(f) for f in allowed))
        ctx.L.pref("restricted_mode", "elders")
        st = ctx.home()
        need(ctx.seek_row("apps"),
             f"到不了应用行（rows={[r['id'] for r in ctx.L.state().get('rows', [])]}，焦点 {ctx.L.focus()}）")
        img, tree, st = ctx.snap("elders")
        tiles = [n for n in tree.nodes if n.rid in {app_tag(f) for f in allowed}]
        need(len(tiles) == len(allowed), f"屏幕上有 {len(tiles)} 个允许的应用磁贴，应为 {len(allowed)}")
        top = min(n.bounds[1] for n in tiles)
        first = [n for n in tiles if abs(n.bounds[1] - top) < ctx.dev.px(12)]
        need(len(first) == 4, f"第一排 {len(first)} 个磁贴，应为 4")
        missing = [f for f in allowed
                   if not any(t.strip() for t in (tree.find(app_tag(f)).texts() if tree.find(app_tag(f)) else []))]
        need(not missing, f"没有常显名称：{missing}")
        n = tree.find(ctx.L.focus(st))
        need(n and ring_sides(ctx, img, n) >= 2, "长辈模式没有高对比焦点框")
    finally:
        ctx.L.pref("restricted_mode", "off")
        ctx.L.pref("restricted_allowed", "")


# ===================================================================== M5
@check("D507", "默认桌面页：显示当前状态；被替换后显示当前默认桌面的名称", "M5", when=api(24))
def d507(ctx):
    orig = ctx.flags.get("originalHome")
    try:
        st = open_settings(ctx, "settings:default_home")
        img, tree, st = ctx.snap("default-home")
        need(st.get("page") == "default_home", f"page={st.get('page')}")
        need(any(t == "已是默认桌面" for n in tree.nodes for t in (n.text, n.desc)), "没有显示「已是默认桌面」")
        need((st.get("defaultHome") or {}).get("isDefault") is True, f"defaultHome={st.get('defaultHome')}")
        if not orig or orig.startswith(PKG):
            raise Skip("没有记录原默认桌面，无法测试被替换的情况")
        ctx.dev.set_home(orig)
        ctx.dev.sh(f"am start -n {ctx.L.comp}")
        time.sleep(2)
        st = open_settings(ctx, "settings:default_home")
        img, tree, st = ctx.snap("default-home-lost")
        need((st.get("defaultHome") or {}).get("isDefault") is False, f"defaultHome={st.get('defaultHome')}")
        need(any(t.startswith("当前默认桌面：") for n in tree.nodes for t in (n.text, n.desc)), "没有显示「当前默认桌面：…」")
    finally:
        ctx.dev.set_home(ctx.L.comp)


@check("D508", "诊断报告：经手机页下载，含帧时间分位数与固件指纹，已脱敏", "M5")
def d508(ctx):
    with Organizer(ctx) as o:
        o.login()
        code, body = o.req("GET", "/api/diag", timeout=30)
        need(code == 200, f"GET /api/diag 返回 {code}")
        text = body.decode("utf-8", "replace")
    p = ctx.rep.path(ctx.dev.name, "diag.txt")
    p.write_text(text, encoding="utf-8")
    ctx.ev.append(ctx.rep.rel(p))
    msgs = []
    for k in ("P50", "P90", "P99"):
        if k not in text:
            msgs.append(f"缺少 {k}")
    fp = ctx.dev.prop("ro.build.fingerprint")
    if fp and fp not in text:
        msgs.append("缺少 Build.FINGERPRINT")
    ips = set(re.findall(r"inet (\d+\.\d+\.\d+\.\d+)", ctx.dev.sh("ip -4 addr"))) - {"127.0.0.1"}
    leaked = [ip for ip in ips if re.search(rf"(?<![\d.]){re.escape(ip)}(?![\d.])", text)]
    if leaked:
        msgs.append(f"含完整 IP：{leaked}")
    macs = {m.lower() for m in re.findall(r"(?i)link/ether ((?:[0-9a-f]{2}:){5}[0-9a-f]{2})", ctx.dev.sh("ip link"))}
    macs -= {"00:00:00:00:00:00"}
    if any(m in text.lower() or m.replace(":", "-") in text.lower() for m in macs):
        msgs.append("含完整 MAC")
    serial = ctx.dev.prop("ro.serialno")
    if serial and len(serial) > 4 and serial in text:
        msgs.append("含序列号")
    if o.token in text or (o.session and o.session in text):
        msgs.append("含令牌或会话")
    if FIX in text:
        msgs.append("含已安装应用列表")
    need(not msgs, "；".join(msgs))


@check("D509", "onTrimMemory：UI_HIDDEN 不释放；BACKGROUND / RUNNING_LOW 清空图片缓存与冻结模糊位图", "M5")
def d509(ctx):
    ctx.F.install(*PROTO_APPS)
    if ctx.dev.api >= 26:
        ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)

    def used():
        mm = ctx.L.state().get("memory") or {}
        return (mm.get("imageCacheBytes") or 0) + (mm.get("frozenBlurBytes") or 0)

    def fill():
        ctx.home()
        for k in ("DOWN", "RIGHT", "RIGHT", "DOWN", "RIGHT", "UP", "UP"):
            ctx.L.press(k)
        time.sleep(2)
        return used()

    def trim(level):
        rc, out, err = ctx.dev.adb("shell", f"am send-trim-memory {PKG} {level}")
        out = (out + err).strip()
        ctx.data.setdefault("am", []).append(f"{level}: rc={rc} {out[:120]}")
        need(rc == 0 and not re.search(r"Error|Unable|Exception", out), f"send-trim-memory {level}：rc={rc} {out[:200]}")
        time.sleep(2)

    ctx.F.install("dolphin")
    if ctx.dev.api < 34:
        b = fill()
        need(b > 0, "浏览后 memory 字段为 0（状态接口没有统计？）")
        trim("RUNNING_LOW")
        a = used()
        need(a <= 0.1 * b, f"RUNNING_LOW 后从 {b} 只降到 {a}")
    b0 = fill()
    need(b0 > 0, "浏览后 memory 字段为 0（状态接口没有统计？）")
    ctx.dev.sh(f"am start -n {P('dolphin')}/{ACT}")
    time.sleep(3)
    trim("HIDDEN")
    b1 = used()
    need(b1 >= 0.5 * b0, f"转入后台（UI_HIDDEN）后从 {b0} 降到 {b1}，不应释放")
    trim("BACKGROUND")
    b2 = used()
    ctx.data.update(foreground=b0, hidden=b1, background=b2)
    need(b2 <= 0.1 * b0, f"BACKGROUND 后从 {b0} 只降到 {b2}")
    st = ctx.home()
    need(ctx.L.alive(), "回到桌面失败")


@check("D510", "旧系统证书：API 23 上用应用内置根证书访问 Let's Encrypt 站点", "M5",
       when=lambda ctx: None if ctx.dev.api <= 24 else "只在 API 23–24 上执行")
def d510(ctx):
    def call(url):
        out = ctx.L.call("net_check", url)
        m = re.search(r'\{\s*"ok"[^{}]*\}', out)
        try:
            return json.loads(m.group(0)) if m else {"ok": False, "error": out.strip()[:120]}
        except json.JSONDecodeError:
            return {"ok": False, "error": out.strip()[:120]}
    r = call("https://valid-isrgrootx1.letsencrypt.org/")
    ctx.data["isrg"] = r
    if not r.get("ok"):
        ref = call("https://www.baidu.com/")
        ctx.data["reference"] = ref
        if not ref.get("ok"):
            raise Skip(f"模拟器没有网络（参考站点也失败：{ref}）")
        raise CheckFailed(f"Let's Encrypt 站点失败：{r}")


@check("D511", "应用内更新：包名不符、versionCode 不升时拒绝，不安装", "M5")
def d511(ctx):
    vc = int(re.search(r"versionCode=(\d+)", ctx.dev.sh(f"dumpsys package {PKG}")).group(1))
    wrong = ctx.F.apk("dolphin")
    ctx.F.uninstall("dolphin")
    ver = {"versionCode": vc + 1, "versionName": "99.0.0", "minSdk": 23, "url": None, "size": wrong.stat().st_size,
           "sha256": sha256_file(wrong), "dm": None, "notes": [{"versionCode": vc + 1, "text": "测试"}]}
    routes = {"/pkg.apk": file_route(wrong, "application/vnd.android.package-archive")}
    with MockServer(ctx.dev, routes) as m:
        ver["url"] = m.base + "/pkg.apk"
        m.routes["/version.json"] = json_route(ver)
        try:
            ctx.L.pref("update_url", m.base + "/version.json")
            ctx.L.stop()
            ctx.L.start()
            t0 = time.time()
            while time.time() - t0 < 30 and (ctx.L.state().get("update") or {}).get("state") != "available":
                time.sleep(2)
            u = ctx.L.state().get("update") or {}
            need(u.get("state") == "available", f"版本文件 versionCode+1 后 update={u}")
            open_settings(ctx, "settings:update")
            need(ctx.L.seek("update:install", ("DOWN", "RIGHT", "UP"), 8), "更新页没有 update:install")
            ctx.L.press("OK")
            t0 = time.time()
            while time.time() - t0 < 40 and (ctx.L.state().get("update") or {}).get("state") not in ("error", "ready"):
                time.sleep(2)
            u = ctx.L.state().get("update") or {}
            need(u.get("state") == "error" and u.get("lastError") == "package", f"下载包名不符的 APK 后 update={u}")
            need(not ctx.dev.installed(P("dolphin")), "包名不符的 APK 被安装了")
            need(ctx.dev.resumed_pkg() == PKG, f"前台 {ctx.dev.resumed()}（不应弹出安装器）")
            ver["versionCode"] = vc - 1 if vc > 1 else vc
            m.routes["/version.json"] = json_route(ver)
            ctx.L.stop()
            ctx.L.start()
            time.sleep(8)
            u = ctx.L.state().get("update") or {}
            need(u.get("state") != "available", f"versionCode 不升时 update={u}")
        finally:
            ctx.L.pref("update_url", "")
