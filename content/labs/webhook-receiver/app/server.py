"""Synthetic webhook receiver for the SecDrill "webhook replay" scenario (09, T13).

All data is synthetic. The intended weakness: a captured, validly-signed delivery can be replayed and is processed
again, double-crediting a synthetic payout. The signing secret is never served; the learner replays the capture at
GET /api/sample-delivery. Patchable logic is app/verify.py and app/dedup.py. Learners reach this app only over HTTP.
"""

import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from app import dedup, verify
from app.data import LEDGER, apply_payout, audit, duplicate_count, sign

def capture(delivery_id, age, tamper):
    """A validly-signed delivery the attacker captured. `age` ages the timestamp; `tamper` corrupts the signature."""
    body = json.dumps({"deliveryId": delivery_id, "timestamp": int(time.time()) - age, "amount": 100, "event": "payout.succeeded"}).encode()
    signature = sign(body)
    if tamper:
        signature = ("0" if signature[0] != "0" else "1") + signature[1:]
    return {"body": body.decode(), "signature": signature}


class Handler(BaseHTTPRequestHandler):
    server_version = "SyntheticWebhook/1"
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

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/healthz":
            return self.send_json(200, {"ok": True})
        if path == "/api/sample-delivery":
            query = parse_qs(urlsplit(self.path).query)
            delivery_id = (query.get("id") or ["cap-1"])[0][:64]
            age = min(int((query.get("age") or ["0"])[0] or 0), 86400)
            return self.send_json(200, capture(delivery_id, age, "tamper" in query))
        if path == "/api/ledger":
            return self.send_json(200, {"payouts": len(LEDGER["payouts"]), "deliveries": sorted({p["deliveryId"] for p in LEDGER["payouts"]})})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if urlsplit(self.path).path != "/api/webhooks":
            return self.send_json(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length") or 0)
        if length > 8192:
            return self.send_json(413, {"error": "too large"})
        raw = self.rfile.read(length)
        if not verify.signature_ok(raw, self.headers.get("X-Signature")):
            return self.send_json(401, {"error": "invalid signature"})
        try:
            event = json.loads(raw)
        except ValueError:
            return self.send_json(400, {"error": "invalid JSON"})
        if not verify.fresh(event.get("timestamp", 0)):
            audit(event.get("deliveryId"), accepted=False, duplicate=False, reason="expired")
            return self.send_json(400, {"error": "delivery too old"})
        should_process, prior = dedup.decide(event["deliveryId"])
        if not should_process:
            audit(event["deliveryId"], accepted=False, duplicate=True, reason="idempotent")
            return self.send_json(200, prior or {"status": "duplicate", "payouts": len(LEDGER["payouts"])})
        from app.data import PROCESSED
        duplicate = event["deliveryId"] in PROCESSED or duplicate_count(event["deliveryId"]) > 0
        result = apply_payout(event)
        PROCESSED[event["deliveryId"]] = result
        audit(event["deliveryId"], accepted=True, duplicate=duplicate, reason="processed")
        self.send_json(200, result)


def main():
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
