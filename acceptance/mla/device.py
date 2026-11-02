"""设备：adb 操作、无障碍树、截图与颜色计算。"""
from __future__ import annotations

import io
import json
import re
import socket
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import numpy as np
from PIL import Image

from .base import ADB, EMULATOR, run

KEYS = {"UP": "KEYCODE_DPAD_UP", "DOWN": "KEYCODE_DPAD_DOWN", "LEFT": "KEYCODE_DPAD_LEFT", "RIGHT": "KEYCODE_DPAD_RIGHT",
        "OK": "KEYCODE_DPAD_CENTER", "BACK": "KEYCODE_BACK", "HOME": "KEYCODE_HOME", "MENU": "KEYCODE_MENU"}


# ================================================================ 无障碍树
class Node:
    __slots__ = ("a", "children", "parent")

    def __init__(self, a, parent=None):
        self.a, self.children, self.parent = a, [], parent

    rid = property(lambda s: s.a.get("resource-id", ""))
    text = property(lambda s: s.a.get("text", ""))
    desc = property(lambda s: s.a.get("content-desc", ""))
    pkg = property(lambda s: s.a.get("package", ""))
    cls = property(lambda s: s.a.get("class", ""))
    focused = property(lambda s: s.a.get("focused") == "true")

    @property
    def bounds(self):
        m = re.findall(r"-?\d+", self.a.get("bounds", "[0,0][0,0]"))
        return tuple(int(x) for x in m[:4])

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()

    def ancestors(self):
        p = self.parent
        while p is not None:
            yield p
            p = p.parent

    def texts(self):
        return [t for n in self.walk() for t in (n.text, n.desc) if t]

    def __repr__(self):
        return f"<{self.rid or self.cls} {self.bounds} f={self.focused} '{self.text[:20]}'>"


class UiTree:
    def __init__(self, xml: str):
        self.xml = xml
        root = ET.fromstring(xml)
        self.root = Node({}, None)
        self._build(root, self.root)
        self.nodes = list(self.root.walk())[1:]

    def _build(self, el, parent):
        for c in el:
            if c.tag != "node":
                continue
            n = Node(dict(c.attrib), parent)
            parent.children.append(n)
            self._build(c, n)

    def find(self, tag):
        return next((n for n in self.nodes if n.rid == tag), None)

    def prefix(self, p):
        return [n for n in self.nodes if n.rid.startswith(p)]

    def focused(self, pkg=None):
        return [n for n in self.nodes if n.focused and (pkg is None or n.pkg == pkg)]

    def packages(self):
        return {n.pkg for n in self.nodes if n.pkg}


# ================================================================ 颜色
def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _lin(v):
    v = v / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def rgb2lab(c):
    r, g, b = (_lin(x) for x in c)
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
    f = lambda t: t ** (1 / 3) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def lch(c):
    L, a, b = rgb2lab(hex2rgb(c) if isinstance(c, str) else c)
    import math
    return L, math.hypot(a, b), (math.degrees(math.atan2(b, a)) + 360) % 360


def hue_diff(h1, h2):
    d = abs(h1 - h2) % 360
    return min(d, 360 - d)


def delta_e(c1, c2):
    a = rgb2lab(hex2rgb(c1) if isinstance(c1, str) else c1)
    b = rgb2lab(hex2rgb(c2) if isinstance(c2, str) else c2)
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def contrast(c1, c2):
    def lum(c):
        r, g, b = (_lin(x) for x in (hex2rgb(c) if isinstance(c, str) else c))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    x, y = lum(c1), lum(c2)
    return (max(x, y) + 0.05) / (min(x, y) + 0.05)


def region(img: Image.Image, box):
    x1, y1, x2, y2 = (int(v) for v in box)
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(img.width, x2), min(img.height, y2)
    if x2 - x1 < 2 or y2 - y1 < 2:
        return None
    return np.asarray(img.crop((x1, y1, x2, y2)).convert("RGB"), dtype=np.float32)


def mean_color(img, box):
    a = region(img, box)
    return None if a is None else tuple(int(round(v)) for v in a.reshape(-1, 3).mean(0))


