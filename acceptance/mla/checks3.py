"""M3 系统能力：频道、审批、片单、系列直连、第三方小组件。"""
from __future__ import annotations

import json
import time

from .base import PASS, WARN, CheckFailed, Skip
from .checks import ACT, PROTO_APPS, api, check, need, no_tvprovider, tvprovider
from .launcher import FIX, PKG, PROJ, Fixtures

P = Fixtures.pkg
RATIOS = {"16:9": 16 / 9, "3:2": 1.5, "4:3": 4 / 3, "1:1": 1.0, "2:3": 2 / 3, "movie": 1 / 1.441}


def approval(st, pkg, key):
    return next((a.get("state") for a in st.get("approvals", []) if a.get("pkg") == pkg and a.get("channelKey") == key), None)


def wait_approval(ctx, pkg, key, want, timeout=12):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if approval(ctx.L.state(), pkg, key) == want:
            return True
        time.sleep(0.2)
    return False


def wait_state(ctx, pred, timeout=3.0, interval=0.1):
    """轮询状态接口直到条件成立（或超时），返回最后一次状态。"""
    t0 = time.time()
    st = ctx.L.state()
    while not pred(st) and time.time() - t0 < timeout:
        time.sleep(interval)
        st = ctx.L.state()
    return st


def bridge_calls(ctx, fid, method=None):
    return [e for e in ctx.F.log(fid) if e.get("event") == "bridge_call" and (method is None or e.get("method") == method)
            and e.get("caller") == PKG]


# --------------------------------------------------------------------- 频道与审批
@check("D301", "频道请求：非系列应用的请求进入待审，主页不显示", "M3", when=tvprovider)
def d301(ctx):
    ctx.F.install("spark", "probe")
    ctx.publish("spark")
    pkg = P("spark")
    need(wait_approval(ctx, pkg, "spark.weekly", "pending"), "requestChannelBrowsable 之后状态接口没有 pending 记录（未注册或未处理 CHANNEL_BROWSABLE_REQUESTED）")
    st = ctx.home()
    need(ctx.row_items(st, f"ch:{pkg}:spark.weekly") is None, "未批准的频道出现在主页")
    img, tree, st = ctx.snap("pending")
    need(tree.find("manage_channels") or ctx.L.seek("manage_channels", ("DOWN",), 25), "没有「管理频道」入口")


@check("D302", "管理频道里批准后，频道行按权重显示全部节目", "M3", when=tvprovider)
def d302(ctx):
    ctx.publish("spark", wait=1)
    pkg = P("spark")
    ctx.approve(pkg, "spark.weekly")
    st = ctx.home()
    items = ctx.row_items(st, f"ch:{pkg}:spark.weekly")
    want = [f"prog:{pkg}:spark.{k}" for k in ("mars", "jelly", "neon2", "autumn", "snow", "lotus", "dunes")]
    need(items == want, f"频道行 {items}")
    need(ctx.seek_row(f"ch:{pkg}:spark.weekly"), "方向键到不了该频道行")
    ctx.snap("channel-row")


@check("D303", "App 发起的显示请求：确认页「不显示」返回取消，「显示」返回 RESULT_OK 并上屏", "M3", when=tvprovider)
def d303(ctx):
    ctx.publish("spark", wait=1)
    pkg = P("spark")
    for choice, code in (("confirm_channel:deny", 0), ("confirm_channel:allow", -1)):
        ctx.F.cmd("spark", "clear_log")
        ctx.dev.sh(f"am start -n {pkg}/{FIX}.RequestBrowsableActivity --es channel spark.later --es target {PKG}")
        wait_state(ctx, lambda s: s.get("overlay") == "confirm_channel", 2.5)
        ctx.L.settle()
        img, tree, st = ctx.snap(f"confirm-{choice.split(':')[1]}")
        need(st.get("overlay") == "confirm_channel" or tree.find("confirm_channel:allow"), "没有出现频道确认页")
        need(ctx.L.seek(choice, ("LEFT", "RIGHT", "DOWN", "UP"), 4), f"确认页找不到 {choice}")
        ctx.L.press("OK")
        e = ctx.F.wait("spark", lambda e: e.get("event") == "browsable_result", 8)
        need(e and e.get("resultCode") is not None and int(e["resultCode"]) == code, f"夹具收到的结果 {e}，应为 {code}")
    need(wait_approval(ctx, pkg, "spark.later", "approved"), "确认后状态接口未记为 approved")
    st = ctx.home()
    need(ctx.row_items(st, f"ch:{pkg}:spark.later"), "确认后主页没有「稍后再看」频道行")


