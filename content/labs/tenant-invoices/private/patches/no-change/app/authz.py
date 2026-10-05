"""Authorization rules for invoices. Learner patch path."""


def can_read(caller_tenant, invoice):
    # Weakness: any logged-in caller may read any invoice, whatever tenant owns it.
    return caller_tenant is not None
