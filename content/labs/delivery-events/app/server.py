"""Synthetic delivery-events receiver for the SecDrill webhook Transfer (09, T13). Same idempotency concept as
webhook-receiver plus event ordering, in a different family. A captured signed event can be replayed and an older
event can be re-applied out of order. Patchable logic is app/verify.py and app/sequence.py. All data is synthetic."""

import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from app import sequence, verify
from app.data import apply_event, audit, ship, sign


def capture(shipment_id, seq, age, tamper):
    body = json.dumps({"shipmentId": shipment_id, "seq": seq, "timestamp": int(time.time()) - age, "event": "status"}).encode()
    signature = sign(body)
    if tamper:
        signature = ("0" if signature[0] != "0" else "1") + signature[1:]
    return {"body": body.decode(), "signature": signature}


class Handler(BaseHTTPRequestHandler):
    server_version = "SyntheticDelivery/1"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/healthz":
            return self.send_json(200, {"ok": True})
        if path == "/api/sample-event":
            query = parse_qs(urlsplit(self.path).query)
            shipment_id = (query.get("ship") or ["ship-1"])[0][:64]
            seq = min(int((query.get("seq") or ["1"])[0] or 1), 99)
            age = min(int((query.get("age") or ["0"])[0] or 0), 86400)
            return self.send_json(200, capture(shipment_id, seq, age, "tamper" in query))
        if path.startswith("/api/shipment/"):
            state = ship(path[len("/api/shipment/"):][:64])
            return self.send_json(200, {"status": state["status"], "lastSeq": state["lastSeq"], "applied": len(state["applied"]), "credits": state["credits"]})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if urlsplit(self.path).path != "/api/events":
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
            audit(event.get("shipmentId"), event.get("seq"), accepted=False, out_of_order=False, reason="expired")
            return self.send_json(400, {"error": "event too old"})
        state = ship(event["shipmentId"])
        should_apply, prior = sequence.decide(event["shipmentId"], event["seq"])
        if not should_apply:
            out_of_order = event["seq"] <= state["lastSeq"] and event["seq"] not in state["applied"]
            audit(event["shipmentId"], event["seq"], accepted=False, out_of_order=out_of_order, reason="rejected")
            return self.send_json(409 if out_of_order else 200, prior or {"status": state["status"], "applied": len(state["applied"]), "credits": state["credits"]})
        out_of_order = event["seq"] <= state["lastSeq"]
        result = apply_event(event)
        audit(event["shipmentId"], event["seq"], accepted=True, out_of_order=out_of_order, reason="applied")
        self.send_json(200, result)


def main():
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
