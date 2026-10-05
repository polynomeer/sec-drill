"""Invoice handlers. Learner patch path. Each returns (status, body)."""

from app import authz
from app.data import INVOICES, audit, view


def list_invoices(caller_tenant, query, headers):
    if caller_tenant is None:
        return 401, {"error": "login required"}
    # Weakness: the client may choose which tenant's invoices to list.
    tenant = query.get("tenant", caller_tenant)
    mine = [{"id": iid, "amount": inv["amount"]} for iid, inv in INVOICES.items() if inv["tenant"] == tenant]
    return 200, {"tenant": tenant, "invoices": mine}


def read_invoice(caller_tenant, invoice_id, headers):
    invoice = INVOICES.get(invoice_id)
    if caller_tenant is None:
        audit(None, invoice["tenant"] if invoice else None, invoice_id, 401)
        return 401, {"error": "login required"}
    if invoice is None:
        audit(caller_tenant, None, invoice_id, 404)
        return 404, {"error": "not found"}
    if not authz.can_read(caller_tenant, invoice):
        audit(caller_tenant, invoice["tenant"], invoice_id, 404)
        return 404, {"error": "not found"}
    audit(caller_tenant, invoice["tenant"], invoice_id, 200)
    return 200, view(invoice_id, invoice)


def legacy_read_invoice(caller_tenant, invoice_id, headers):
    """Old reporting route kept for compatibility."""
    invoice = INVOICES.get(invoice_id)
    if caller_tenant is None:
        return 401, {"error": "login required"}
    if invoice is None:
        return 404, {"error": "not found"}
    audit(caller_tenant, invoice["tenant"], invoice_id, 200)
    return 200, view(invoice_id, invoice)
