"""Webhook authenticity checks. Learner patch path."""

import time

from app.data import sign

# Deliveries older than this many seconds must be rejected as stale (replay protection).
FRESHNESS_SECONDS = 300


def signature_ok(raw, provided):
    import hmac
    return hmac.compare_digest(sign(raw), provided or "")


def fresh(timestamp):
    # Weakness: the delivery timestamp is never checked, so a captured delivery can be replayed forever.
    return True
