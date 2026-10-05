import hmac
from app.data import sign
def signature_ok(raw, provided):
    return hmac.compare_digest(sign(raw), provided or "")
def fresh(timestamp):
    return False
