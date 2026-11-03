"""M4 环境模式与系统集成；M5 首次启动、性能、健壮性。"""
from __future__ import annotations

import re
import statistics
import time
import urllib.error
import urllib.request

from .base import PASS, SKIP, WARN, CheckFailed, Skip
from .checks import PROTO_APPS, api, check, need, tvprovider
from .launcher import PKG, PROJ, Fixtures

P = Fixtures.pkg


# ===================================================================== M4
def enter_ambient(ctx, idle=12):
    ctx.L.pref("ambient_idle_s", str(idle))
    ctx.home()
    time.sleep(idle + 6)
    st = ctx.L.state()
    return st


def leave_ambient(ctx):
    ctx.L.pref("ambient_idle_s", "0")


@check("D401", "环境模式：空闲后进入，任意键退出且焦点回到原处", "M4")
def d401(ctx):
    ctx.F.install(*PROTO_APPS)
    ctx.home()
    need(ctx.seek_row("apps"), "到不了应用行")
    ctx.L.press("RIGHT")
    ctx.L.press("RIGHT")
    want = ctx.L.focus()
    try:
        ctx.L.pref("ambient_idle_s", "12")
        time.sleep(18)
        img, _, st, ev = ctx.L.snap("ambient", dump=False)
        ctx.ev += ev
        need(st.get("mode") == "ambient", f"空闲 18 秒后 mode={st.get('mode')}")
        tree = ctx.dev.dump()
        need(tree.find("ambient:clock"), "环境模式没有时钟（ambient:clock）")
        ctx.dev.key("DOWN", wait=1.5)
        st = ctx.L.settle()
        need(st.get("mode") != "ambient", "按键后没有退出环境模式")
        need(ctx.L.focus(st) == want, f"退出后焦点 {ctx.L.focus(st)}，应为 {want}")
    finally:
        leave_ambient(ctx)


@check("D402", "环境模式视频用 SurfaceView，不用 TextureView", "M4")
def d402(ctx):
    try:
        st = enter_ambient(ctx)
        need(st.get("mode") == "ambient", f"mode={st.get('mode')}")
        tree = ctx.dev.dump()
        cls = {n.cls for n in tree.nodes if n.pkg == PKG}
        need("android.view.TextureView" not in cls, "环境模式里出现 TextureView")
        need("android.view.SurfaceView" in cls or "android.widget.ImageView" in cls or any("Surface" in c for c in cls),
             f"没有 SurfaceView（无网络时允许静态图片回退）：{sorted(cls)[:8]}")
        ctx.ev.append(ctx.rep.rel(ctx.rep.path(ctx.dev.name, "ambient-tree.xml")))
        ctx.rep.path(ctx.dev.name, "ambient-tree.xml").write_text(tree.xml, encoding="utf-8")
    finally:
        ctx.dev.key("BACK", wait=1)
        leave_ambient(ctx)


@check("D403", "环境模式不阻塞系统：保持 3 分钟无崩溃、时钟位置会漂移（防烧屏）", "M4")
def d403(ctx):
    try:
        st = enter_ambient(ctx)
        need(st.get("mode") == "ambient", f"mode={st.get('mode')}")
        pos = []
        for i in range(4):
            tree = ctx.dev.dump()
            n = tree.find("ambient:clock")
            pos.append(n.bounds if n else None)
            time.sleep(45)
        need(ctx.L.alive() or ctx.dev.resumed_pkg() in ("com.android.systemui", PKG), f"前台 {ctx.dev.resumed()}")
        moved = len({p for p in pos if p}) > 1
        ctx.data["clockBounds"] = pos
        return (PASS if moved else WARN), ("时钟位置有漂移" if moved else "3 分钟内时钟位置未变化（真机需确认防烧屏位移）")
    finally:
        ctx.dev.key("BACK", wait=1)
        leave_ambient(ctx)


def open_qs(ctx):
    ctx.home()
    need(ctx.L.seek("nav:home", ("UP",), 8) and ctx.L.seek("nav:settings", ("RIGHT",), 8), "找不到 nav:settings")
    ctx.L.press("OK")
    st = ctx.L.settle()
    need(st.get("overlay") == "quick_settings", f"overlay={st.get('overlay')}")


def music_volume(ctx):
    for cmd in ("cmd media_session volume --stream 3 --get", "media volume --stream 3 --get"):
        out = ctx.dev.sh(cmd)
        m = re.search(r"volume is (\d+)", out)
        if m:
            return int(m.group(1))
    m = re.search(r"STREAM_MUSIC:.*?Current: (?:\d+ \(\w+\): )?(\d+)", ctx.dev.sh("dumpsys audio"), re.S)
    return int(m.group(1)) if m else None


