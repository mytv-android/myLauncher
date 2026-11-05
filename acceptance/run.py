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
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mla.base import (ADB, APKSIGNER, FAIL, KIT, PASS, SKIP, Report, Result, kit_freeze, kit_verify, run, spec)  # noqa: E402
from mla import checks, checks0, checks3, checks33, checks45  # noqa: E402,F401  注册检查
from mla.checks import Ctx, run_device  # noqa: E402
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


def acquire(name, window) -> Device:
    d = MATRIX["devices"][name]
    serial = running_emulators().get(d["avd"])
    if serial:
        dev = Device(serial, name)
        print(f"使用已运行的 {d['avd']}（{serial}）")
    else:
        print(f"启动模拟器 {d['avd']} …", flush=True)
        dev = Device.boot(d["avd"], name, window=window)
    if dev.api != d["api"]:
        raise RuntimeError(f"{name}：期望 API {d['api']}，实际 {dev.api}")
    return dev


def guard_tv(dev: Device):
    if "android.software.leanback" not in dev.features():
        raise RuntimeError(f"{dev.serial} 不是电视设备（无 android.software.leanback），拒绝在其上操作")


def apk_cert(apk) -> str:
    rc, out, err = run([str(APKSIGNER), "verify", "--print-certs", str(apk)], timeout=120)
    m = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", out)
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


def prepare(dev: Device, rep: Report, dbg: Path, ms: str) -> tuple[Ctx, str]:
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
    Fixtures(dev).cleanup()
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
        ctx.L.start()
        ctx.L.pref("onboarding_done", "true")
        ctx.L.pref("ambient_idle_s", "0")
        ctx.L.stop()
        ctx.L.start()
        if ms >= "M3":
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
        Fixtures(dev).cleanup()
        dev.uninstall(PKG)
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
    t0 = time.time()
    ctx, orig = prepare(dev, rep, dbg, ms)
    try:
        run_device(ctx, only=only)
    finally:
        teardown(dev, orig)
    rep.meta.setdefault("deviceSeconds", {})[dev.name] = round(time.time() - t0)


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
        for name in names:
            dev = None
            try:
                dev = acquire(name, a.window)
                run_on(dev, rep, info["debug"], a.milestone, only)
            except Exception as e:
                rep.add(Result("D000", "设备准备", FAIL, name, f"{type(e).__name__}: {e}"))
            finally:
                if dev and dev.emulator_started and not a.keep:
                    dev.kill()
                    time.sleep(5)
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
    run_on(dev, rep, dbg, a.milestone, set(a.only.split(",")) if a.only else None)
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
