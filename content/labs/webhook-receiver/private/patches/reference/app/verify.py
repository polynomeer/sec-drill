"""Webhook authenticity checks. Learner patch path."""

import hmac
import time

from app.data import sign

FRESHNESS_SECONDS = 300


def signature_ok(raw, provided):
    return hmac.compare_digest(sign(raw), provided or "")


def fresh(timestamp):
    # Reject deliveries older than the freshness window so a captured delivery cannot be replayed later.
    return isinstance(timestamp, (int, float)) and (time.time() - timestamp) <= FRESHNESS_SECONDS
