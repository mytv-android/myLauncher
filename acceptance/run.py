"""myLauncher 验收入口。

  python acceptance/run.py kit verify                      校验工具包未被修改
  python acceptance/run.py accept M1                       正式验收：构建 + 静态门禁 + 矩阵内全部模拟器
  python acceptance/run.py accept M3 --devices api28 --only D316,D317 --no-build   开发自查（不能作为正式结果）
  python acceptance/run.py static M2 [--no-build]          只跑静态门禁
  python acceptance/run.py device M2 --serial emulator-5556 --name api28            在已开启的模拟器上跑设备检查
  python acceptance/run.py selftest --serial emulator-5556 自检夹具与工具（不需要桌面）
  python acceptance/run.py boot api28 [--window]           启动矩阵中的一台模拟器

退出码：0 通过，1 不通过，2 工具或环境错误。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mla.base import (ADB, APKSIGNER, FAIL, KIT, PASS, SKIP, Report, Result, kit_freeze, kit_verify, ms_le, run, spec)  # noqa: E402
from mla import checks, checks0, checks3, checks33, checks45  # noqa: E402,F401  注册检查
from mla.checks import Ctx, add_copy_result, run_device  # noqa: E402
from mla.device import Device  # noqa: E402
from mla.launcher import FIX, PKG, Fixtures, Launcher  # noqa: E402
from mla.static import A, build, comp_name, filters, manifest, static_checks  # noqa: E402

MATRIX = spec("matrix")


# ------------------------------------------------------------------ 设备
def running_emulators() -> dict:
    """{avd 名: serial}"""
    rc, out, _ = run([str(ADB), "devices"], timeout=20)
    res = {}
    for l in out.splitlines():
        m = re.match(r"(emulator-\d+)\s+device", l)
        if m:
            rc, o, _ = run([str(ADB), "-s", m.group(1), "emu", "avd", "name"], timeout=15)
            name = o.strip().splitlines()[0].strip() if o.strip() else ""
            res[name] = m.group(1)
    return res


def kill_stale_avd(avd: str):
    """清掉同 AVD 但 adb 不可见的 qemu 进程（上次运行崩溃残留，会占住 AVD 导致新实例起不来）。"""
    if os.name != "nt":
        return
    ps = ("Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'qemu-system*' "
          f"-and $_.CommandLine -like '*-avd {avd} *' }} | ForEach-Object {{ $_.ProcessId }}")
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                             capture_output=True, text=True, timeout=30).stdout
    except Exception:
        return
    for line in out.split():
        if line.strip().isdigit():
            print(f"清理残留的 {avd} 进程（PID {line.strip()}）", flush=True)
            subprocess.run(["taskkill", "/T", "/F", "/PID", line.strip()],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def acquire(name, window) -> Device:
    d = MATRIX["devices"][name]
    serial = running_emulators().get(d["avd"])
    if serial:
        dev = Device(serial, name)
        print(f"使用已运行的 {d['avd']}（{serial}）")
    else:
        kill_stale_avd(d["avd"])
        print(f"启动模拟器 {d['avd']} …", flush=True)
        dev = Device.boot(d["avd"], name, window=window)
    if dev.api != d["api"]:
        raise RuntimeError(f"{name}：期望 API {d['api']}，实际 {dev.api}")
    dev.device_id = name
    return dev


_PORT_LOCK = threading.Lock()
_PORT_USED: set[int] = set()


def alloc_port() -> int:
    with _PORT_LOCK:
        for p in range(5562, 5600, 2):
            if p in _PORT_USED:
                continue
            import socket
            try:
                with socket.socket() as s:
                    s.bind(("127.0.0.1", p))
                with socket.socket() as s:
                    s.bind(("127.0.0.1", p + 1))
            except OSError:
                continue
            _PORT_USED.add(p)
            return p
    raise RuntimeError("没有空闲的模拟器端口")


def running_serials(avd: str) -> list[str]:
    rc, out, _ = run([str(ADB), "devices"], timeout=20)
    serials = []
    for l in out.splitlines():
        m = re.match(r"(emulator-\d+)\s+device", l)
        if not m or m.group(1) == "emulator-5554":
            continue
        rc, o, _ = run([str(ADB), "-s", m.group(1), "emu", "avd", "name"], timeout=15)
        if o.strip().splitlines() and o.strip().splitlines()[0].strip() == avd:
            serials.append(m.group(1))
    return serials


def acquire_group(name, count, window) -> list[Device]:
    """同一 AVD 并行启动多个实例（每台跑一部分检查），用于把单设备墙钟压进预算。"""
    if count <= 1:
        return [acquire(name, window)]
    d = MATRIX["devices"][name]
    # 同一 AVD 的所有实例必须都是 -read-only；先关掉已运行的非 read-only 实例。
    old = running_serials(d["avd"])
    for serial in old:
        print(f"关闭已运行的 {d['avd']}（{serial}），改用 read-only 并行实例")
        run([str(ADB), "-s", serial, "emu", "kill"], timeout=30)
    if old:
        time.sleep(4)
    kill_stale_avd(d["avd"])
    devs: list[Device | None] = [None] * count
    errs: list[BaseException] = []

    def boot_one(i: int):
        tag = f"{name}#{chr(97 + i)}"
        last = None
        for attempt in range(2):
            try:
                print(f"启动模拟器 {d['avd']} 作为 {tag} …", flush=True)
                dev = Device.boot(d["avd"], tag, window=window, port=alloc_port(), readonly=True)
                if dev.api != d["api"]:
                    dev.kill()
                    raise RuntimeError(f"{tag}：期望 API {d['api']}，实际 {dev.api}")
                dev.device_id = name
                devs[i] = dev
                return
            except BaseException as e:
                last = e
                print(f"{tag} 启动失败（第 {attempt + 1} 次）：{e}", flush=True)
                time.sleep(2)
        errs.append(last)

    ths = [threading.Thread(target=boot_one, args=(i,)) for i in range(count)]
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    if errs:
        for dev in devs:
            if dev and dev.emulator_started:
                dev.kill()
        raise errs[0]
    return [d for d in devs if d]


def guard_tv(dev: Device):
    if "android.software.leanback" not in dev.features():
        raise RuntimeError(f"{dev.serial} 不是电视设备（无 android.software.leanback），拒绝在其上操作")


def apk_cert(apk) -> str:
    rc, out, err = run([str(APKSIGNER), "verify", "--print-certs", str(apk)], timeout=120)
    # build-tools 37 起输出改成 "V2 Signer: certificate SHA-256 digest:"，旧版本是
    # "Signer #1 certificate SHA-256 digest:"，两种都要认。
    m = re.search(r"(?:Signer #\d+ |V\d+ Signer: )certificate SHA-256 digest: ([0-9a-f]{64})", out)
    return m.group(1) if m else ""


def home_activity(apk) -> str:
    m = manifest(apk)
    pkg = m.get("package")
    for a in list(m.iter("activity")) + list(m.iter("activity-alias")):
        if any("android.intent.action.MAIN" in ac and "android.intent.category.HOME" in ca for ac, ca in filters(a)):
            return f"{pkg}/{comp_name(pkg, a.get(A + 'name'))}"
    raise RuntimeError("debug APK 没有 HOME Activity")


STOCK_HOMES = [
    "com.google.android.apps.tv.launcherx/.home.HomeActivity",
    "com.google.android.tvlauncher/.HomeScreen",
]


def prepare(dev: Device, rep: Report, dbg: Path, ms: str, need_probe=True) -> tuple[Ctx, str]:
    guard_tv(dev)
    orig = dev.home_component()
    # FallbackHome 属于系统设置包；停用整个包会连带停用系统设置入口（D404 等）。
    # 先恢复被禁用的原厂桌面，让“原桌面”成为一个可以安全停用的独立包。
    if "FallbackHome" in orig:
        for comp in STOCK_HOMES:
            if dev.installed(comp.split("/")[0]):
                dev.sh(f"pm enable {comp.split('/')[0]}")
        restored = dev.home_component()
        if restored and "/" in restored and "FallbackHome" not in restored:
            rep.meta.setdefault("fallbackHome", {})[dev.name] = orig
            orig = restored
    rep.meta.setdefault("originalHome", {})[dev.name] = orig
    # 先卸载桌面再删夹具：桌面若还挂着夹具的小组件，先删夹具会让系统留下 provider 为空的小组件记录，
    # API 28 的 uiautomator 取根节点时会因此在 system_server 里 NPE，整棵树都取不到（A-010）
    dev.uninstall(PKG)
    Fixtures(dev).cleanup()
    # Play Store 会在后台自更新，更新时间把它顶进“最近更新”应用行、挤掉夹具，导致行内检查不稳定
    dev.disabled_update_apps = []
    if dev.installed("com.android.vending"):
        dev.sh("pm disable-user --user 0 com.android.vending")
        dev.disabled_update_apps.append("com.android.vending")
    dev.logcat_clear()
    if ms == "M0":
        ctx = Ctx(dev, rep, "", ms)
        return ctx, orig
    dev.uninstall(PKG)
    ok, msg = dev.install(dbg, grant=True)
    if not ok:
        raise RuntimeError(f"安装桌面失败：{msg}")
    comp = home_activity(dbg)
    disabled = ""
    if dev.api >= 24:
        orig_pkg = orig.split("/")[0] if "/" in orig else ""
        if orig_pkg and orig_pkg != PKG:
            dev.sh(f"pm disable-user --user 0 {orig_pkg}")
            disabled = orig_pkg
            dev.disabled_home = disabled
    try:
        if dev.api >= 24 and not dev.set_home(comp):
            raise RuntimeError(f"无法设为默认桌面（当前 {dev.home_component()}）")
        ctx = Ctx(dev, rep, comp, ms)
        ctx.flags["launcherCert"] = apk_cert(dbg)
        ctx.flags["originalHome"] = orig
        dev.state_forward = dev.forward_state()
        ctx.L.http = dev.state_forward
        ctx.L.start()
        ctx.L.pref("onboarding_done", "true")
        ctx.L.pref("ambient_idle_s", "0")
        ctx.L.stop()
        ctx.L.start()
        if ms >= "M3" and need_probe:
            ctx.F.install("probe")
            ctx.F.cmd("probe", "probe_handlers")
            h = ctx.F.wait("probe", lambda e: e.get("event") == "handlers", 15) or {}
            ctx.flags["bindHandler"] = bool(h.get("appwidgetBind"))
            ctx.F.uninstall("probe")
        return ctx, orig
    except Exception:
        if disabled:
            dev.sh(f"pm enable {disabled}")
            dev.disabled_home = ""
        raise


def teardown(dev: Device, orig: str):
    try:
        port = getattr(dev, "state_forward", None)
        if port:
            dev.adb("forward", "--remove", f"tcp:{port}")
            dev.state_forward = None
        # 与 prepare 同理：桌面先卸载（连带清掉它挂的小组件），再清夹具；只清本轮 publish 过的
        dev.uninstall(PKG)
        Fixtures(dev).cleanup(only_published=True)
        for pkg in getattr(dev, "disabled_update_apps", []):
            dev.sh(f"pm enable --user 0 {pkg}")
        dev.disabled_update_apps = []
        for cmd in ("cmd connectivity airplane-mode disable", "svc wifi enable", "svc data enable"):
            dev.sh(cmd)
        disabled = getattr(dev, "disabled_home", "")
        if disabled:
            dev.sh(f"pm enable {disabled}")
            dev.disabled_home = ""
        if dev.api >= 29:
            dev.sh(f"cmd role remove-role-holder --user 0 android.app.role.HOME {PKG}")
        if orig and not orig.startswith(PKG) and dev.api >= 24:
            dev.set_home(orig)
    except Exception as e:
        print(f"清理 {dev.name} 时出错：{e}")


def run_on(dev: Device, rep: Report, dbg: Path, ms: str, only=None):
    ctx, orig = prepare(dev, rep, dbg, ms)
    try:
        run_device(ctx, only=only)
    finally:
        teardown(dev, orig)


def check_groups(ms: str) -> list[set]:
    """按 matrix.json 的 partitions 把本里程碑适用的设备检查分组（依赖关系保持在同一组）。"""
    parts = MATRIX.get("partitions") or []
    groups: dict[int, set] = {}
    for c in checks.REG:
        if not ms_le(c["ms"], ms):
            continue
        if c["until"] and not ms_le(ms, c["until"]):
            continue
        cid = c["id"]
        if cid == "D199":
            continue
        key = next((i for i, g in enumerate(parts) if cid in g), 0)
        groups.setdefault(key, set()).add(cid)
    return [groups[k] for k in sorted(groups) if groups[k]]


def plan_groups(ms: str, want: int) -> list[set]:
    groups = check_groups(ms)
    if want <= 1 or len(groups) <= 1:
        return []
    if len(groups) > want:
        groups = groups[:want - 1] + [set().union(*groups[want - 1:])]
    return groups


def run_group(devs: list[Device], rep: Report, dbg: Path, ms: str, only=None, groups=None):
    if len(devs) == 1:
        run_on(devs[0], rep, dbg, ms, only)
        return
    ctxs: list = [None] * len(devs)
    origs: list = [None] * len(devs)
    errs: list = []

    def prep(i):
        try:
            g = groups[i] if groups and i < len(groups) else None
            ctxs[i], origs[i] = prepare(devs[i], rep, dbg, ms, need_probe=g is None or "D316" in g)
        except BaseException as e:
            errs.append(e)

    ths = [threading.Thread(target=prep, args=(i,)) for i in range(len(devs))]
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    if errs:
        for i, ctx in enumerate(ctxs):
            if ctx is not None:
                teardown(devs[i], origs[i])
        raise errs[0]
    def run_part(i):
        try:
            run_device(ctxs[i], only=groups[i] if groups and i < len(groups) else None, copy=False)
        except BaseException as e:
            rep.add(Result("D000", "设备检查", FAIL, devs[i].device_id,
                           f"{devs[i].name}: {type(e).__name__}: {e}"))

    ths = [threading.Thread(target=run_part, args=(i,)) for i in range(len(devs))]
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    expect = set()
    for c in checks.REG:
        if not ms_le(c["ms"], ms) or (c["until"] and not ms_le(ms, c["until"])) or c["id"] == "D199":
            continue
        expect.add(c["id"])
    seen = {r.id for r in rep.results if r.device == devs[0].device_id}
    missing = sorted(expect - seen)
    if missing:
        rep.add(Result("D000", "设备检查缺失", FAIL, devs[0].device_id, f"未执行：{missing}"))
    add_copy_result(rep, devs[0].device_id, [d for ctx in ctxs for d in ctx.L.dumps])
    ths = [threading.Thread(target=teardown, args=(devs[i], origs[i])) for i in range(len(devs))]
    for t in ths:
        t.start()
    for t in ths:
        t.join()


# ------------------------------------------------------------------ 命令
def cmd_kit(a):
    if a.action == "verify":
        ok, msg = kit_verify()
        print(("工具包完整，根哈希 " if ok else "工具包被修改：") + msg)
        return 0 if ok else 1
    if a.action == "freeze":
        root, z, zs = kit_freeze()
        print(f"MANIFEST 根哈希 {root}\n压缩包 {z}\n压缩包 SHA-256 {zs}")
        return 0


def ms_arg(v):
    v = v.upper()
    if v not in ("M0", "M1", "M2", "M3", "M4", "M5"):
        raise argparse.ArgumentTypeError("里程碑为 M0–M5")
    return v


def cmd_accept(a):
    formal = not (a.no_build or a.devices or a.only)
    rep = Report(a.milestone, "accept" if formal else "dev")
    rep.meta["formal"] = formal
    if not formal:
        rep.meta["note"] = "使用了 --no-build / --devices / --only，本次结果只供自查，不能作为里程碑验收"
    info = build(rep, skip=a.no_build)
    st = static_checks(rep, a.milestone, info)
    blocking = [r for r in st if r.id in ("S01", "S02", "S03") and r.status == FAIL]
    if blocking and not a.force_devices:
        print("静态门禁 S01–S03 未通过，跳过设备检查")
    else:
        names = a.devices.split(",") if a.devices else MATRIX["milestones"][a.milestone]
        only = set(a.only.split(",")) if a.only else None
        budget = int(MATRIX.get("deviceBudgetSeconds", 600))
        want = int(MATRIX.get("instancesPerDevice", 1)) if (only is None and a.milestone != "M0") else 1
        groups = plan_groups(a.milestone, want)
        instances = len(groups) if groups else 1
        for name in names:
            devs: list[Device] = []
            t_dev = time.time()
            try:
                devs = acquire_group(name, instances, a.window)
                run_group(devs, rep, info["debug"], a.milestone, only, groups)
            except Exception as e:
                rep.add(Result("D000", "设备准备", FAIL, name, f"{type(e).__name__}: {e}"))
            finally:
                if not a.keep:
                    ths = [threading.Thread(target=d.kill) for d in devs if d.emulator_started]
                    for t in ths:
                        t.start()
                    for t in ths:
                        t.join()
                    if ths:
                        time.sleep(2)
                elapsed = round(time.time() - t_dev)
                rep.meta.setdefault("deviceSeconds", {})[name] = elapsed
                print(f"{name} 用时 {elapsed}s（预算 {budget}s）", flush=True)
                if only is None and elapsed > budget:
                    rep.add(Result("D000", f"设备用时超过 {budget} 秒", FAIL, name,
                                   f"本机用时 {elapsed}s，超过每台模拟器 {budget}s 的测试预算"))
    out = rep.write()
    print(f"\n结论：{rep.verdict()} {json.dumps(rep.counts(), ensure_ascii=False)}\n报告：{out}")
    return 0 if rep.verdict() == "通过" else 1


def cmd_static(a):
    rep = Report(a.milestone, "static")
    info = build(rep, skip=a.no_build)
    static_checks(rep, a.milestone, info)
    out = rep.write()
    print(f"\n结论：{rep.verdict()}\n报告：{out}")
    return 0 if rep.verdict() == "通过" else 1


def cmd_device(a):
    rep = Report(a.milestone, f"device-{a.name}")
    rep.meta["formal"] = False
    from mla.base import PROJECT
    dbg = PROJECT / spec("project")["gradle"]["debugApk"]
    if a.milestone != "M0" and not dbg.exists():
        print(f"没有 {dbg}，先构建")
        return 2
    dev = Device(a.serial, a.name)
    exp = MATRIX["devices"].get(a.name, {}).get("api")
    if exp and dev.api != exp:
        print(f"{a.name} 应为 API {exp}，{a.serial} 是 API {dev.api}")
        return 2
    t0 = time.time()
    run_on(dev, rep, dbg, a.milestone, set(a.only.split(",")) if a.only else None)
    rep.meta["deviceSeconds"] = {a.name: round(time.time() - t0)}
    out = rep.write()
    print(f"\n结论：{rep.verdict()}\n报告：{out}")
    return 0 if rep.verdict() == "通过" else 1


def cmd_boot(a):
    dev = acquire(a.name, a.window)
    print(f"{a.name} 已就绪：{dev.serial}（API {dev.api}），用完后执行 adb -s {dev.serial} emu kill")
    return 0


def cmd_selftest(a):
    """不需要桌面：安装全部夹具，检查命令、日志、发布、探针、直连、小组件绑定。"""
    dev = Device(a.serial, a.name or a.serial)
    guard_tv(dev)
    rep = Report("M0", f"selftest-{dev.name}")
    F = Fixtures(dev)
    F.cleanup()

    def res(id_, title, ok, msg=""):
        rep.add(Result(id_, title, PASS if ok else FAIL, dev.name, msg))

    try:
        for fid in F.index:
            ok, msg = dev.install(F.apk(fid))
            res("K01", f"安装夹具 {fid}", ok, "" if ok else msg[:200])
            if fid == "series":       # impostor 与 series 同包名，最后单独测
                F.uninstall(fid)
                continue
        F.install("series")
        F.cmd("dolphin", "clear_log")
        F.cmd("dolphin", "set_launcher_cert", "00")
        e = F.wait("dolphin", lambda e: e.get("event") == "cert_set", 8)
        F.cmd("dolphin", "set_launcher_cert", "")
        res("K02", "命令与日志通道", bool(e), json.dumps(e, ensure_ascii=False)[:200] if e else "")
        F.cmd("probe", "probe_handlers")
        h = F.wait("probe", lambda e: e.get("event") == "handlers", 15)
        res("K03", "探针 probe_handlers", bool(h), json.dumps(h, ensure_ascii=False)[:300] if h else "")
        if dev.api >= 26:
            for fid in ("dolphin", "spark", "cloud", "pulse", "prism", "aspect", "series"):
                F.cmd(fid, "publish")
                e = F.wait(fid, lambda e: e.get("event") == "published", 20)
                res("K04", f"{fid} 发布频道与节目", bool(e), json.dumps(e, ensure_ascii=False)[:200] if e else "")
            dev.sh(f"pm grant {F.pkg('probe')} android.permission.READ_TV_LISTINGS")
            F.cmd("probe", "clear_log")
            F.cmd("probe", "dump_tv")
            time.sleep(4)
            d = [e for e in F.log("probe") if e.get("event") == "dump_tv"]
            res("K05", "探针读取 TvProvider", len(d) >= 3, json.dumps([(e.get("table"), e.get("total"), e.get("error")) for e in d], ensure_ascii=False))
        dev.sh(f"appwidget grantbind --package {F.pkg('probe')} --user 0")
        F.cmd("probe", "clear_log")
        F.cmd("probe", "bind_widget", f"{F.pkg('sky')}/{FIX}.WeatherWidget")
        b = F.wait("probe", lambda e: e.get("event") == "bind_widget", 15)
        res("K06", "探针绑定第三方小组件", bool(b and b.get("ok")), json.dumps(b, ensure_ascii=False)[:200] if b else "")
        if b and b.get("ok"):
            u = F.wait("sky", lambda e: e.get("event") == "widget_update", 10)
            res("K07", "小组件 provider 记录 onUpdate", bool(u))
        F.cmd("probe", "clear_log")
        F.cmd("probe", "call_bridge", f"{F.pkg('series')}:hello:")
        c = F.wait("probe", lambda e: e.get("event") == "call_bridge", 10)
        s = F.wait("series", lambda e: e.get("event") == "bridge_call" and e.get("caller") == F.pkg("probe"), 10)
        res("K08", "直连 Provider 调用与记录", bool(c and s), json.dumps(s, ensure_ascii=False)[:200] if s else "")
    finally:
        if not a.keep:
            F.cleanup()
    out = rep.write()
    print(f"\n自检：{rep.verdict()}\n报告：{out}")
    return 0 if rep.verdict() == "通过" else 1


def main():
    ap = argparse.ArgumentParser(description="myLauncher 验收工具")
    sp = ap.add_subparsers(dest="cmd", required=True)
    k = sp.add_parser("kit")
    k.add_argument("action", choices=["verify", "freeze"])
    ac = sp.add_parser("accept")
    ac.add_argument("milestone", type=ms_arg)
    ac.add_argument("--devices", help="逗号分隔的设备 id（见 spec/matrix.json），默认按里程碑矩阵")
    ac.add_argument("--only", help="逗号分隔的检查编号")
    ac.add_argument("--no-build", action="store_true")
    ac.add_argument("--window", action="store_true", help="显示模拟器窗口")
    ac.add_argument("--keep", action="store_true", help="结束后不关闭本工具启动的模拟器")
    ac.add_argument("--force-devices", action="store_true", help=argparse.SUPPRESS)
    s = sp.add_parser("static")
    s.add_argument("milestone", type=ms_arg)
    s.add_argument("--no-build", action="store_true")
    d = sp.add_parser("device")
    d.add_argument("milestone", type=ms_arg)
    d.add_argument("--serial", required=True)
    d.add_argument("--name", required=True, help="spec/matrix.json 中的设备 id")
    d.add_argument("--only")
    bo = sp.add_parser("boot", help="启动矩阵中的一台模拟器并保持运行")
    bo.add_argument("name", choices=sorted(MATRIX["devices"]))
    bo.add_argument("--window", action="store_true")
    t = sp.add_parser("selftest")
    t.add_argument("--serial", required=True)
    t.add_argument("--name")
    t.add_argument("--keep", action="store_true")
    a = ap.parse_args()
    try:
        return {"kit": cmd_kit, "accept": cmd_accept, "static": cmd_static, "device": cmd_device, "selftest": cmd_selftest, "boot": cmd_boot}[a.cmd](a)
    except KeyboardInterrupt:
        return 2
    except RuntimeError as e:
        print(f"错误：{e}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
