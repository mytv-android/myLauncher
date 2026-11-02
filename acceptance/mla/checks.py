"""设备检查。每个检查独立建立前置条件；同一设备上按编号顺序执行。"""
from __future__ import annotations

import json
import random
import re
import time

from .base import FAIL, MANUAL, PASS, SKIP, WARN, CheckFailed, Result, Skip, ms_le
from .device import contrast, delta_e, hf_energy, hue_diff, lch, mean_color, mode_color, std_color
from .launcher import FIX, PKG, Fixtures, Launcher, copy_violations

REG: list[dict] = []
PROTO_APPS = ["dolphin", "spark", "cloud", "prism", "pulse", "sky", "files", "store", "fit", "book"]
ACT = f"{FIX}.LauncherActivity"


def check(id_, title, ms, when=None, until=None):
    """when(ctx) -> None 表示适用，否则返回不适用的原因。until：只在不晚于该里程碑的验收中执行。"""
    def deco(fn):
        REG.append(dict(id=id_, title=title, ms=ms, when=when, until=until, fn=fn))
        return fn
    return deco


def need(cond, msg):
    if not cond:
        raise CheckFailed(msg)


# ---------------------------------------------------------------- 适用条件
def tvprovider(ctx):
    return None if ctx.dev.api >= 26 else "API < 26，没有 TvProvider"


def no_tvprovider(ctx):
    return None if ctx.dev.api < 26 else "只在 API 23–25 上执行"


def api(n):
    return lambda ctx: None if ctx.dev.api >= n else f"需要 API {n}+"


def t1_capable(ctx):
    return None if ctx.dev.api >= 31 else "T1 只在 API 31+"


# ---------------------------------------------------------------- 上下文
class Ctx:
    def __init__(self, dev, rep, home_comp, target_ms):
        self.dev, self.rep, self.ms = dev, rep, target_ms
        self.L = Launcher(dev, rep, home_comp)
        self.F = Fixtures(dev)
        self.flags: dict = {}
        self.ev: list[str] = []
        self.data: dict = {}

    # ---- 通用操作
    def snap(self, name, dump=True):
        img, tree, st, ev = self.L.snap(name, dump)
        self.ev += ev
        if tree is not None:
            diff = self.L.consistent(tree, st)
            if diff:
                raise CheckFailed("STATE_MISMATCH " + diff)
        return img, tree, st

    def home(self):
        if self.dev.api >= 24:
            self.dev.key("HOME", wait=1.2)
        else:
            self.L.start()
        st = self.L.settle(3)
        if self.L.dev.resumed_pkg() != PKG:
            st = self.L.start()
        return st

    def app_tag(self, fid):
        return f"app:{Fixtures.pkg(fid)}/{ACT}"

    def publish(self, *fids, wait=4.0):
        for f in fids:
            if f not in self.F.installed and not self.dev.installed(Fixtures.pkg(f)):
                self.F.install(f)
            self.F.cmd(f, "publish")
            e = self.F.wait(f, lambda x: x.get("event") == "published", 20)
            need(e, f"夹具 {f} 没有记录 published")
        time.sleep(wait)

    def approve(self, pkg, key):
        """经界面批准一个频道：主页 → 管理频道 → 打开开关。"""
        st = self.L.state()
        if any(a.get("pkg") == pkg and a.get("channelKey") == key and a.get("state") == "approved" for a in st.get("approvals", [])):
            return
        self.home()
        need(self.L.seek("manage_channels", ("DOWN",), 25), "找不到行末的「管理频道」入口（manage_channels）")
        self.L.press("OK")
        st = self.L.settle(3)
        need(st.get("page") == "channels", f"进入管理频道后 page={st.get('page')}")
        tag = f"channels:{pkg}:{key}"
        need(self.L.seek(tag, ("DOWN", "UP"), 30), f"管理频道页找不到 {tag}")
        self.L.press("OK")
        time.sleep(1)
        st = self.L.state()
        need(any(a.get("pkg") == pkg and a.get("channelKey") == key and a.get("state") == "approved" for a in st.get("approvals", [])),
             f"打开 {tag} 后状态接口未记为 approved")
        self.L.press("BACK")
        self.home()

    def row_items(self, st, rid):
        return next((r["items"] for r in st.get("rows", []) if r["id"] == rid), None)

    def seek_row(self, rid, max_steps=25):
        self.home()
        return self.L.seek(None, ("DOWN",), max_steps, match=lambda t: (self.L.state().get("focus") or {}).get("rowId") == rid)


