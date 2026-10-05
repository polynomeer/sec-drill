from app.data import ship
def decide(shipment_id, seq):
    state = ship(shipment_id)
    if seq in state["applied"]:
        return False, None
    return True, None
