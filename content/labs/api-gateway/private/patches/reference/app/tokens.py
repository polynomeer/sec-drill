"""Token resolution. Learner patch path. Returns the granted scopes, or None if the token may not be used."""

from app.data import TOKENS


def granted_scopes(token, headers):
    record = TOKENS.get(token)
    if record is None or record["revoked"]:
        return None
    return record["scopes"]
