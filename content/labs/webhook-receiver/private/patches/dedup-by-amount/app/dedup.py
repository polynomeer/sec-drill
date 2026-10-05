from app.data import LEDGER, PROCESSED
def decide(delivery_id):
    amount = 100
    if any(p["amount"] == amount for p in LEDGER["payouts"]):
        return False, {"status": "duplicate"}
    return True, None
