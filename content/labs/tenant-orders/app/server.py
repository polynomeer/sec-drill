"""Synthetic tenant-orders service for the SecDrill "tenant order leak" scenario (09, T07, T08).

All data is synthetic. HTTP plumbing, login and the access record live here and in app/data.py; the order logic a
learner may patch lives in app/orders.py and app/authz.py. Learners reach this app only over HTTP through the Lab
Gateway; no route serves the access record, the environment or files.
"""

import json
import secrets
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from app import orders
from app.data import USERS

TOKENS = {}

INDEX = """<!doctype html><meta charset="utf-8"><title>Synthetic Orders</title>
<h1>Synthetic Orders portal</h1>
<p>Synthetic training service. Sign in as <code>alice</code> / <code>lab-alice</code> (tenant Northwind).</p>
<ul>
<li><code>POST /api/login</code> with JSON <code>{"username": "...", "password": "..."}</code> returns a bearer token</li>
<li><code>GET /api/orders</code> lists your tenant's orders</li>
<li><code>GET /api/orders/&lt;id&gt;</code> shows one order</li>
</ul>
"""


def order_id(raw):
    return int(raw) if raw.isdigit() and len(raw) <= 9 else None


class Handler(BaseHTTPRequestHandler):
    server_version = "SyntheticOrders/1"
    sys_version = ""

    def log_message(self, fmt, *args):
        # Request lines may carry tokens or flags in query strings; nothing is logged.
        pass

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def caller_tenant(self):
        header = self.headers.get("Authorization", "")
        token = header[7:] if header.startswith("Bearer ") else ""
        return TOKENS.get(token)

    def do_GET(self):
        url = urlsplit(self.path)
        path = url.path
        query = {key: values[0] for key, values in parse_qs(url.query).items()}
        headers = {key.lower(): value for key, value in self.headers.items()}
        if path == "/":
            data = INDEX.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/healthz":
            return self.send_json(200, {"ok": True})
        tenant = self.caller_tenant()
        if path == "/api/orders":
            return self.send_json(*orders.list_orders(tenant, query, headers))
        for prefix, handler in (("/api/orders/", orders.read_order), ("/api/v1/orders/", orders.legacy_read_order)):
            if path.startswith(prefix):
                identifier = order_id(path[len(prefix):])
                if identifier is None:
                    return self.send_json(404, {"error": "not found"})
                return self.send_json(*handler(tenant, identifier, headers))
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if urlsplit(self.path).path != "/api/login":
            return self.send_json(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length") or 0)
        if length > 4096:
            return self.send_json(413, {"error": "too large"})
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self.send_json(400, {"error": "invalid JSON"})
        user = USERS.get(body.get("username")) if isinstance(body, dict) else None
        if user is None or not secrets.compare_digest(str(body.get("password", "")), user["password"]):
            return self.send_json(401, {"error": "invalid credentials"})
        token = secrets.token_urlsafe(24)
        TOKENS[token] = user["tenant"]
        self.send_json(200, {"token": token, "tenant": user["tenant"]})


def main():
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
