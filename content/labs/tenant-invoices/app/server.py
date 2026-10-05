"""Synthetic invoices service for the SecDrill tenant-leak Transfer (09, T13). Same object-level authorization
concept as tenant-orders, in a different family: invoices keyed by UUID, different routes. All data is synthetic.
Patchable logic is app/invoices.py and app/authz.py."""

import json
import re
import secrets
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from app import invoices
from app.data import USERS

TOKENS = {}
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
INDEX = b"<!doctype html><meta charset=utf-8><title>Synthetic Invoices</title><h1>Synthetic Invoices</h1><p>dana / lab-dana (tenant umbra).</p>"


class Handler(BaseHTTPRequestHandler):
    server_version = "SyntheticInvoices/1"
    sys_version = ""

    def log_message(self, fmt, *args):
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
        return TOKENS.get(header[7:] if header.startswith("Bearer ") else "")

    def do_GET(self):
        url = urlsplit(self.path)
        path = url.path
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        headers = {k.lower(): v for k, v in self.headers.items()}
        if path == "/":
            self.send_response(200); self.send_header("Content-Type", "text/html"); self.send_header("Content-Length", str(len(INDEX))); self.end_headers(); self.wfile.write(INDEX); return
        if path == "/healthz":
            return self.send_json(200, {"ok": True})
        tenant = self.caller_tenant()
        if path == "/api/invoices":
            return self.send_json(*invoices.list_invoices(tenant, query, headers))
        for prefix, handler in (("/api/invoices/", invoices.read_invoice), ("/api/v2/invoices/", invoices.legacy_read_invoice)):
            if path.startswith(prefix):
                invoice_id = path[len(prefix):]
                if not UUID_RE.match(invoice_id):
                    return self.send_json(404, {"error": "not found"})
                return self.send_json(*handler(tenant, invoice_id, headers))
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
