"""Principal resolution. Learner patch path. Returns the principal, or None if the token may not be used."""

from app.data import PRINCIPALS


def principal(token, headers):
    record = PRINCIPALS.get(token)
    if record is None:
        return None
    # Weakness: a disabled grant is still accepted.
    return record