@check("D404", "快速设置：Wi-Fi 打开系统网络设置；音量左右调节生效", "M4")
def d404(ctx):
    open_qs(ctx)
    need(ctx.L.seek("qs:wifi", ("DOWN", "UP"), 12), "到不了 qs:wifi")
    ctx.L.press("OK")
    time.sleep(2.5)
    fg = ctx.dev.resumed_pkg()
    need(fg in ("com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings") or "settings" in fg, f"Wi-Fi 打开的是 {ctx.dev.resumed()}")
    ctx.dev.key("BACK", wait=1.5)
    if not ctx.L.alive():
        ctx.home()
    open_qs(ctx)
    need(ctx.L.seek("qs:volume", ("DOWN", "UP"), 12), "到不了 qs:volume")
    v0 = music_volume(ctx)
    ctx.L.press("LEFT")
    ctx.L.press("LEFT")
    v1 = music_volume(ctx)
    ctx.L.press("RIGHT")
    v2 = music_volume(ctx)
    ctx.data.update(v0=v0, v1=v1, v2=v2)
    if v0 is None:
        raise Skip("读取不到系统音量")
    need(v1 is not None and v2 is not None and v1 < v0 or v2 > v1, f"音量 {v0} → 左×2 {v1} → 右 {v2}")
    ctx.L.press("BACK")


@check("D405", "信号源：没有输入源的设备上不崩溃，不显示空行", "M4")
def d405(ctx):
    open_qs(ctx)
    need(ctx.L.seek("qs:source", ("DOWN", "UP"), 12), "到不了 qs:source")
    ctx.L.press("OK")
    time.sleep(2)
    img, tree, st = ctx.snap("source")
    need(ctx.L.alive() or "tv" in ctx.dev.resumed_pkg(), f"前台 {ctx.dev.resumed()}")
    ctx.dev.key("BACK", wait=1.2)
    st = ctx.home()
    need(not any(r["id"] == "inputs" and not r["items"] for r in st.get("rows", [])), "出现了空的信号源行")


# D406（手机与电脑，会话协议）在 checks33.py


# ===================================================================== M5
def reprepare(ctx):
    ctx.dev.set_home(ctx.L.comp)
    for p in ("android.permission.READ_TV_LISTINGS", "com.android.providers.tv.permission.READ_EPG_DATA"):
        ctx.dev.sh(f"pm grant {PKG} {p}")
    ctx.L.start()
    ctx.L.pref("onboarding_done", "true")
    ctx.L.pref("ambient_idle_s", "0")


@check("D501", "首次启动引导：清空数据后出现，完成后进入主页且不再出现", "M5")
def d501(ctx):
    try:
        ctx.L.clear()
        ctx.dev.set_home(ctx.L.comp)
        ctx.L.start()
        time.sleep(2)
        for _ in range(3):                                  # 系统权限对话框：选允许
            fg = ctx.dev.resumed_pkg()
            if fg in ("com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller"):
                tree = ctx.dev.dump()
                b = next((n for n in tree.nodes if re.search(r"allow_button|permission_allow", n.rid)), None)
                if b:
                    x1, y1, x2, y2 = b.bounds
                    ctx.dev.sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                time.sleep(1.5)
        img, tree, st = ctx.snap("onboarding")
        need(st.get("page") == "onboarding", f"清空数据后 page={st.get('page')}")
        need(ctx.L.seek("onboarding:done", ("DOWN", "RIGHT", "UP", "LEFT"), 30), "到不了 onboarding:done")
        ctx.L.press("OK")
        st = ctx.L.settle(4)
        need(st.get("page") == "home", f"完成引导后 page={st.get('page')}")
        ctx.L.stop()
        st = ctx.L.start()
        need(st.get("page") != "onboarding", "重启后又出现引导")
    finally:
        reprepare(ctx)


@check("D502", "内存：浏览一轮后 PSS ≤ 220MB（超过 180MB 警告）", "M5")
def d502(ctx):
    ctx.F.install(*PROTO_APPS)
    if ctx.dev.api >= 26:
        ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    ctx.home()
    for d in ("DOWN",) * 4 + ("RIGHT",) * 8 + ("UP",) * 4:
        ctx.L.press(d, settle=False)
    time.sleep(3)
    out = ctx.dev.sh(f"dumpsys meminfo {PKG}")
    p = ctx.rep.path(ctx.dev.name, "meminfo.txt")
    p.write_text(out, encoding="utf-8")
    ctx.ev.append(ctx.rep.rel(p))
    m = re.search(r"TOTAL(?: PSS)?:?\s+(\d+)", out)
    need(m, "读不到 TOTAL PSS")
    mb = int(m.group(1)) / 1024
    ctx.data["pssMB"] = round(mb, 1)
    need(mb <= 220, f"PSS {mb:.0f} MB")
    return (WARN if mb > 180 else PASS), f"PSS {mb:.0f} MB"