@check("D304", "卡片宽度按海报比例、行高统一（16:9 / 3:2 / 4:3 / 1:1 / 2:3 / 电影海报）", "M3", when=tvprovider)
def d304(ctx):
    ctx.publish("aspect", wait=1)
    pkg = P("aspect")
    ctx.approve(pkg, "aspect.ratios")
    need(ctx.seek_row(f"ch:{pkg}:aspect.ratios"), "到不了比例测试行")
    keys = {"16:9": "16x9", "3:2": "3x2", "4:3": "4x3", "1:1": "1x1", "2:3": "2x3", "movie": "movie"}
    seen, msgs, heights = {}, [], []
    for i in range(9):
        tree = ctx.dev.dump()
        st = ctx.L.state()
        foc = ctx.L.focus(st)
        for k, r in RATIOS.items():
            n = tree.find(f"prog:{pkg}:aspect.{keys[k]}")
            if n and n.rid != foc and k not in seen:
                x1, y1, x2, y2 = n.bounds
                if x2 - x1 > 10 and y2 - y1 > 10 and x1 >= 0 and x2 <= ctx.dev.size[0]:
                    seen[k] = (x2 - x1) / (y2 - y1)
                    heights.append(ctx.dev.dp(y2 - y1))
        foc_key = next((k for k, key in keys.items() if f"prog:{pkg}:aspect.{key}" == foc), None)
        if len(seen) == len(RATIOS) and foc_key in seen:
            break
        ctx.L.press("RIGHT", settle=False)
        ctx.L._wait_focus_change(foc, 0.8)
    for k, r in RATIOS.items():
        if k not in seen:
            msgs.append(f"{k} 卡片未完整出现在屏幕上")
        elif abs(seen[k] - r) / r > 0.04:
            msgs.append(f"{k} 宽高比 {seen[k]:.3f}，应为 {r:.3f}")
    if heights and (max(heights) - min(heights) > 4 or abs(sum(heights) / len(heights) - 144) > 6):
        msgs.append(f"卡片高度 {[round(h) for h in heights]}dp，应统一为 144dp")
    ctx.data["ratios"] = seen
    need(not msgs, "；".join(msgs))


@check("D305", "TYPE_CHANNEL 节目没有比例时显示 1:1 台标卡，带直播角标", "M3", when=tvprovider)
def d305(ctx):
    ctx.publish("prism", wait=1)
    pkg = P("prism")
    ctx.approve(pkg, "prism.live")
    need(ctx.seek_row(f"ch:{pkg}:prism.live"), "到不了棱镜直播行")
    ctx.L.press("RIGHT")
    img, tree, st = ctx.snap("logo-cards")
    n = tree.find(f"prog:{pkg}:prism.ch1")
    need(n, "找不到 prism.ch1 卡片")
    x1, y1, x2, y2 = n.bounds
    need(abs((x2 - x1) / (y2 - y1) - 1) < 0.05, f"台标卡宽高比 {(x2 - x1) / (y2 - y1):.2f}")
    need(any("直播" in t for t in n.texts()) or any("直播" in t for x in tree.prefix(f"prog:{pkg}:") for t in x.texts()), "没有「直播」角标")


