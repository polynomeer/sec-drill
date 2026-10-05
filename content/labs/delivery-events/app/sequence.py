"""Event ordering and idempotency. Learner patch path. Returns (should_apply, prior_result)."""

from app.data import ship


def decide(shipment_id, seq):
    # Weakness: every event is applied, so replays and out-of-order (older) events corrupt the state.
    return True, None
