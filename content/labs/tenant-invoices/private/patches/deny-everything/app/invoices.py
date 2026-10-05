"""Invoice handlers. Learner patch path. Each returns (status, body)."""

from app import authz
from app.data import INVOICES, audit, view


def list_invoices(caller_tenant, query, headers):
    if caller_tenant is None:
        return 401, {"error": "login required"}
    mine = [{"id": iid, "amount": inv["amount"]} for iid, inv in INVOICES.items() if authz.can_read(caller_tenant, inv)]
    return 200, {"tenant": caller_tenant, "invoices": mine}


def _read(caller_tenant, invoice_id):
    invoice = INVOICES.get(invoice_id)
    if caller_tenant is None:
        audit(None, invoice["tenant"] if invoice else None, invoice_id, 401)
        return 401, {"error": "login required"}
    if invoice is None or not authz.can_read(caller_tenant, invoice):
        audit(caller_tenant, invoice["tenant"] if invoice else None, invoice_id, 404)
        return 404, {"error": "not found"}
    audit(caller_tenant, invoice["tenant"], invoice_id, 200)
    return 200, view(invoice_id, invoice)


def read_invoice(caller_tenant, invoice_id, headers):
    return _read(caller_tenant, invoice_id)


def legacy_read_invoice(caller_tenant, invoice_id, headers):
    return _read(caller_tenant, invoice_id)
