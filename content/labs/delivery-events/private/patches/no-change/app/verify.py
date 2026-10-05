"""Delivery-event authenticity checks. Learner patch path."""

import hmac
import time

from app.data import sign

FRESHNESS_SECONDS = 300


def signature_ok(raw, provided):
    return hmac.compare_digest(sign(raw), provided or "")


def fresh(timestamp):
    # Weakness: a captured event's timestamp is never checked.
    return True
