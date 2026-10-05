"""Delivery idempotency. Learner patch path. Returns (should_process, prior_result)."""

from app.data import PROCESSED


def decide(delivery_id):
    # Process each delivery id once; a repeat returns the original result without crediting again.
    if delivery_id in PROCESSED:
        return False, PROCESSED[delivery_id]
    return True, None
