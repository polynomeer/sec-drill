def can_read(caller_tenant, order):
    return caller_tenant is not None and order["tenant"] == caller_tenant