def run_device(ctx: Ctx, only=None, regression=True):
    results = []
    for c in sorted(REG, key=lambda c: c["id"]):
        if not ms_le(c["ms"], ctx.ms):
            continue
        if c["until"] and not ms_le(ctx.ms, c["until"]):
            continue
        if only and c["id"] not in only:
            continue
        reason = c["when"](ctx) if c["when"] else None
        r = Result(c["id"], c["title"], SKIP, ctx.dev.name, reason or "")
        if reason is None:
            ctx.ev, ctx.data = [], {}
            t0 = time.time()
            try:
                out = c["fn"](ctx)
                status, msg = (out if isinstance(out, tuple) else (PASS, out or ""))
                r.status, r.message = status, msg
            except CheckFailed as e:
                r.status, r.message = FAIL, str(e)
            except Skip as e:
                r.status, r.message = SKIP, str(e)
            except Exception as e:
                r.status, r.message = FAIL, f"{type(e).__name__}: {e}"
                try:
                    _, _, _, ev = ctx.L.snap(f"{c['id']}-error", dump=False)
                    ctx.ev += ev
                except Exception:
                    pass
            r.evidence, r.data, r.seconds = list(ctx.ev), dict(ctx.data), round(time.time() - t0, 1)
            crashes = ctx.dev.crashes(PKG)
            if crashes:
                p = ctx.rep.path(ctx.dev.name, f"{c['id']}-crash.txt")
                p.write_text("\n\n".join(crashes), encoding="utf-8")
                r.status, r.message = FAIL, ("桌面崩溃或 ANR；" + r.message).strip("；")
                r.evidence.append(ctx.rep.rel(p))
                ctx.dev.logcat_clear()
                ctx.L.start()
        ctx.rep.add(r)
        results.append(r)
    if ctx.ms == "M0":
        return results
    # 文案：汇总本设备全部无障碍树
    v = copy_violations(ctx.L.dumps)
    r = Result("D199", "界面文案：只有白名单内的产品文字，没有提示性文字", FAIL if v else PASS, ctx.dev.name,
               "；".join(v[:30]) + (f" …共 {len(v)} 条" if len(v) > 30 else ""))
    ctx.rep.add(r)
    results.append(r)
    return results


# ===================================================================== M1 骨架
@check("D101", "冷启动无崩溃，桌面在前台", "M1")
def d101(ctx):
    rc = ctx.dev.sh(f"am start -W -n {ctx.L.comp}", timeout=60)
    m = re.search(r"TotalTime: (\d+)", rc)
    time.sleep(3)
    need(ctx.L.alive(), f"前台是 {ctx.dev.resumed()}")
    ctx.L.state()
    ctx.data["totalTimeMs"] = int(m.group(1)) if m else None
    return PASS, f"am start TotalTime {ctx.data['totalTimeMs']} ms"


@check("D102", "HOME 键回到桌面，焦点在主按钮或第一项", "M1", when=api(24))
def d102(ctx):
    ctx.F.install("dolphin")
    ctx.dev.sh(f"am start -W -n {Fixtures.pkg('dolphin')}/{ACT}")
    time.sleep(1.5)
    need(ctx.dev.resumed_pkg() == Fixtures.pkg("dolphin"), "夹具应用没有启动")
    ctx.dev.key("HOME", wait=1.5)
    st = ctx.L.settle(3)
    need(ctx.L.alive(), f"按 HOME 后前台是 {ctx.dev.resumed()}（默认桌面未生效？）")
    tag = ctx.L.focus(st)
    first = (st.get("rows") or [{}])[0]
    ok = tag == "hero:play" if (st.get("hero") or {}).get("visible") else (first.get("items") and tag == first["items"][0])
    need(ok, f"HOME 后焦点在 {tag}")
    ctx.snap("home-key")


@check("D103", "没有内容来源时：无 Hero、无继续观看和片单，应用行为第一行，焦点在第一个应用", "M1")
def d103(ctx):
    ctx.F.cleanup()
    ctx.F.install(*PROTO_APPS)
    ctx.L.stop()
    st = ctx.L.start()
    st = ctx.home()
    img, tree, st = ctx.snap("empty-home")
    rows = [r["id"] for r in st.get("rows", [])]
    need(not (st.get("hero") or {}).get("visible") and tree.find("hero") is None, "没有内容时仍显示 Hero")
    need("continue" not in rows and "watchlist" not in rows, f"出现了空的内容行：{rows}")
    need(rows and rows[0] == "apps", f"第一行是 {rows[:1]}，应为 apps")
    need(ctx.L.focus(st).startswith("app:"), f"焦点在 {ctx.L.focus(st)}")


