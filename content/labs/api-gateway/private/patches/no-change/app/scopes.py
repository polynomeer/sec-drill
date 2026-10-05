"""Scope enforcement. Learner patch path."""


def authorized(required_scope, granted_scopes, headers):
    # Weakness: any authenticated caller is allowed, whatever scope the resource requires.
    return True
