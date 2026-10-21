# -*- coding: utf-8 -*-
"""Временный mock PostgREST для on-device сверки экрана «Рейтинг» (story 7.7).

Имитирует контракт LeaderboardApi (Supabase REST):
  POST /rest/v1/profiles        -> 201 (upsert ника)
  POST /rest/v1/aggregates      -> 201 (публикация агрегатов; после НЕПУСТОЙ
                                     публикации /top начинает отдавать строки)
  GET  /rest/v1/top?...         -> 200 [] до первой публикации, далее 4 строки
                                   (данные утверждённого макета 08-rating)
  POST /rest/v1/invites         -> 201 {"code": "AB12CD34"}
  POST /rest/v1/rpc/accept_invite -> 200 {}

Только для QA-прогона: supabase.url в local.properties временно указывает на
http://10.0.2.2:<port>. Не коммитить, не использовать вне сверки 7.7.
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8791
PUBLISHED = {"any": False}

TOP_ROWS = [
    {"nickname": "FogLady", "distance_meters": 41200.0, "fraction": 0.41},
    {"nickname": "walkspb", "distance_meters": 38500.0, "fraction": 0.38},
    {"nickname": "olya_walks", "distance_meters": 17000.0, "fraction": 0.17},
    {"nickname": "пешеход92", "distance_meters": 16000.0, "fraction": 0.16},
]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(fmt % args, flush=True)

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        if self.path.startswith("/rest/v1/profiles"):
            print(f"POST profiles: {raw}", flush=True)
            self._send(201, [{}])
        elif self.path.startswith("/rest/v1/aggregates"):
            print(f"POST aggregates: {raw}", flush=True)
            # Любая публикация агрегатов (даже пустых — прогулок ещё не было)
            # переводит мок в состояние «участник в рейтинге».
            PUBLISHED["any"] = True
            self._send(201, [{}])
        elif self.path.startswith("/rest/v1/invites"):
            self._send(201, [{"code": "AB12CD34"}])
        elif self.path.startswith("/rest/v1/rpc/accept_invite"):
            self._send(200, {})
        else:
            self._send(404, {"error": "mock: unknown path " + self.path})

    def do_GET(self):
        if self.path.startswith("/rest/v1/top"):
            rows = TOP_ROWS if PUBLISHED["any"] else []
            print(f"GET top -> {len(rows)} rows", flush=True)
            self._send(200, rows)
        else:
            self._send(404, {"error": "mock: unknown path " + self.path})


if __name__ == "__main__":
    print(f"mock PostgREST на :{PORT} (10.0.2.2:{PORT} с эмулятора)", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
