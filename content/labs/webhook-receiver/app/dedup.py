"""Delivery idempotency. Learner patch path. Returns (should_process, prior_result)."""

from app.data import PROCESSED


def decide(delivery_id):
    # Weakness: every delivery is processed, so a replayed delivery is credited again.
    return True, None