def mode_color(img, box, q=8):
    """出现最多的颜色（按 q 量化），用于避开文字取按钮底色。"""
    a = region(img, box)
    if a is None:
        return None
    px = (a.reshape(-1, 3) // q).astype(np.int32)
    keys = px[:, 0] * 65536 + px[:, 1] * 256 + px[:, 2]
    vals, cnt = np.unique(keys, return_counts=True)
    k = vals[cnt.argmax()]
    sel = a.reshape(-1, 3)[keys == k]
    return tuple(int(round(v)) for v in sel.mean(0))


def hf_energy(img, box):
    """高频能量：灰度拉普拉斯绝对值均值。棋盘格未模糊时很高，模糊后接近 0。"""
    a = region(img, box)
    if a is None:
        return None
    g = a @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    lap = np.abs(4 * g[1:-1, 1:-1] - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:])
    return float(lap.mean())


def std_color(img, box):
    a = region(img, box)
    return None if a is None else float(a.reshape(-1, 3).std(0).mean())


# ================================================================ 设备
class Device:
    def __init__(self, serial: str, name: str = ""):
        self.serial = serial
        self.name = name or serial
        self._api = None
        self._size = None
        self._density = None
        self.emulator_started = False

    # ---- adb
    def adb(self, *args, timeout=60, binary=False, input=None):
        return run([str(ADB), "-s", self.serial, *args], timeout=timeout, binary=binary, input=input)

    def sh(self, cmd: str, timeout=60) -> str:
        rc, out, err = self.adb("shell", cmd, timeout=timeout)
        return out.replace("\r\n", "\n")

    def prop(self, k):
        return self.sh(f"getprop {k}").strip()

    @property
    def api(self) -> int:
        if self._api is None:
            self._api = int(self.prop("ro.build.version.sdk") or 0)
        return self._api

    @property
    def size(self):
        if self._size is None:
            out = self.sh("wm size")
            m = re.findall(r"(\d+)x(\d+)", out)
            self._size = tuple(int(x) for x in m[-1]) if m else (1920, 1080)
        return self._size

    @property
    def density(self) -> float:
        if self._density is None:
            m = re.findall(r"(\d+)", self.sh("wm density"))
            self._density = int(m[-1]) / 160.0 if m else 2.0
        return self._density

    def dp(self, px) -> float:
        return px / self.density

    def px(self, dp) -> float:
        return dp * self.density

    def features(self) -> set:
        return {l.split(":", 1)[1].strip() for l in self.sh("pm list features").splitlines() if l.startswith("feature:")}

    # ---- 按键
    def key(self, k, long=False, wait=0.25):
        code = KEYS.get(k, k)
        self.sh(f"input keyevent {'--longpress ' if long else ''}{code}")
        if wait:
            time.sleep(wait)

    # ---- 包
    def install(self, apk, grant=False, timeout=240):
        args = ["install", "-r", "-t"] + (["-g"] if grant else []) + [str(apk)]
        rc, out, err = self.adb(*args, timeout=timeout)
        ok = "Success" in out
        if not ok and "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in (out + err):
            m = re.search(r"Package (\S+)", out + err)
            if m:
                self.uninstall(m.group(1))
                rc, out, err = self.adb(*args, timeout=timeout)
                ok = "Success" in out
        return ok, (out + err).strip()

    def uninstall(self, pkg):
        return "Success" in self.adb("uninstall", pkg)[1]

    def installed(self, pkg) -> bool:
        return f"package:{pkg}" in self.sh(f"pm list packages {pkg}").split()

    def packages(self, prefix=""):
        return [l.split(":", 1)[1].strip() for l in self.sh(f"pm list packages {prefix}").splitlines() if l.startswith("package:")]

    def pid(self, pkg):
        out = self.sh(f"pidof {pkg}").strip()
        if out:
            return out.split()[0]
        for l in self.sh("ps -A" if self.api >= 26 else "ps").splitlines():
            if l.strip().endswith(" " + pkg):
                return l.split()[1]
        return ""

    # ---- 活动
    def resumed(self) -> str:
        out = self.sh("dumpsys activity activities", timeout=30)
        for pat in (r"topResumedActivity[=:].*?\{[^ ]+ [^ ]+ ([^ }]+)", r"mResumedActivity[=:].*?\{[^ ]+ [^ ]+ ([^ }]+)",
                    r"ResumedActivity: ActivityRecord\{[^ ]+ [^ ]+ ([^ }]+)"):
            m = re.search(pat, out)
            if m:
                comp = m.group(1)
                if "/" in comp and comp.split("/")[1].startswith("."):
                    p, c = comp.split("/")
                    comp = f"{p}/{p}{c}"
                return comp
        return ""

    def resumed_pkg(self) -> str:
        return self.resumed().split("/")[0]

    def home_component(self) -> str:
        out = self.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" if self.api >= 24
                      else "pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME")
        lines = [l.strip() for l in out.splitlines() if "/" in l]
        return lines[-1] if lines else ""

    def set_home(self, component: str) -> bool:
        pkg = component.split("/")[0]
        if self.api >= 29:
            self.sh(f"cmd role add-role-holder --user 0 android.app.role.HOME {pkg}")
        if self.api >= 24:
            self.sh(f"cmd package set-home-activity --user 0 {component}")
        return self.home_component().split("/")[0] == pkg

    # ---- 界面
    def dump(self, tries=4) -> UiTree:
        last = ""
        for i in range(tries):
            self.sh("rm -f /data/local/tmp/mla_ui.xml")
            out = self.sh("uiautomator dump /data/local/tmp/mla_ui.xml", timeout=40)
            xml = self.sh("cat /data/local/tmp/mla_ui.xml", timeout=30)
            if xml.lstrip().startswith("<?xml") or xml.lstrip().startswith("<hierarchy"):
                return UiTree(xml[xml.index("<"):])
            last = out.strip()
            time.sleep(1.0 + i)
        raise RuntimeError(f"uiautomator dump 失败：{last}")

    def screencap(self) -> Image.Image:
        rc, out, err = self.adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        return Image.open(io.BytesIO(out)).convert("RGB")

    def logcat_clear(self):
        self.adb("logcat", "-c")

    def crashes(self, pkg) -> list[str]:
        rc, out, err = self.adb("logcat", "-d", "-b", "crash", "-v", "brief", timeout=30)
        rc2, out2, _ = self.adb("logcat", "-d", "-s", "AndroidRuntime:E", "ActivityManager:W", "-v", "brief", timeout=30)
        lines = (out + "\n" + out2).splitlines()
        hits = []
        for i, l in enumerate(lines):
            if ("FATAL EXCEPTION" in l or "ANR in" in l) and any(pkg in x for x in lines[i:i + 4]):
                hits.append("\n".join(lines[i:i + 8]))
        return hits

    # ---- content
    def content_query(self, uri, col="line") -> list[str]:
        out = self.sh(f"content query --uri {uri}", timeout=30)
        rows = []
        for l in out.splitlines():
            m = re.match(rf"Row: \d+ {col}=(.*)$", l.strip())
            if m:
                rows.append(m.group(1))
        return rows

    def content_call(self, uri, method, arg=None) -> str:
        a = f" --arg '{arg}'" if arg is not None else ""
        return self.sh(f"content call --uri {uri} --method {method}{a}", timeout=30)

    # ---- 模拟器
    @staticmethod
    def free_port():
        for p in range(5562, 5600, 2):
            with socket.socket() as s:
                try:
                    s.bind(("127.0.0.1", p))
                    s.close()
                    with socket.socket() as s2:
                        s2.bind(("127.0.0.1", p + 1))
                    return p
                except OSError:
                    continue
        raise RuntimeError("没有空闲的模拟器端口")

    @classmethod
    def boot(cls, avd: str, name: str, window=False, timeout=600) -> "Device":
        import subprocess
        port = cls.free_port()
        args = [str(EMULATOR), "-avd", avd, "-port", str(port), "-no-snapshot-save", "-no-boot-anim", "-no-audio"]
        if not window:
            args.append("-no-window")
        subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
        d = cls(f"emulator-{port}", name)
        d.emulator_started = True
        t0 = time.time()
        while time.time() - t0 < timeout:
            rc, out, _ = run([str(ADB), "-s", d.serial, "shell", "getprop sys.boot_completed"], timeout=15)
            if out.strip() == "1":
                time.sleep(8)
                d.sh("input keyevent KEYCODE_WAKEUP")
                d.sh("settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1")
                d.sh("svc power stayon true")
                return d
            time.sleep(4)
        raise RuntimeError(f"{avd} 启动超时")

    def kill(self):
        if self.emulator_started:
            self.adb("emu", "kill", timeout=30)