@check("D104", "没有头像、账号、个人资料入口", "M1")
def d104(ctx):
    ctx.home()
    img, tree, st = ctx.snap("nav")
    bad = [n.rid for n in tree.nodes if re.search(r"avatar|account|profile|user", n.rid or "", re.I)]
    bad += [t for n in tree.nodes for t in (n.text, n.desc) if t and re.search(r"账号|帐号|个人资料|头像|登录|切换用户", t)]
    need(not bad, f"发现：{bad[:5]}")
    for t in ("nav:home", "nav:apps", "nav:widgets", "nav:search", "nav:settings"):
        need(tree.find(t), f"顶栏缺少 {t}")


@check("D105", "应用行最多 10 个；应用页列出全部夹具应用", "M1")
def d105(ctx):
    ctx.F.install(*PROTO_APPS)
    st = ctx.home()
    items = ctx.row_items(st, "apps") or []
    need(0 < len(items) <= 10, f"应用行有 {len(items)} 项")
    need(ctx.L.seek("nav:home", ("UP",), 8), "按上键到不了顶栏")
    need(ctx.L.seek("nav:apps", ("RIGHT",), 4), "顶栏找不到 nav:apps")
    time.sleep(1.0)
    st = ctx.L.settle()
    need(st.get("page") == "apps", f"焦点停在「应用」1 秒后 page={st.get('page')}")
    ctx.L.press("DOWN")
    seen, d = set(), "RIGHT"
    for _ in range(12):                      # 蛇形走遍网格
        for _ in range(12):
            seen.add(ctx.L.focus())
            before = ctx.L.focus()
            ctx.L.press(d)
            if ctx.L.focus() == before:
                break
        before = ctx.L.focus()
        ctx.L.press("DOWN")
        if ctx.L.focus() == before or ctx.L.focus() in seen:
            seen.add(ctx.L.focus())
            break
        d = "LEFT" if d == "RIGHT" else "RIGHT"
    want = {ctx.app_tag(f) for f in PROTO_APPS}
    miss = want - seen
    ctx.snap("apps-page")
    need(not miss, f"应用页走遍后没有遇到：{sorted(miss)}")


