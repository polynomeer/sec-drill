"""Synthetic state and the server-side access record for the webhook-replay scenario. Not a learner patch path."""

import hashlib
import hmac
import json
import os
import time

AUDIT_PATH = "/tmp/secdrill/audit.jsonl"
# The shared webhook signing secret and the flag live only in the environment, never in a served route.
SECRET = os.environ.pop("SECDRILL_WEBHOOK_SECRET", "synthetic-webhook-secret").encode()
FLAG = os.environ.pop("SECDRILL_FLAG_DUPLICATE_PAYOUT", "")

# Processed delivery ids and the synthetic ledger a duplicate delivery would wrongly credit twice.
PROCESSED = {}
LEDGER = {"payouts": []}


def sign(body):
    return hmac.new(SECRET, body, hashlib.sha256).hexdigest()


def audit(delivery_id, accepted, duplicate, reason):
    os.makedirs(os.path.dirname(AUDIT_PATH), exist_ok=True)
    record = {"at": int(time.time()), "route": "webhook-deliver", "deliveryId": delivery_id,
              "accepted": accepted, "duplicate": duplicate, "reason": reason}
    with open(AUDIT_PATH, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")


def apply_payout(event):
    LEDGER["payouts"].append({"deliveryId": event["deliveryId"], "amount": event["amount"]})
    note = "double-paid " + FLAG if duplicate_count(event["deliveryId"]) > 1 else "ok"
    return {"status": "processed", "payouts": len(LEDGER["payouts"]), "note": note}


def duplicate_count(delivery_id):
    return sum(1 for payout in LEDGER["payouts"] if payout["deliveryId"] == delivery_id)
