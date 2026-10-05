"""Scope enforcement. Learner patch path."""


def authorized(required_scope, granted_scopes, headers):
    # The resource's required scope must be one the token was actually granted; client headers are ignored.
    return required_scope in (granted_scopes or [])
