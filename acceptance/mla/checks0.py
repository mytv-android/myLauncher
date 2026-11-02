"""M0 设备事实：探针独立测量，与实现方 docs/m0/m0-results.json 对照。"""
from __future__ import annotations

import json
import time

from .base import PROJECT, CheckFailed, Skip
from .checks import check, need
from .launcher import Fixtures

P = Fixtures.pkg


def report_device(ctx) -> dict:
    p = PROJECT / "docs" / "m0" / "m0-results.json"
    if not p.exists():
        raise CheckFailed("docs/m0/m0-results.json 不存在")
    d = json.loads(p.read_text(encoding="utf-8"))
    dev = (d.get("devices") or {}).get(ctx.dev.name)
    if dev is None:
        raise CheckFailed(f"M0 报告没有设备 {ctx.dev.name} 的数据（devices.{ctx.dev.name}）")
    return dev


def probe(ctx, cmd, arg=None, event=None, timeout=20):
    ctx.F.cmd("probe", "clear_log")
    ctx.F.cmd("probe", cmd, arg)
    e = ctx.F.wait("probe", lambda x: x.get("event") == (event or cmd), timeout)
    need(e, f"探针没有记录 {event or cmd}")
    return e


def compare(ctx, key, measured, claimed):
    ctx.data[key] = {"measured": measured, "report": claimed}
    return None if measured == claimed else f"{key}：实测 {measured}，报告 {claimed}"


@check("P01", "设备事实：系统界面处理者、特性、TvProvider（与 M0 报告一致）", "M0", until="M0")
def p01(ctx):
    ctx.F.install("probe")
    h = probe(ctx, "probe_handlers", event="handlers")
    rep = report_device(ctx)
    f = h.get("features") or {}
    measured = {
        "appwidgetBindHandler": bool(h.get("appwidgetBind")),
        "requestChannelBrowsableHandlers": sorted(h.get("requestChannelBrowsable") or []),
        "tvProvider": bool(h.get("tvProvider")),
        "leanback": bool(f.get("android.software.leanback")),
        "gtv": bool(f.get("com.google.android.feature.AMATI_EXPERIENCE")),
    }
    p = ctx.rep.path(ctx.dev.name, "P01-handlers.json")
    p.write_text(json.dumps(h, ensure_ascii=False, indent=1), encoding="utf-8")
    ctx.ev.append(ctx.rep.rel(p))
    diffs = [compare(ctx, k, v, sorted(rep.get(k) or []) if isinstance(v, list) else rep.get(k)) for k, v in measured.items()]
    diffs = [x for x in diffs if x]
    need(not diffs, "；".join(diffs))


@check("P02", "设备事实：第三方应用持 READ_TV_LISTINGS 能读到其他应用的哪些行", "M0", until="M0")
def p02(ctx):
    if ctx.dev.api < 26:
        raise Skip("API < 26")
    ctx.F.install("probe", "dolphin", "spark")
    ctx.dev.sh(f"pm grant {P('probe')} android.permission.READ_TV_LISTINGS")
    for f in ("dolphin", "spark"):
        ctx.F.cmd(f, "publish")
        need(ctx.F.wait(f, lambda e: e.get("event") == "published", 20), f"{f} 没有 published")
    time.sleep(2)
    ctx.F.cmd("probe", "clear_log")
    ctx.F.cmd("probe", "dump_tv")
    time.sleep(4)
    log = ctx.F.log("probe")
    dumps = [e for e in log if e.get("event") == "dump_tv"]
    sels = [e for e in log if e.get("event") == "dump_tv_selection"]
    need(len(dumps) >= 3, f"dump_tv 只记录了 {len(dumps)} 张表")
    other = {}
    for e in dumps:
        bp = e.get("byPackage") or {}
        other[e["table"]] = sum(v.get("count", 0) for k, v in bp.items() if k in (P("dolphin"), P("spark")))
    p = ctx.rep.path(ctx.dev.name, "P02-dump_tv.json")
    p.write_text(json.dumps({"dumps": dumps, "selection": sels}, ensure_ascii=False, indent=1), encoding="utf-8")
    ctx.ev.append(ctx.rep.rel(p))
    rep = report_device(ctx).get("readTvListings") or {}
    diffs = [compare(ctx, "readTvListings.otherSearchableRows", other, rep.get("otherSearchableRows")),
             compare(ctx, "readTvListings.selectionSecurityException", any(s.get("securityException") for s in sels),
                     rep.get("selectionSecurityException"))]
    diffs = [x for x in diffs if x]
    need(not diffs, "；".join(diffs))


@check("P03", "设备事实：appwidget grantbind 后第三方宿主能直接绑定", "M0", until="M0")
def p03(ctx):
    ctx.F.install("probe", "sky")
    pkg = P("probe")
    ctx.dev.sh(f"appwidget revokebind --package {pkg} --user 0")
    before = probe(ctx, "bind_widget", f"{P('sky')}/com.github.mytv.mlfixture.WeatherWidget", event="bind_widget")
    ctx.dev.sh(f"appwidget grantbind --package {pkg} --user 0")
    after = probe(ctx, "bind_widget", f"{P('sky')}/com.github.mytv.mlfixture.WeatherWidget", event="bind_widget")
    ctx.data.update(before=before, after=after)
    works = (not before.get("ok")) and bool(after.get("ok"))
    d = compare(ctx, "grantbindWorks", works, report_device(ctx).get("grantbindWorks"))
    need(not d, d)


@check("P04", "设备事实：CHANNEL_BROWSABLE_REQUESTED 是否送达第三方接收器", "M0", until="M0")
def p04(ctx):
    if ctx.dev.api < 26:
        raise Skip("API < 26")
    ctx.F.install("probe", "spark")
    ctx.F.cmd("probe", "clear_log")
    ctx.F.cmd("spark", "publish")
    need(ctx.F.wait("spark", lambda e: e.get("event") == "published", 20), "spark 没有 published")
    ctx.F.cmd("spark", "request_browsable", "spark.weekly")
    e = ctx.F.wait("probe", lambda x: x.get("event") == "browsable_requested", 10)
    ctx.data["event"] = e
    d = compare(ctx, "browsableRequestedDeliveredToThirdParty", bool(e), report_device(ctx).get("browsableRequestedDeliveredToThirdParty"))
    need(not d, d)