@check("D106", "继续观看：按最近互动时间排序；Hero 有内容", "M1", when=tvprovider)
def d106(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse")
    st = ctx.home()
    items = ctx.row_items(st, "continue")
    want = [f"wn:{Fixtures.pkg('dolphin')}:dolphin.volcano", f"wn:{Fixtures.pkg('cloud')}:cloud.glacier",
            f"wn:{Fixtures.pkg('spark')}:spark.sakura", f"wn:{Fixtures.pkg('dolphin')}:dolphin.starry",
            f"wn:{Fixtures.pkg('pulse')}:pulse.rain"]
    need(items is not None, "没有继续观看行")
    got = [k for k in items if k in want]
    need(got == want, f"顺序 {got}")
    need((st.get("hero") or {}).get("visible"), "有内容时 Hero 未显示")
    ctx.snap("hero")


@check("D107", "浏览态布局基线：焦点行顶部 y=236dp，行内焦点 x=48dp", "M1", when=tvprovider)
def d107(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    need(ctx.seek_row("continue"), "到不了继续观看行")
    msgs = []
    for i in range(3):
        img, tree, st = ctx.snap(f"browse-{i}")
        n = tree.find(ctx.L.focus(st))
        row = tree.find("row:continue")
        need(n and row, "无障碍树缺少焦点卡片或 row:continue")
        x1, y1, x2, y2 = n.bounds
        w = ctx.dev.dp(x2 - x1)
        left, top = ctx.dev.dp(x1), ctx.dev.dp(row.bounds[1])
        if not (48 - 0.06 * w - 4 <= left <= 48 + 4) and i > 0:
            msgs.append(f"第 {i} 项左边 {left:.0f}dp")
        if not (230 <= top <= 242):
            msgs.append(f"行顶部 {top:.0f}dp")
        ctx.L.press("RIGHT")
    need(not msgs, "；".join(msgs))


@check("D108", "焦点记忆：离开一行再回来，回到原来那一项", "M1", when=tvprovider)
def d108(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    need(ctx.seek_row("continue"), "到不了继续观看行")
    for _ in range(3):
        ctx.L.press("RIGHT")
    want = ctx.L.focus()
    ctx.L.press("DOWN")
    ctx.L.press("UP")
    need(ctx.L.focus() == want, f"回到 {ctx.L.focus()}，应为 {want}")


@check("D109", "BACK 链：行 → Hero → 顶栏，之后不再变化且桌面不退出", "M1")
def d109(ctx):
    st = ctx.home()
    first = st["rows"][0]["id"]
    need(ctx.seek_row(first, 4), "到不了第一行")
    ctx.L.press("RIGHT")
    seq = []
    for _ in range(7):
        st = ctx.L.press("BACK")
        seq.append(ctx.L.focus(st))
        need(ctx.L.alive(), f"按 BACK 后桌面不在前台：{ctx.dev.resumed()}")
    need(seq[-1].startswith("nav:") and len(set(seq[-3:])) == 1, f"BACK 序列 {seq}")
    if (st.get("hero") or {}).get("visible"):
        need("hero:play" in seq, f"BACK 没有经过 Hero：{seq}")


@check("D110", "顶栏切换页面：主页 / 应用 / 小组件，BACK 回主页", "M1")
def d110(ctx):
    ctx.home()
    need(ctx.L.seek("nav:home", ("UP",), 8), "到不了顶栏")
    for tab, page in (("nav:apps", "apps"), ("nav:widgets", "widgets")):
        need(ctx.L.seek(tab, ("RIGHT",), 4), f"找不到 {tab}")
        time.sleep(1.0)
        st = ctx.L.settle()
        need(st.get("page") == page, f"{tab} 停留后 page={st.get('page')}")
        ctx.L.press("DOWN")
        need(not ctx.L.focus().startswith("nav:"), f"{page} 页按下键焦点仍在顶栏")
        ctx.L.press("UP")
        ctx.snap(f"page-{page}")
    for _ in range(3):
        st = ctx.L.press("BACK")
    need(st.get("page") == "home", f"BACK 后 page={st.get('page')}")


@check("D111", "打开应用后返回，焦点留在原卡片", "M1")
def d111(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    tag = ctx.app_tag("dolphin")
    need(ctx.seek_row("apps") and ctx.L.seek(tag, ("RIGHT", "LEFT"), 12), f"应用行找不到 {tag}")
    ctx.F.cmd("dolphin", "clear_log")
    ctx.L.press("OK")
    time.sleep(2)
    need(ctx.dev.resumed_pkg() == Fixtures.pkg("dolphin"), f"OK 后前台是 {ctx.dev.resumed()}")
    need(ctx.F.wait("dolphin", lambda e: e.get("event") == "launched", 5), "夹具没有记录 launched")
    ctx.dev.key("BACK", wait=1.5)
    st = ctx.L.settle()
    need(ctx.L.alive() and ctx.L.focus(st) == tag, f"返回后焦点 {ctx.L.focus(st)}")


@check("D112", "打开继续观看节目：拉起来源应用并带上节目参数", "M1", when=tvprovider)
def d112(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    need(ctx.seek_row("continue"), "到不了继续观看行")
    tag = f"wn:{Fixtures.pkg('dolphin')}:dolphin.volcano"
    need(ctx.L.seek(tag, ("LEFT", "RIGHT"), 8), f"找不到 {tag}")
    ctx.F.cmd("dolphin", "clear_log")
    ctx.L.press("OK")
    e = ctx.F.wait("dolphin", lambda e: e.get("event") == "launched", 8)
    need(e and "dolphin.volcano" in json.dumps(e, ensure_ascii=False), f"夹具记录：{e}")
    ctx.dev.key("BACK", wait=1.5)


@check("D113", "长按菜单隐藏应用；在桌面设置里恢复", "M1")
def d113(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    tag = ctx.app_tag("files")
    need(ctx.seek_row("apps") and ctx.L.seek(tag, ("RIGHT", "LEFT"), 12), f"应用行找不到 {tag}")
    ctx.L.press("OK", long=True)
    img, tree, st = ctx.snap("menu")
    need(st.get("overlay") == "menu" and tree.find("menu:hide"), "长按 OK 没有出现带「隐藏」的菜单")
    need(ctx.L.seek("menu:hide", ("DOWN", "UP"), 8), "菜单里到不了 menu:hide")
    ctx.L.press("OK")
    st = ctx.L.settle()
    need(tag in st.get("hiddenApps", []) and tag not in (ctx.row_items(st, "apps") or []), "隐藏后仍在应用行或未记入 hiddenApps")
    ctx.home()
    need(ctx.L.seek("nav:home", ("UP",), 8) and ctx.L.seek("nav:settings", ("RIGHT",), 8), "顶栏找不到 nav:settings")
    ctx.L.press("OK")
    need(ctx.L.seek("qs:launcher_settings", ("DOWN",), 15), "快速设置里找不到「桌面设置」")
    ctx.L.press("OK")
    need(ctx.L.seek("settings:hidden_apps", ("DOWN",), 15), "桌面设置里找不到「已隐藏的应用」")
    ctx.L.press("OK")
    need(ctx.L.seek(f"hidden:{tag}", ("DOWN",), 15), f"找不到恢复项 hidden:{tag}")
    ctx.snap("hidden-apps")
    ctx.L.press("OK")
    st = ctx.L.settle()
    need(tag not in st.get("hiddenApps", []), "恢复后仍在 hiddenApps")
    st = ctx.home()
    need(tag in (ctx.row_items(st, "apps") or []) or len(ctx.row_items(st, "apps") or []) >= 10, "恢复后没有回到应用行")


@check("D114", "进程被系统回收后恢复页面与焦点", "M1")
def d114(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    need(ctx.L.seek("nav:home", ("UP",), 8) and ctx.L.seek("nav:apps", ("RIGHT",), 4), "到不了 nav:apps")
    time.sleep(1)
    ctx.L.press("DOWN")
    ctx.L.press("RIGHT")
    ctx.L.press("RIGHT")
    want = ctx.L.focus()
    ctx.dev.sh(f"am start -W -n {Fixtures.pkg('store')}/{ACT}")
    time.sleep(1.5)
    ctx.dev.sh(f"am kill {PKG}")
    time.sleep(1.5)
    need(not ctx.dev.pid(PKG), "am kill 后桌面进程仍在（无法模拟回收）")
    ctx.dev.key("BACK", wait=2.5)
    st = ctx.L.settle(4)
    need(st.get("page") == "apps" and ctx.L.focus(st) == want, f"恢复后 page={st.get('page')} 焦点 {ctx.L.focus(st)}，应为 apps / {want}")


@check("D115", "随机遍历 120 步：焦点始终存在、不离开桌面、状态与界面一致", "M1")
def d115(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    rnd = random.Random(20261102)
    msgs = []
    w, h = ctx.dev.size
    for i in range(120):
        k = rnd.choices(["UP", "DOWN", "LEFT", "RIGHT", "BACK"], [3, 3, 4, 4, 1])[0]
        st = ctx.L.press(k)
        if not ctx.L.alive():
            raise CheckFailed(f"第 {i} 步 {k} 后离开了桌面：{ctx.dev.resumed()}")
        if not ctx.L.focus(st):
            msgs.append(f"第 {i} 步 {k} 后没有焦点")
        if i % 15 == 0:
            img, tree, st = ctx.snap(f"walk-{i}")
            n = tree.find(ctx.L.focus(st))
            if n:
                x1, y1, x2, y2 = n.bounds
                m = ctx.dev.px(16)
                if x1 < m or y1 < m or x2 > w - m or y2 > h - m:
                    msgs.append(f"第 {i} 步焦点 {n.rid} 越出安全区 {n.bounds}")
    need(not msgs, "；".join(msgs[:10]))


@check("D116", "M1 材质为 T3（纯色）", "M1", until="M1")
def d116(ctx):
    st = ctx.L.state()
    need((st.get("glass") or {}).get("tier") == "T3", f"glass.tier={st.get('glass')}")


@check("D117", "静止 3 秒后界面空闲（uiautomator 可读取）", "M1")
def d117(ctx):
    ctx.home()
    time.sleep(3)
    t0 = time.time()
    ctx.dev.dump(tries=1)
    return PASS, f"{time.time() - t0:.1f}s"


# ===================================================================== M2 视觉
ICON_SEEDS = {}


def _icon_seed(fid):
    if not ICON_SEEDS:
        from .base import KIT
        idx = json.loads((KIT / "fixtures" / "art" / "index.json").read_text(encoding="utf-8"))
        for k, v in idx.items():
            if k.startswith("ico:"):
                ICON_SEEDS[k[4:]] = v["seed"]
    return ICON_SEEDS[fid]


@check("D201", "取色：焦点停在应用上，全局主题色相跟随图标", "M2")
def d201(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.L.pref("color_source", "content")
    ctx.L.pref("scheme_style", "tonal")
    ctx.home()
    need(ctx.seek_row("apps"), "到不了应用行")
    msgs, themes = [], []
    for fid in ("dolphin", "spark", "pulse", "prism", "cloud", "sky"):
        tag = ctx.app_tag(fid)
        if not ctx.L.seek(tag, ("RIGHT", "LEFT"), 12):
            msgs.append(f"找不到 {tag}")
            continue
        time.sleep(1.4)
        st = ctx.L.settle()
        th = st.get("theme") or {}
        exp = _icon_seed(fid)
        _, c, hh = lch(th.get("seed", "#000000"))
        _, ce, he = lch(exp)
        themes.append({"app": fid, **th})
        if hue_diff(hh, he) > 25 or c < 12:
            msgs.append(f"{fid}：seed {th.get('seed')}（H{hh:.0f} C{c:.0f}），图标约 {exp}（H{he:.0f}）")
        p = (th.get("roles") or {}).get("primary")
        if p and hue_diff(lch(p)[2], hh) > 30:
            msgs.append(f"{fid}：primary {p} 色相偏离种子")
    ctx.flags["themes"] = themes
    ctx.data["themes"] = themes
    ctx.snap("theme-last")
    need(not msgs, "；".join(msgs))


@check("D202", "低彩度来源（灰色图标）不改变全局主题", "M2")
def d202(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    need(ctx.seek_row("apps"), "到不了应用行")
    need(ctx.L.seek(ctx.app_tag("prism"), ("RIGHT", "LEFT"), 12), "找不到 prism")
    time.sleep(1.4)
    before = ctx.L.settle()["theme"]["seed"]
    need(ctx.L.seek(ctx.app_tag("files"), ("RIGHT", "LEFT"), 12), "找不到 files")
    time.sleep(1.4)
    after = ctx.L.settle()["theme"]["seed"]
    need(delta_e(before, after) < 3, f"焦点从棱镜直播移到文件管理，种子 {before} → {after}")


@check("D203", "对比度：onPrimary/primary ≥ 4.5，onSurface/surface ≥ 7，焦点环/surface ≥ 3（三个对比度级别）", "M2")
def d203(ctx):
    ctx.F.install(*PROTO_APPS)
    msgs = []
    for lvl in ("0", "0.5", "1"):
        ctx.L.pref("contrast", lvl)
        ctx.home()
        ctx.seek_row("apps")
        for fid in ("dolphin", "spark", "prism", "sky"):
            if not ctx.L.seek(ctx.app_tag(fid), ("RIGHT", "LEFT"), 12):
                continue
            time.sleep(1.4)
            r = (ctx.L.settle().get("theme") or {}).get("roles") or {}
            for a, b, m in (("onPrimary", "primary", 4.5), ("onSurface", "surface", 7), ("focusRing", "surface", 3)):
                if a in r and b in r:
                    v = contrast(r[a], r[b])
                    if v < m:
                        msgs.append(f"对比度 {lvl} / {fid}：{a}/{b} = {v:.2f}")
                else:
                    msgs.append(f"roles 缺少 {a} 或 {b}")
    ctx.L.pref("contrast", "0")
    need(not msgs, "；".join(sorted(set(msgs))[:12]))


@check("D204", "主题真实上屏：主按钮底色等于状态接口的 primary", "M2", when=tvprovider)
def d204(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    ctx.home()
    need(ctx.L.seek("hero:watchlist", ("RIGHT",), 3), "Hero 里找不到 hero:watchlist")
    time.sleep(1.5)
    img, tree, st = ctx.snap("hero-primary")
    n = tree.find("hero:play")
    need(n, "无障碍树没有 hero:play")
    x1, y1, x2, y2 = n.bounds
    c = mode_color(img, (x1 + 4, y1 + 4, x2 - 4, y2 - 4))
    p = st["theme"]["roles"]["primary"]
    d = delta_e(c, p)
    ctx.data.update(screen=c, primary=p, deltaE=d)
    need(d < 10, f"屏幕上主按钮底色 {c} 与 primary {p} 相差 ΔE {d:.1f}")


@check("D205", "连续移动时不频繁切换主题（节流）", "M2")
def d205(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    need(ctx.seek_row("apps"), "到不了应用行")
    for _ in range(9):
        ctx.L.press("LEFT", settle=False)
    time.sleep(1.5)
    ctx.L.call("reset_counters")
    for _ in range(7):
        ctx.dev.key("RIGHT", wait=0.08)
    time.sleep(2.0)
    st = ctx.L.settle()
    n = (st.get("counters") or {}).get("themeTransitions", 99)
    need(n <= 2, f"连按 7 次右键期间主题过渡 {n} 次")


@check("D206", "主题过渡期间内容行不重组", "M2")
def d206(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    ctx.seek_row("apps")
    ctx.L.call("reset_counters")
    for _ in range(4):
        ctx.L.press("RIGHT")
        time.sleep(1.2)
    c = ctx.L.state().get("counters") or {}
    need(c.get("themeTransitions", 0) >= 1, "没有发生主题过渡，无法判断")
    need(c.get("rowRecompositionsDuringThemeTransition", 1) == 0, f"counters={c}")


def _capsule_box(ctx, tree):
    ns = [tree.find(t) for t in ("nav:home", "nav:apps", "nav:widgets")]
    ns = [n for n in ns if n]
    need(ns, "无障碍树没有顶栏 Tab")
    x1 = min(n.bounds[0] for n in ns)
    x2 = max(n.bounds[2] for n in ns)
    y1 = min(n.bounds[1] for n in ns)
    y2 = max(n.bounds[3] for n in ns)
    return x1, y1, x2, y2


def _glass_probe(ctx, tier):
    ctx.L.pref("glass_tier", tier)
    ctx.L.pref("color_source", "fixed")
    ctx.home()
    need(ctx.L.seek("nav:search", ("UP", "RIGHT"), 12) or ctx.L.seek("nav:home", ("UP",), 8), "到不了顶栏")
    ctx.L.call("set_backdrop", "checker:8:#FFFFFF:#000000")
    time.sleep(2)
    img, tree, st = ctx.snap(f"glass-{tier}-checker")
    box = _capsule_box(ctx, tree)
    w, h = ctx.dev.size
    ref = (int(w * 0.70), int(ctx.dev.px(90)), int(w * 0.70) + (box[2] - box[0]) // 2, int(ctx.dev.px(90)) + (box[3] - box[1]))
    hf_glass, hf_ref = hf_energy(img, box), hf_energy(img, ref)
    cols = []
    for c in ("#E00000", "#0030E0"):
        ctx.L.call("set_backdrop", f"solid:{c}")
        time.sleep(2)
        im, _, _ = ctx.snap(f"glass-{tier}-{c[1:]}", dump=False)
        cols.append(mean_color(im, box))
    ctx.L.call("set_backdrop", "none")
    ctx.L.pref("color_source", "content")
    ctx.L.pref("glass_tier", "auto")
    track = delta_e(*cols)
    ctx.data.update(tier=st.get("glass"), hfGlass=hf_glass, hfRef=hf_ref, capsuleRed=cols[0], capsuleBlue=cols[1], track=track)
    return st, hf_glass, hf_ref, track


@check("D207", "T1 实时模糊：顶栏玻璃把背后的棋盘格模糊掉，颜色随背景变化", "M2", when=t1_capable)
def d207(ctx):
    st, hg, hr, track = _glass_probe(ctx, "T1")
    need((st.get("glass") or {}).get("tier") == "T1", f"设置 T1 后 glass={st.get('glass')}")
    need(hr > 20, f"参考区域高频能量 {hr:.1f}，背景图案没有生效")
    need(hg < 0.25 * hr, f"玻璃区高频能量 {hg:.1f}，参考 {hr:.1f}（没有模糊）")
    need(track > 15, f"红/蓝背景下顶栏颜色差 ΔE {track:.1f}（玻璃没有透出背景）")


@check("D208", "T2 冻结模糊：同上（API 23–30 的默认档）", "M2")
def d208(ctx):
    st, hg, hr, track = _glass_probe(ctx, "T2")
    need((st.get("glass") or {}).get("tier") == "T2", f"设置 T2 后 glass={st.get('glass')}")
    need(hr > 20, f"参考区域高频能量 {hr:.1f}，背景图案没有生效")
    need(hg < 0.25 * hr, f"玻璃区高频能量 {hg:.1f}，参考 {hr:.1f}")
    need(track > 15, f"红/蓝背景下顶栏颜色差 ΔE {track:.1f}")


@check("D209", "T3 纯色：不透出背景", "M2")
def d209(ctx):
    st, hg, hr, track = _glass_probe(ctx, "T3")
    need((st.get("glass") or {}).get("tier") == "T3", f"glass={st.get('glass')}")
    need(track < 6, f"T3 下顶栏颜色随背景变化 ΔE {track:.1f}")


@check("D210", "自动档位：API 31 以下不会用 T1；模糊区域不超过 3 块", "M2")
def d210(ctx):
    ctx.L.pref("glass_tier", "auto")
    ctx.L.stop()
    st = ctx.L.start()
    g = st.get("glass") or {}
    if ctx.dev.api < 31:
        need(g.get("tier") in ("T2", "T3"), f"API {ctx.dev.api} 自动档为 {g}")
    need(g.get("blurRegions", 0) <= 3, f"blurRegions={g.get('blurRegions')}")
    return PASS, json.dumps(g)


@check("D211", "背景随焦点内容切换", "M2", when=tvprovider)
def d211(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    need(ctx.seek_row("continue"), "到不了继续观看行")
    w, h = ctx.dev.size
    box = (int(w * 0.62), int(h * 0.08), int(w * 0.95), int(h * 0.30))
    cols = {}
    for key in ("dolphin.volcano", "cloud.glacier"):
        pkg = Fixtures.pkg(key.split(".")[0])
        need(ctx.L.seek(f"wn:{pkg}:{key}", ("RIGHT", "LEFT"), 8), f"找不到 {key}")
        time.sleep(1.8)
        img, _, _ = ctx.snap(f"backdrop-{key}", dump=False)
        cols[key] = mean_color(img, box)
    d = delta_e(cols["dolphin.volcano"], cols["cloud.glacier"])
    ctx.data.update(cols)
    need(d > 12, f"火山岛纪行与冰川来信的背景区域颜色只差 ΔE {d:.1f}")


@check("D212", "快速设置：取色来源 / 玻璃效果 / 配色风格可用方向键切换并生效", "M2")
def d212(ctx):
    ctx.home()
    need(ctx.L.seek("nav:home", ("UP",), 8) and ctx.L.seek("nav:settings", ("RIGHT",), 8), "找不到 nav:settings")
    ctx.L.press("OK")
    img, tree, st = ctx.snap("quick-settings")
    need(st.get("overlay") == "quick_settings", f"overlay={st.get('overlay')}")
    for t in ("qs:source", "qs:glass", "qs:style", "qs:launcher_settings"):
        need(tree.find(t), f"快速设置缺少 {t}")
    need(ctx.L.seek("qs:glass", ("DOWN",), 12), "到不了 qs:glass")
    tiers = []
    for _ in range(3):
        ctx.L.press("RIGHT")
        time.sleep(0.8)
        tiers.append((ctx.L.state().get("glass") or {}).get("tier"))
    need(len(set(tiers)) >= 2, f"左右切换玻璃效果，tier 依次为 {tiers}")
    ctx.L.press("BACK")
    ctx.L.pref("glass_tier", "auto")


@check("D213", "帧时间（模拟器参考值，不阻塞）", "M2")
def d213(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    ctx.seek_row("apps")
    ctx.dev.sh(f"dumpsys gfxinfo {PKG} reset")
    for i in range(24):
        ctx.dev.key("RIGHT" if (i // 6) % 2 == 0 else "LEFT", wait=0.12)
    time.sleep(1)
    out = ctx.dev.sh(f"dumpsys gfxinfo {PKG}")
    p = ctx.rep.path(ctx.dev.name, "gfxinfo.txt")
    p.write_text(out, encoding="utf-8")
    ctx.ev.append(ctx.rep.rel(p))
    m95 = re.search(r"95th percentile: (\d+)ms", out)
    jank = re.search(r"Janky frames: \d+ \(([\d.]+)%\)", out)
    v = int(m95.group(1)) if m95 else None
    ctx.data.update(p95=v, jank=jank.group(1) if jank else None)
    return (WARN if v is None or v > 32 else PASS), f"P95 {v} ms，卡顿帧 {ctx.data['jank']}%（真机预算见 §17）"


@check("D214", "人工对照：焦点形变、玻璃、配色与原型一致", "M2")
def d214(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2) if ctx.dev.api >= 26 else ctx.F.install(*PROTO_APPS)
    ctx.home()
    ctx.snap("review-hero", dump=False)
    ctx.seek_row("apps")
    ctx.L.press("RIGHT")
    time.sleep(1.4)
    ctx.snap("review-app-focus", dump=False)
    if ctx.dev.api >= 26 and ctx.seek_row("continue"):
        ctx.L.press("RIGHT")
        time.sleep(1.4)
        ctx.snap("review-browse", dump=False)
    return MANUAL, "在浏览器中打开 tv-launcher-design-v3.html 的原型，与同名场景并排比较：Hero、应用图标 Cookie 形焦点、浏览态信息头、玻璃顶栏"
