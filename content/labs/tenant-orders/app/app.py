"""Synthetic tenant-orders service for the SecDrill "tenant order leak" CTF (09, T07).

All data is synthetic. The intended weakness: GET /api/orders/<id> checks that the caller is logged in but not that
the order belongs to the caller's tenant. One order of the other tenant carries the Session flag.

Every order read is appended to a server-side access record (AUDIT_PATH) from facts the server decides (the tenant
bound to the token, the tenant that owns the order, the response status). The runner's collector reads it to confirm
the objective independently of anything the learner reports. Learners reach this app only over HTTP through the Lab
Gateway; no route serves the access record, the environment or files.
"""

import json
import os
import secrets
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AUDIT_PATH = "/tmp/secdrill/audit.jsonl"
FLAG = os.environ.pop("SECDRILL_FLAG_CROSS_TENANT_ORDER", "")

TENANTS = {"northwind": "Northwind Synthetic Traders", "contoso": "Contoso Synthetic Retail"}
USERS = {"alice": {"password": "lab-alice", "tenant": "northwind"}}
ORDERS = {
    1001: {"tenant": "northwind", "item": "Synthetic paper, 10 boxes", "total": 12000, "note": "Deliver to dock 2"},
    1002: {"tenant": "northwind", "item": "Synthetic toner", "total": 8900, "note": "Monthly restock"},
    1003: {"tenant": "contoso", "item": "Synthetic server rack", "total": 410000, "note": "Confidential contract ref " + FLAG},
    1004: {"tenant": "contoso", "item": "Synthetic cables", "total": 3200, "note": "Rush order"},
}
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


def audit(actor_tenant, resource_tenant, order_id, status):
    os.makedirs(os.path.dirname(AUDIT_PATH), exist_ok=True)
    record = {"at": int(time.time()), "route": "order-read", "actorTenant": actor_tenant,
              "resourceTenant": resource_tenant, "orderId": order_id, "status": status}
    with open(AUDIT_PATH, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")


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
        path = self.path.split("?", 1)[0]
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
            if tenant is None:
                return self.send_json(401, {"error": "login required"})
            mine = [{"id": order_id, "item": order["item"]} for order_id, order in ORDERS.items() if order["tenant"] == tenant]
            return self.send_json(200, {"tenant": tenant, "orders": mine})
        if path.startswith("/api/orders/"):
            raw_id = path[len("/api/orders/"):]
            if not raw_id.isdigit() or len(raw_id) > 9:
                return self.send_json(404, {"error": "not found"})
            order_id = int(raw_id)
            order = ORDERS.get(order_id)
            if tenant is None:
                audit(None, order["tenant"] if order else None, order_id, 401)
                return self.send_json(401, {"error": "login required"})
            if order is None:
                audit(tenant, None, order_id, 404)
                return self.send_json(404, {"error": "not found"})
            # Intended weakness: no check that order["tenant"] == tenant.
            audit(tenant, order["tenant"], order_id, 200)
            return self.send_json(200, {"id": order_id, "tenant": order["tenant"], "tenantName": TENANTS[order["tenant"]],
                                        "item": order["item"], "total": order["total"], "note": order["note"]})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/api/login":
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


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