@check("D503", "冷启动时间（模拟器参考值，不阻塞）", "M5")
def d503(ctx):
    ts = []
    for _ in range(3):
        ctx.L.stop()
        time.sleep(1)
        out = ctx.dev.sh(f"am start -W -n {ctx.L.comp}", timeout=60)
        m = re.search(r"TotalTime: (\d+)", out)
        if m:
            ts.append(int(m.group(1)))
        time.sleep(3)
    need(ts, "am start 没有返回 TotalTime")
    med = statistics.median(ts)
    ctx.data["totalTimeMs"] = ts
    return (WARN if med > 2500 else PASS), f"中位数 {med:.0f} ms（{ts}）"


@check("D504", "来源应用卸载：相关行与卡片在 10 秒内消失，不崩溃", "M5", when=tvprovider)
def d504(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    st = ctx.home()
    pkg = P("dolphin")
    need(any(k.startswith(f"wn:{pkg}:") for k in ctx.row_items(st, "continue") or []), "继续观看里没有 dolphin 的节目")
    ctx.F.uninstall("dolphin")
    t0 = time.time()
    while time.time() - t0 < 10:
        st = ctx.L.state()
        left = [k for r in st.get("rows", []) for k in r["items"] if f":{pkg}" in k or k.startswith(f"app:{pkg}/")]
        if not left:
            break
        time.sleep(1)
    need(not left, f"卸载 10 秒后仍显示：{left[:4]}")
    need(ctx.L.alive(), "桌面不在前台")


def set_network(ctx, on: bool):
    if ctx.dev.api >= 28:
        ctx.dev.sh(f"cmd connectivity airplane-mode {'disable' if on else 'enable'}")
    ctx.dev.sh(f"svc wifi {'enable' if on else 'disable'}")
    ctx.dev.sh(f"svc data {'enable' if on else 'disable'}")


@check("D505", "断网：冷启动、浏览、环境模式、快速设置都不崩溃、不卡死", "M5")
def d505(ctx):
    ctx.F.install(*PROTO_APPS)
    try:
        set_network(ctx, False)
        time.sleep(3)
        ctx.L.stop()
        st = ctx.L.start()
        need(ctx.L.alive(), f"断网冷启动后前台 {ctx.dev.resumed()}")
        for d in ("DOWN",) * 3 + ("RIGHT",) * 5 + ("UP",) * 4:
            ctx.L.press(d)
        st = enter_ambient(ctx, idle=10)
        need(st.get("mode") == "ambient", f"断网时没有进入环境模式（mode={st.get('mode')}）")
        ctx.dev.key("DOWN", wait=1.5)
        leave_ambient(ctx)
        open_qs(ctx)
        ctx.L.press("BACK")
        ctx.dev.dump(tries=2)
        need(ctx.L.alive(), "桌面不在前台")
    finally:
        leave_ambient(ctx)
        set_network(ctx, True)


@check("D506", "release 构建冒烟：安装、启动、浏览无崩溃；Baseline Profile 可安装", "M5", when=api(24))
def d506(ctx):
    from .base import PROJECT
    rel = PROJECT / PROJ["gradle"]["releaseApk"]
    dbg = PROJECT / PROJ["gradle"]["debugApk"]
    need(rel.exists(), "没有 release APK")
    try:
        ok, msg = ctx.dev.install(rel)
        need(ok, f"release 覆盖安装失败：{msg}")
        ctx.dev.set_home(ctx.L.comp)
        ctx.dev.sh(f"am start -W -n {ctx.L.comp}", timeout=60)
        time.sleep(3)
        for k in ("DOWN", "RIGHT", "RIGHT", "DOWN", "UP", "UP", "UP"):
            ctx.dev.key(k, wait=0.5)
        need(ctx.dev.resumed_pkg() == PKG, f"release 启动后前台 {ctx.dev.resumed()}")
        need(not ctx.dev.crashes(PKG), "release 构建崩溃")
        out = ctx.dev.sh(f"am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE {PKG}/androidx.profileinstaller.ProfileInstallReceiver")
        m = re.search(r"result=(-?\d+)", out)
        code = int(m.group(1)) if m else None
        ctx.data["profileInstallResult"] = code
        if code == 3:
            return WARN, "ART 版本不支持写入 Baseline Profile（模拟器常见）"
        need(code in (1, 2), f"ProfileInstaller 返回 {code}（1=已安装 2=已存在；6=APK 中没有 baseline.prof）")
        if ctx.dev.api >= 28:
            out = ctx.dev.sh(f"cmd package compile -m speed-profile -f {PKG}", timeout=240)
            dx = ctx.dev.sh(f"dumpsys package dexopt {PKG}")
            m = re.search(r"\[status=([\w-]+)", dx)
            ctx.data["dexopt"] = m.group(1) if m else dx.strip()[-200:]
            if not (m and m.group(1) == "speed-profile"):
                return WARN, f"按 speed-profile 编译后状态为 {ctx.data['dexopt']}（{out.strip()[:80]}）"
    finally:
        ok, _ = ctx.dev.install(dbg)
        if not ok:
            ctx.dev.uninstall(PKG)
            ctx.dev.install(dbg, grant=True)
        reprepare(ctx)
