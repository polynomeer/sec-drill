def can_read(caller_tenant, order)
    return caller_tenant == order["tenant"]
