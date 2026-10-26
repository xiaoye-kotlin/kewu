# -*- coding: utf-8 -*-
# 课悟 AI 局域网调试代理（2026-10-24）
# 手机直连 api.modelarts-maas.com:443 被网络阻断，本机走系统代理可达。
# 本服务监听局域网，把 /v2/chat/completions 原样转发到 ModelArts。
# 仅用于开发调试；正式发布应改为业务后端代理。
import json
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

UPSTREAM = "https://api.modelarts-maas.com"
PORT = 8377


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path.split("?")[0] != "/v2/chat/completions":
            self._reply(404, {"error": "only /v2/chat/completions is proxied"})
            return
        try:
            n = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(n)
            req = urllib.request.Request(
                UPSTREAM + self.path, data=body, method="POST",
                headers={
                    "Content-Type": "application/json",
                    "Authorization": self.headers.get("Authorization", ""),
                },
            )
            with urllib.request.urlopen(req, timeout=110) as r:
                data = r.read()
                self._reply(r.status, raw=data)
        except Exception as e:  # noqa: BLE001
            self._reply(502, {"error": f"proxy upstream failed: {e}"})

    def do_GET(self):
        self._reply(200, {"ok": True, "service": "kewu-ai-proxy", "path": self.path})

    def _reply(self, code, obj=None, raw=None):
        data = raw if raw is not None else json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("[kewu-ai-proxy]", fmt % args, flush=True)


if __name__ == "__main__":
    print(f"[kewu-ai-proxy] listening on 0.0.0.0:{PORT} -> {UPSTREAM}", flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
