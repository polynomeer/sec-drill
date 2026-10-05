"""Synthetic shipment state and the access record for the webhook Transfer (ordered events). Not patchable.

State is keyed by shipmentId so independent deliveries never interfere."""

import hashlib
import hmac
import json
import os
import time

AUDIT_PATH = "/tmp/secdrill/audit.jsonl"
SECRET = os.environ.pop("SECDRILL_DELIVERY_SECRET", "synthetic-delivery-secret").encode()
FLAG = os.environ.pop("SECDRILL_FLAG_EVENT_REORDER", "")

SHIPS = {}
STATUSES = {1: "created", 2: "packed", 3: "shipped", 4: "refunded"}


def ship(shipment_id):
    return SHIPS.setdefault(shipment_id, {"lastSeq": 0, "status": "new", "applied": [], "credits": 0})


def sign(body):
    return hmac.new(SECRET, body, hashlib.sha256).hexdigest()


def audit(shipment_id, seq, accepted, out_of_order, reason):
    os.makedirs(os.path.dirname(AUDIT_PATH), exist_ok=True)
    record = {"at": int(time.time()), "route": "delivery-event", "shipmentId": shipment_id, "seq": seq,
              "accepted": accepted, "outOfOrder": out_of_order, "reason": reason}
    with open(AUDIT_PATH, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")


def apply_event(event):
    state = ship(event["shipmentId"])
    seq = event["seq"]
    state["applied"].append(seq)
    state["lastSeq"] = max(state["lastSeq"], seq)
    state["status"] = STATUSES.get(seq, state["status"])
    if STATUSES.get(seq) == "refunded":
        state["credits"] += 1
    note = "double-refund " + FLAG if state["credits"] > 1 else "ok"
    return {"status": state["status"], "applied": len(state["applied"]), "credits": state["credits"], "note": note}