@check("D306", "没有海报、海报失效时：来源应用取色的实色卡加标题，仍可打开", "M3", when=tvprovider)
def d306(ctx):
    ctx.publish("aspect", wait=1)
    pkg = P("aspect")
    ctx.approve(pkg, "aspect.ratios")
    need(ctx.seek_row(f"ch:{pkg}:aspect.ratios"), "到不了比例测试行")
    tag0 = f"prog:{pkg}:aspect.noart"
    t0 = time.time()
    while time.time() - t0 < 25 and tag0 not in (ctx.row_items(ctx.L.state(), f"ch:{pkg}:aspect.ratios") or []):
        time.sleep(0.1)
    msgs = []
    for key, title in (("aspect.noart", "无图节目"), ("aspect.broken", "图片失效")):
        tag = f"prog:{pkg}:{key}"
        need(ctx.L.seek(tag, ("LEFT", "RIGHT"), 12),
             f"找不到 {tag}（行内 {ctx.row_items(ctx.L.state(), f'ch:{pkg}:aspect.ratios')}，焦点 {ctx.L.focus()}）")
        ctx.L.press("LEFT")
        img, tree, st = ctx.snap(key)
        n = tree.find(tag)
        if not n:
            msgs.append(f"{tag} 不在屏幕上")
            continue
        if not any(title in t for t in n.texts()):
            msgs.append(f"{key} 卡片上没有标题文字")
        x1, y1, x2, y2 = n.bounds
        from .device import lch, mean_color
        c = mean_color(img, (x1 + 6, y1 + 6, x2 - 6, y1 + (y2 - y1) // 3))
        if c is None or lch(c)[0] < 12:
            msgs.append(f"{key} 卡片底色 {c}（黑色/空白）")
        ctx.L.press("RIGHT")
    need(not msgs, "；".join(msgs))


@check("D307", "节目 INTENT_URI 指向其他应用时不得越权拉起", "M3", when=tvprovider)
def d307(ctx):
    ctx.F.install("probe")
    ctx.publish("aspect", wait=1)
    pkg = P("aspect")
    ctx.approve(pkg, "aspect.ratios")
    ctx.F.cmd("probe", "clear_log")
    need(ctx.seek_row(f"ch:{pkg}:aspect.ratios"), "到不了比例测试行")
    need(ctx.L.seek(f"prog:{pkg}:aspect.evil", ("RIGHT",), 12), "找不到越权跳转节目")
    ctx.L.press("OK")
    time.sleep(1.5)
    need(ctx.dev.resumed_pkg() != P("probe"), "桌面按节目 INTENT_URI 拉起了另一个应用（probe）")
    need(not any(e.get("event") == "launched" for e in ctx.F.log("probe")), "probe 记录到了 launched")
    if ctx.dev.resumed_pkg() != PKG:
        ctx.dev.key("BACK", wait=1.5)


@check("D308", "继续观看：剩余分钟与集数文字与节目数据一致", "M3", when=tvprovider)
def d308(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=1)
    need(ctx.seek_row("continue"), "到不了继续观看行")
    want = {f"wn:{P('dolphin')}:dolphin.volcano": r"第 2 集 · 剩 18 分钟", f"wn:{P('cloud')}:cloud.glacier": r"剩 41 分钟",
            f"wn:{P('spark')}:spark.sakura": r"S1 E5 · 剩 9 分钟", f"wn:{P('dolphin')}:dolphin.starry": r"剩 52 分钟",
            f"wn:{P('pulse')}:pulse.rain": r"剩 23 分钟"}
    msgs = []
    for tag, txt in want.items():
        if not ctx.L.seek(tag, ("RIGHT", "LEFT"), 8):
            msgs.append(f"找不到 {tag}")
            continue
        ctx.L.settle()
        tree = ctx.dev.dump()
        n = tree.find(tag)
        texts = " | ".join(n.texts()) if n else ""
        if txt not in texts:
            hdr = tree.find("info")
            texts += " | " + " | ".join(hdr.texts() if hdr else [])
            if txt not in texts:
                msgs.append(f"{tag}：期望「{txt}」，界面为「{texts[:80]}」")
    need(not msgs, "；".join(msgs))


@check("D118", "片单：Hero 按钮加入/移出，空时隐藏，重启后保留", "M1", when=tvprovider)
def d118(ctx):
    ctx.publish("dolphin", "spark", "cloud", "pulse", wait=2)
    st = ctx.home(reset=True)
    need((st.get("hero") or {}).get("visible"), "Hero 未显示")
    need(not ctx.row_items(st, "watchlist"), "初始片单不为空")
    need(ctx.L.seek("hero:watchlist", ("RIGHT",), 3), "找不到 hero:watchlist")
    ctx.L.press("OK")
    ctx.L.settle()
    t0 = time.time()
    st = ctx.L.state()
    while time.time() - t0 < 3 and not (st.get("watchlist") and ctx.row_items(st, "watchlist")):
        time.sleep(0.1)
        st = ctx.L.state()
    need(st.get("watchlist") and ctx.row_items(st, "watchlist"), "加入后没有片单行")
    img, tree, st = ctx.snap("watchlist-added")
    need(any("已在片单" in t for t in (tree.find("hero:watchlist").texts() if tree.find("hero:watchlist") else [])), "按钮文字没有变成「已在片单」")
    ctx.L.stop()
    st = ctx.L.start()
    t0 = time.time()
    while time.time() - t0 < 3 and not st.get("watchlist"):
        time.sleep(0.1)
        st = ctx.L.state()
    need(st.get("watchlist"), "重启后片单丢失")
    ctx.home(reset=True)
    need(ctx.L.seek("hero:watchlist", ("RIGHT",), 3), "找不到 hero:watchlist")
    ctx.L.press("OK")
    st = ctx.L.settle()
    t0 = time.time()
    while time.time() - t0 < 2 and (st.get("watchlist") or ctx.row_items(st, "watchlist")):
        time.sleep(0.1)
        st = ctx.L.state()
    need(not st.get("watchlist") and not ctx.row_items(st, "watchlist"), "移出后片单行仍在")


@check("D310", "在桌面移除节目：本地生效，应用重新发布后仍不显示", "M3", when=tvprovider)
def d310(ctx):
    ctx.publish("spark", wait=1)
    pkg = P("spark")
    ctx.approve(pkg, "spark.weekly")
    tag = f"prog:{pkg}:spark.jelly"
    row = f"ch:{pkg}:spark.weekly"
    need(ctx.seek_row(row), "到不了频道行")
    # TvProvider 逐条插入节目，桌面偶尔会读到发布到一半的行；缺目标时重新发布再等一次
    for attempt in range(2):
        t0 = time.time()
        while time.time() - t0 < 12 and tag not in (ctx.row_items(ctx.L.state(), row) or []):
            time.sleep(0.2)
        if tag in (ctx.row_items(ctx.L.state(), row) or []):
            break
        if attempt == 0:
            ctx.publish("spark", wait=2)
    long_menu(ctx, tag, row)
    need(ctx.L.seek("menu:remove", ("DOWN", "UP"), 8), "菜单里没有 menu:remove")
    ctx.L.press("OK")
    st = ctx.L.settle()
    need(tag not in (ctx.row_items(st, f"ch:{pkg}:spark.weekly") or []), "移除后仍在频道行")
    ctx.publish("spark", wait=3)
    st = ctx.home()
    need(tag not in (ctx.row_items(st, f"ch:{pkg}:spark.weekly") or []), "应用重新发布后被移除的节目又出现了")


# --------------------------------------------------------------------- 系列直连
def _launcher_cert(ctx):
    c = ctx.flags.get("launcherCert")
    if not c:
        raise CheckFailed("未取得桌面证书摘要")
    return c


@check("D311", "系列应用：安装后桌面调用 hello、init；频道请求自动批准并回传 approve", "M3", when=tvprovider)
def d311(ctx):
    ctx.F.uninstall("series")
    ctx.F.install("series")
    ctx.F.cmd("series", "set_launcher_cert", _launcher_cert(ctx))
    need(ctx.F.wait("series", lambda e: e.get("event") == "bridge_call" and e.get("method") == "init" and e.get("caller") == PKG, 10),
         "安装后 10 秒内没有收到桌面的 init 调用")
    need(bridge_calls(ctx, "series", "hello"), "没有收到 hello")
    ctx.publish("series", wait=1)
    pkg = P("series")
    need(wait_approval(ctx, pkg, "series.main", "approved"), "系列应用的频道请求没有被自动批准")
    need(ctx.F.wait("series", lambda e: e.get("event") == "bridge_call" and e.get("method") == "approve" and e.get("caller") == PKG, 20),
         f"没有回传 approve（calls {[e for e in ctx.F.log('series') if e.get('event') == 'bridge_call']}）")
    st = ctx.home()
    need(ctx.row_items(st, f"ch:{pkg}:series.main"), "主页没有系列频道行")


@check("D312", "系列应用：在桌面移除节目后回传 hide（带 internalProviderId）", "M3", when=tvprovider)
def d312(ctx):
    pkg = P("series")
    if not ctx.dev.installed(pkg):
        ctx.F.install("series")
        ctx.F.cmd("series", "set_launcher_cert", _launcher_cert(ctx))
    ctx.publish("series", wait=1)
    need(wait_approval(ctx, pkg, "series.main", "approved"), "系列频道未批准")
    tag = f"prog:{pkg}:series.p1"
    row = f"ch:{pkg}:series.main"
    need(ctx.seek_row(row), "到不了系列频道行")
    t0 = time.time()
    while time.time() - t0 < 8 and tag not in (ctx.row_items(ctx.L.state(), row) or []):
        time.sleep(0.1)
    long_menu(ctx, tag, row)
    need(ctx.L.seek("menu:remove", ("DOWN", "UP"), 8), "菜单里没有 menu:remove")
    ctx.L.press("OK")
    e = ctx.F.wait("series", lambda e: e.get("event") == "bridge_call" and e.get("method") == "hide" and e.get("caller") == PKG, 10)
    need(e and e.get("arg") == "series.p1", f"hide 回传：{e}")


@check("D313", "证书不符的同名应用：不调用直连、不自动批准", "M3", when=tvprovider)
def d313(ctx):
    ctx.F.uninstall("series")
    ctx.F.install("series_impostor")
    ctx.F.cmd("series_impostor", "clear_log")
    time.sleep(1.5)
    ctx.publish("series_impostor", wait=3)
    need(approval(ctx.L.state(), P("series"), "series.main") in ("pending", None), "冒名应用的频道被自动批准")
    calls = bridge_calls(ctx, "series_impostor")
    need(not calls, f"桌面调用了冒名应用的直连：{calls[:2]}")
    ctx.F.uninstall("series")


@check("D314", "不在信任名单的直连 Provider：不调用、频道进入待审", "M3", when=tvprovider)
def d314(ctx):
    ctx.F.install("rogue")
    time.sleep(1.5)
    ctx.publish("rogue", wait=3)
    need(approval(ctx.L.state(), P("rogue"), "rogue.main") in ("pending", None), "野生桥接的频道被自动批准")
    calls = [e for e in ctx.F.log("rogue") if e.get("event") in ("bridge_call", "bridge_query") and e.get("caller") == PKG]
    need(not calls, f"桌面调用了未授信的直连：{calls[:2]}")


@check("D315", "API 23–25：系列频道从直连读取行数据并显示", "M3", when=no_tvprovider)
def d315(ctx):
    ctx.F.uninstall("series")
    ctx.F.install("series")
    ctx.F.cmd("series", "set_launcher_cert", _launcher_cert(ctx))
    need(ctx.F.wait("series", lambda e: e.get("event") == "bridge_query" and e.get("caller") == PKG, 10), "桌面没有查询直连行数据")
    pkg = P("series")
    t0 = time.time()
    while time.time() - t0 < 15:
        st = ctx.home()
        if ctx.row_items(st, f"ch:{pkg}:series.main"):
            break
        time.sleep(0.5)
    need(ctx.row_items(ctx.L.state(), f"ch:{pkg}:series.main") == [f"prog:{pkg}:series.p1", f"prog:{pkg}:series.p2"], "主页没有直连提供的系列频道行")
    ctx.snap("series-bridge-rows")


# --------------------------------------------------------------------- 第三方小组件
def provider(fid, cls):
    return f"{P(fid)}/{FIX}.{cls}"


def widgets_page(ctx):
    """回到主页并把焦点停到「小组件」Tab，等页面切换完成。"""
    for _ in range(3):
        ctx.home()
        if not (ctx.L.focus(ctx.L.state()) or "").startswith("nav:"):
            if not ctx.L.seek("nav:home", ("UP",), 8):
                ctx.dev.key("HOME", wait=0.8)
                ctx.L.settle(2)
                continue
        if ctx.L.seek("nav:widgets", ("RIGHT", "LEFT"), 4):
            st = wait_state(ctx, lambda s: s.get("page") == "widgets", 4.0)
            if st.get("page") == "widgets":
                ctx.L.settle()
                return
        ctx.dev.key("HOME", wait=0.8)
        ctx.L.settle(2)
    raise CheckFailed("到不了小组件页")


def long_menu(ctx, tag, row=None):
    """长按节目打开上下文菜单；菜单在 400ms 长按判定后才出现，按状态轮询等它上屏。"""
    last = ""
    for _ in range(3):
        st = ctx.L.state()
        if st.get("overlay") == "menu":
            return st
        if st.get("overlay"):
            ctx.dev.key("BACK", wait=0.4)
        if not ctx.L.alive_fast(force=True):
            ctx.home()
        found = ctx.L.seek(tag, ("RIGHT", "LEFT"), 20)
        if not found and row:
            # 频道数据刷新可能把焦点移出行；重新进入该行再找
            ctx.seek_row(row)
            found = ctx.L.seek(tag, ("RIGHT", "LEFT"), 20)
        if not found:
            st = ctx.L.state()
            last = (f"焦点到不了 {tag}（焦点 {ctx.L.focus(st)}，行内 "
                    f"{ctx.row_items(st, row) if row else None}）")
            time.sleep(0.2)
            continue
        ctx.L.press("OK", long=True)
        t0 = time.time()
        while time.time() - t0 < 2.5:
            st = ctx.L.state()
            if st.get("overlay") == "menu":
                return st
            time.sleep(0.1)
        last = f"overlay={st.get('overlay')} focus={ctx.L.focus(st)}"
        time.sleep(0.2)
    raise CheckFailed(f"长按没有打开上下文菜单（{tag}；{last}）")


def open_picker(ctx):
    widgets_page(ctx)
    ctx.L.press("DOWN")
    need(ctx.L.seek("widget:add", ("RIGHT", "DOWN", "LEFT"), 20), "小组件页找不到「添加小组件」")
    for _ in range(2):
        ctx.L.press("OK")
        st = wait_state(ctx, lambda s: s.get("overlay") == "picker", 2)
        if st.get("overlay") == "picker":
            break
        ctx.dev.key("BACK", wait=0.3)
    need(st.get("overlay") == "picker", f"overlay={st.get('overlay')}")


def pick(ctx, prov):
    need(ctx.L.seek(f"picker:{prov}", ("DOWN",), 40) or ctx.L.seek(f"picker:{prov}", ("UP",), 40), f"选择器里找不到 {prov}")
    ctx.L.press("OK")
    return ctx.L.settle(4)


def widget_of(st, prov):
    return [w for w in st.get("widgets", []) if w.get("provider") == prov]


@check("D316", "未授权时添加第三方小组件：授权页 → 用电脑授权 → grantbind → 添加成功", "M3")
def d316(ctx):
    ctx.F.install("sky")
    ctx.dev.sh(f"cmd appwidget revokebind --package {PKG} --user 0" if ctx.dev.api >= 24 else f"appwidget revokebind --package {PKG} --user 0")
    time.sleep(0.5)
    open_picker(ctx)
    prov = provider("sky", "WeatherWidget")
    pick(ctx, prov)
    img, tree, st = ctx.snap("auth")
    bind_handler = ctx.flags.get("bindHandler")
    if bind_handler:
        raise Skip("设备有 ACTION_APPWIDGET_BIND 处理者，走系统确认界面（路径 2）")
    for t in ("picker:auth:adb", "picker:auth:cancel"):
        need(tree.find(t), f"授权页缺少 {t}")
    need(ctx.L.seek("picker:auth:adb", ("DOWN", "UP"), 4), "到不了 picker:auth:adb")
    ctx.L.press("OK")
    img, tree, st = ctx.snap("auth-adb")
    cmd_shown = any("appwidget grantbind --package com.github.mytv.launcher" in t for n in tree.nodes for t in (n.text, n.desc))
    need(cmd_shown, "电脑授权页没有显示 grantbind 命令")
    out = ctx.dev.sh(f"appwidget grantbind --package {PKG} --user 0")
    need(ctx.L.seek("picker:adb:done", ("DOWN", "UP"), 4), "到不了 picker:adb:done")
    ctx.L.press("OK")
    wait_state(ctx, lambda s: widget_of(s, prov), 3)
    st = ctx.L.settle(4)
    ws = widget_of(st, prov)
    need(ws, f"授权后没有添加小组件（grantbind 输出：{out.strip()[:80]}）")
    wid = ws[0]["id"]
    need(ctx.F.wait("sky", lambda e: e.get("event") == "widget_update", 10),
         f"provider 没有收到 onUpdate（最近事件 {ctx.F.log('sky')[-6:]}；日志 "
         f"{ctx.dev.sh('logcat -d | grep -i appwidget | tail -8')}）")
    need(ctx.F.wait("sky", lambda e: e.get("event") == "widget_options", 10), "宿主没有下发尺寸（updateAppWidgetSize）")
    t0 = time.time()
    while time.time() - t0 < 2.5 and ctx.L.focus(st) != f"widget:{wid}":
        time.sleep(0.1)
        st = ctx.L.state()
    need(ctx.L.focus(st) == f"widget:{wid}", f"添加后焦点在 {ctx.L.focus(st)}，应在新小组件上")
    ctx.flags["skyWidget"] = wid


@check("D317", "已授权后添加：不再出现授权步骤", "M3")
def d317(ctx):
    ctx.F.install("pulse")
    ctx.dev.sh(f"appwidget grantbind --package {PKG} --user 0")
    open_picker(ctx)
    prov = provider("pulse", "MusicWidget")
    pick(ctx, prov)
    wait_state(ctx, lambda s: widget_of(s, prov), 2)
    st = ctx.L.settle()
    need(widget_of(st, prov), "没有直接添加（仍出现授权步骤？）")


@check("D318", "带配置页的小组件：完成则添加，取消则不添加并释放 ID", "M3")
def d318(ctx):
    ctx.F.install("book")
    ctx.dev.sh(f"appwidget grantbind --package {PKG} --user 0")
    prov = provider("book", "BookWidget")
    n0 = len(widget_of(ctx.L.state(), prov))
    for action, add in (("cancel", False), ("done", True)):
        ctx.F.cmd("book", "clear_log")
        open_picker(ctx)
        pick(ctx, prov)
        front = ctx.dev.resumed_pkg()
        t0 = time.time()
        while front != P("book") and time.time() - t0 < 2.5:
            time.sleep(0.1)
            front = ctx.dev.resumed_pkg()
        need(front == P("book"), f"没有打开配置页，前台 {ctx.dev.resumed()}")
        if action == "done":
            ctx.L.press("OK")
        else:
            ctx.dev.key("BACK", wait=0.5)
        if add:
            st = wait_state(ctx, lambda s: len(widget_of(s, prov)) == n0 + 1, 2)
        else:
            st = ctx.L.settle(4)
        n = len(widget_of(st, prov))
        need(n == n0 + (1 if add else 0), f"{action} 后小组件数 {n}（之前 {n0}）")
        if not add:
            need(ctx.F.wait("book", lambda e: e.get("event") == "widget_deleted", 6), "取消后没有删除已分配的 appWidgetId")
        n0 = n


@check("D319", "小组件内部操作：OK 进入，方向键在可点控件间移动，OK 送达 provider，BACK 回外框", "M3")
def d319(ctx):
    ctx.dev.sh(f"appwidget grantbind --package {PKG} --user 0")
    msgs = []
    for fid, cls in (("sky", "WeatherWidget"), ("pulse", "MusicWidget")):
        ctx.F.install(fid)
        prov = provider(fid, cls)
        st = ctx.L.state()
        if not widget_of(st, prov):
            open_picker(ctx)
            pick(ctx, prov)
            wait_state(ctx, lambda s: widget_of(s, prov), 2)
        st = ctx.L.settle()
        wid = widget_of(st, prov)[0]["id"]
        widgets_page(ctx)
        ctx.L.press("DOWN")
        if not ctx.L.seek(f"widget:{wid}", ("RIGHT", "DOWN", "LEFT", "UP"), 24):
            msgs.append(f"找不到 widget:{wid}")
            continue
        ctx.F.cmd(fid, "clear_log")
        ctx.L.press("OK")
        if cls == "FitWidget":
            ctx.dev.key("DOWN", wait=0.5)
        img, tree, st = ctx.snap(f"inner-{fid}")
        frame = tree.find(f"widget:{wid}")
        inner = [n for n in tree.nodes if n.focused and frame and any(a is frame for a in n.ancestors())]
        if not inner:
            msgs.append(f"{fid}：OK 后焦点没有进入小组件内部")
        ctx.dev.key("OK", wait=0.3)
        if not ctx.F.wait(fid, lambda e: e.get("event") == "widget_click", 6):
            msgs.append(f"{fid}：provider 没有收到点击")
        if ctx.dev.resumed_pkg() != PKG:
            ctx.dev.key("BACK", wait=0.5)
        st = ctx.L.press("BACK")
        if ctx.L.focus(st) != f"widget:{wid}":
            msgs.append(f"{fid}：BACK 后焦点在 {ctx.L.focus(st)}")
    need(not msgs, "；".join(msgs))


@check("D320", "崩溃隔离：布局加载失败的小组件只替换为占位卡，桌面不崩溃", "M3")
def d320(ctx):
    ctx.F.install("crash")
    ctx.dev.sh(f"appwidget grantbind --package {PKG} --user 0")
    prov = provider("crash", "CrashWidget")
    open_picker(ctx)
    pick(ctx, prov)
    wait_state(ctx, lambda s: any(w.get("state") == "error" for w in widget_of(s, prov)), 3)
    st = ctx.L.settle()
    need(ctx.L.alive(), "桌面不在前台")
    ws = widget_of(st, prov)
    need(ws and ws[0].get("state") == "error", f"状态接口：{ws}")
    img, tree, st = ctx.snap("crash-widget")
    wid = ws[0]["id"]
    need(tree.find(f"widget:{wid}:reload") or tree.find(f"widget:{wid}:remove") or ctx.L.seek(f"widget:{wid}", ("RIGHT", "LEFT", "DOWN", "UP"), 20),
         "没有占位卡")


@check("D321", "provider 被卸载：显示失效占位，可移除", "M3")
def d321(ctx):
    ctx.F.install("fit")
    ctx.dev.sh(f"appwidget grantbind --package {PKG} --user 0")
    prov = provider("fit", "FitWidget")
    if not widget_of(ctx.L.state(), prov):
        open_picker(ctx)
        pick(ctx, prov)
        wait_state(ctx, lambda s: widget_of(s, prov), 2)
    wid = widget_of(ctx.L.settle(), prov)[0]["id"]
    ctx.F.uninstall("fit")
    wait_state(ctx, lambda s: any(x.get("state") == "missing" for x in s.get("widgets", []) if x["id"] == wid), 4)
    ctx.home()
    st = ctx.L.settle()
    w = next((x for x in st.get("widgets", []) if x["id"] == wid), None)
    need(w and w.get("state") == "missing", f"卸载后状态 {w}")
    widgets_page(ctx)
    ctx.L.press("DOWN")
    need(ctx.L.seek(f"widget:{wid}:remove", ("RIGHT", "DOWN", "LEFT", "UP"), 30), "占位卡上没有「移除」")
    ctx.L.press("OK")
    st = ctx.L.settle()
    need(not any(x["id"] == wid for x in st.get("widgets", [])), "移除后仍在")


@check("D322", "重启后小组件与位置保持", "M3")
def d322(ctx):
    before = sorted((w["id"], w["provider"], w["x"], w["y"], w["spanX"], w["spanY"]) for w in ctx.L.state().get("widgets", []))
    if not before:
        raise Skip("当前没有小组件（前面的检查失败）")
    ctx.L.stop()
    st = ctx.L.start()
    t0 = time.time()
    while time.time() - t0 < 5 and not st.get("widgets"):
        time.sleep(0.1)
        st = ctx.L.state()
    after = sorted((w["id"], w["provider"], w["x"], w["y"], w["spanX"], w["spanY"]) for w in st.get("widgets", []))
    need(before == after, f"重启前 {before}，重启后 {after}")


@check("D323", "应用请求「添加到主屏幕」：确认页添加后回传 appWidgetId", "M3", when=api(26))
def d323(ctx):
    ctx.F.install("pulse")
    ctx.dev.sh(f"appwidget grantbind --package {PKG} --user 0")
    ctx.F.cmd("pulse", "clear_log")
    ctx.dev.sh(f"am start -n {P('pulse')}/{FIX}.PinWidgetActivity --es provider MusicWidget")
    e = ctx.F.wait("pulse", lambda e: e.get("event") == "pin_requested", 8)
    need(e and e.get("isRequestPinAppWidgetSupported") is True, f"pin_requested：{e}（桌面不是默认桌面或未处理 CONFIRM_PIN_APPWIDGET）")
    wait_state(ctx, lambda s: s.get("overlay") == "confirm_pin", 2)
    ctx.L.settle()
    img, tree, st = ctx.snap("confirm-pin")
    need(ctx.L.seek("confirm_pin:add", ("LEFT", "RIGHT", "DOWN", "UP"), 4), "确认页找不到 confirm_pin:add")
    ctx.L.press("OK")
    r = ctx.F.wait("pulse", lambda e: e.get("event") == "pin_result", 8)
    need(r and int(r.get("appWidgetId", -1)) > 0, f"应用没有收到 appWidgetId：{r}")
    st = ctx.L.settle()
    need(any(w["id"] == int(r["appWidgetId"]) for w in st.get("widgets", [])), "桌面没有放置该小组件")


@check("D324", "移动与调整大小：方向键移动占位框，OK 确认", "M3")
def d324(ctx):
    wid = ctx.flags.get("skyWidget") or next((w["id"] for w in ctx.L.state().get("widgets", []) if "WeatherWidget" in w["provider"]), None)
    if not wid:
        raise Skip("没有天气小组件（D316 失败）")
    widgets_page(ctx)
    ctx.L.press("DOWN")
    need(ctx.L.seek(f"widget:{wid}", ("RIGHT", "DOWN", "LEFT", "UP"), 24), f"找不到 widget:{wid}")
    w0 = next(w for w in ctx.L.state()["widgets"] if w["id"] == wid)
    st = {}
    for _ in range(3):
        ctx.L.press("OK", long=True)
        st = wait_state(ctx, lambda s: s.get("overlay") == "menu", 2.0)
        if st.get("overlay") == "menu":
            break
        ctx.dev.key("BACK", wait=0.3)
        time.sleep(0.4)
    need(st.get("overlay") == "menu", f"长按没有打开菜单（overlay={st.get('overlay')}）")
    need(ctx.L.seek("menu:move", ("DOWN", "UP"), 8), "菜单没有 menu:move")
    ctx.L.press("OK")
    ctx.L.press("DOWN")
    ctx.L.press("OK")
    w1 = next(w for w in ctx.L.state()["widgets"] if w["id"] == wid)
    need((w1["x"], w1["y"]) != (w0["x"], w0["y"]), f"移动后位置未变 {w0['x'], w0['y']}")
    for _ in range(3):
        ctx.L.press("OK", long=True)
        st = wait_state(ctx, lambda s: s.get("overlay") == "menu", 2.0)
        if st.get("overlay") == "menu":
            break
        ctx.dev.key("BACK", wait=0.3)
        time.sleep(0.4)
    need(ctx.L.seek("menu:resize", ("DOWN", "UP"), 8),
         f"菜单没有 menu:resize（overlay={ctx.L.state().get('overlay')} 焦点 {ctx.L.focus()}）")
    ctx.L.press("OK")
    ctx.L.press("LEFT")
    ctx.L.press("OK")
    w2 = next(w for w in ctx.L.state()["widgets"] if w["id"] == wid)
    need((w2["spanX"], w2["spanY"]) != (w1["spanX"], w1["spanY"]), "调整大小后格数未变")
    need(ctx.F.wait("sky", lambda e: e.get("event") == "widget_options", 6), "调整大小后没有重新下发尺寸")


@check("D325", "API 31+：主题稳定 3 秒后才给小组件换色，3 秒内最多重载 1 次", "M3", when=api(31))
def d325(ctx):
    ctx.F.install("dolphin", "spark", "pulse", "prism")
    wid = ctx.flags.get("skyWidget") or next((w["id"] for w in ctx.L.state().get("widgets", []) if "WeatherWidget" in w["provider"]), None)
    if not wid:
        raise Skip("没有天气小组件（D316 失败）")
    ctx.home()
    ctx.seek_row("apps")
    st = ctx.L.state()
    items = ctx.row_items(st, "apps") or []
    # 应用行只放最近更新的 10 个，真实应用会挤占；只用行内实际存在的彩色夹具
    cands = [f for f in ("dolphin", "spark", "pulse", "prism", "cloud") if ctx.app_tag(f) in items]
    need(len(cands) >= 2, f"应用行里没有可用于取色的夹具：{items}")
    seeds = set()

    def seed_of(fid):
        ctx.seek_in_row(ctx.app_tag(fid))
        ctx.L.settle()
        # 焦点稳定后 600ms 才换主题；等过渡结束再取值，否则读到的是上一个来源
        pkg = Fixtures.pkg(fid)
        t0, st = time.time(), ctx.L.state()
        while time.time() - t0 < 2.5:
            th = st.get("theme") or {}
            if not th.get("transitioning") and pkg in (th.get("sourceKey") or ""):
                break
            time.sleep(0.1)
            st = ctx.L.state()
        seeds.add((st.get("theme") or {}).get("seed"))

    seed_of(cands[0])
    ctx.L.call("reset_counters")
    for fid in cands[1:4]:
        seed_of(fid)
    burst = ((ctx.L.state().get("counters") or {}).get("widgetReloads") or {}).get(str(wid), 0)
    need(len(seeds) >= 2, f"换焦点后主题没有变化，无法判断小组件换色：{seeds}")
    t0 = time.time()
    after = burst
    while time.time() - t0 < 6 and after < 1:
        time.sleep(0.2)
        after = ((ctx.L.state().get("counters") or {}).get("widgetReloads") or {}).get(str(wid), 0)
    need(burst <= 1, f"连续换主题的 3 秒内重载 {burst} 次")
    need(after >= 1, "主题稳定后没有给小组件换色（setColorResources）")


@check("D326", "加入主页速览：最多 4 个，主页显示实时小组件", "M3")
def d326(ctx):
    ws = ctx.L.state().get("widgets", [])
    if not ws:
        raise Skip("没有小组件")
    wid = ws[0]["id"]
    widgets_page(ctx)
    ctx.L.press("DOWN")
    need(ctx.L.seek(f"widget:{wid}", ("RIGHT", "DOWN", "LEFT", "UP"), 24), f"找不到 widget:{wid}")
    if not next(w for w in ctx.L.state()["widgets"] if w["id"] == wid).get("onHome"):
        ctx.L.press("OK", long=True)
        need(ctx.L.seek("menu:to_home", ("DOWN", "UP"), 8), "菜单没有 menu:to_home")
        ctx.L.press("OK")
    st = ctx.home()
    items = ctx.row_items(st, "widgets") or []
    need(f"widget:{wid}" in items and len(items) <= 4, f"主页速览行 {items}")
