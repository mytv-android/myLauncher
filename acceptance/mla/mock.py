"""主机上的模拟服务器：经 adb reverse 暴露给模拟器，记录全部请求。"""
from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


class MockServer:
    """routes: {路径: handler(req) -> (状态码, 头 dict, 正文 bytes)}；req 为 dict(method, path, query, headers, body)。"""

    def __init__(self, dev, routes: dict):
        self.dev, self.routes, self.requests = dev, routes, []
        owner = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _do(self):
                u = urlparse(self.path)
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n) if n and n < 64 << 20 else b""
                req = dict(method=self.command, path=u.path, query={k: v[0] for k, v in parse_qs(u.query).items()},
                           headers={k.lower(): v for k, v in self.headers.items()}, body=body, t=time.time())
                owner.requests.append(req)
                h = owner.routes.get(u.path)
                code, hdr, out = h(req) if h else (404, {}, b"not found")
                self.send_response(code)
                for k, v in hdr.items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(out)

            do_GET = do_POST = do_HEAD = _do

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.httpd.server_address[1]
        self.base = f"http://127.0.0.1:{self.port}"

    def __enter__(self):
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.dev.adb("reverse", f"tcp:{self.port}", f"tcp:{self.port}")
        return self

    def __exit__(self, *a):
        self.dev.adb("reverse", "--remove", f"tcp:{self.port}")
        self.httpd.shutdown()

    def hits(self, path):
        return [r for r in self.requests if r["path"] == path]


def json_route(obj, code=200):
    data = json.dumps(obj, ensure_ascii=False).encode()
    return lambda req: (code, {"Content-Type": "application/json; charset=utf-8"}, data)


def file_route(path, ctype="application/octet-stream"):
    data = open(path, "rb").read()
    return lambda req: (200, {"Content-Type": ctype}, data)


def open_meteo(temp=23.2, code=0, tmax=26.4, tmin=15.1):
    today = time.strftime("%Y-%m-%d")
    return {
        "latitude": 39.9, "longitude": 116.4, "timezone": "Asia/Shanghai", "utc_offset_seconds": 28800,
        "current": {"time": today + "T12:00", "interval": 900, "temperature_2m": temp, "weather_code": code, "is_day": 1},
        "current_units": {"temperature_2m": "°C", "weather_code": "wmo code"},
        "current_weather": {"time": today + "T12:00", "temperature": temp, "weathercode": code, "is_day": 1},
        "daily": {"time": [today], "weather_code": [code], "temperature_2m_max": [tmax], "temperature_2m_min": [tmin]},
        "daily_units": {"temperature_2m_max": "°C", "temperature_2m_min": "°C"},
        "hourly": {"time": [today + "T12:00"], "temperature_2m": [temp], "weather_code": [code]},
    }
