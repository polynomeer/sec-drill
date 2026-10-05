"""Authorization rules for invoices. Learner patch path."""


def can_read(caller_tenant, invoice):
    return caller_tenant is not None and invoice["tenant"] == caller_tenant
