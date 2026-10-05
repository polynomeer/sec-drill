"""Event ordering and idempotency. Learner patch path. Returns (should_apply, prior_result)."""

from app.data import ship


def decide(shipment_id, seq):
    state = ship(shipment_id)
    if seq in state["applied"]:
        return False, None            # already applied: idempotent no-op
    if seq <= state["lastSeq"]:
        return False, None            # older than the current position: out of order, reject
    return True, None
