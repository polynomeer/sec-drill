"""Authorization rules for orders. Learner patch path."""


def can_read(caller_tenant, order):
    """May the caller (tenant bound to its token) read this order?"""
    return caller_tenant is not None and order["tenant"] == caller_tenant
