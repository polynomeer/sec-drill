"""Order handlers. Learner patch path. Each returns (status, body)."""

from app import authz
from app.data import ORDERS, audit, view


def list_orders(caller_tenant, query, headers):
    if caller_tenant is None:
        return 401, {"error": "login required"}
    caller_tenant = headers.get("x-tenant", caller_tenant)
    mine = [{"id": order_id, "item": order["item"]} for order_id, order in ORDERS.items() if authz.can_read(caller_tenant, order)]
    return 200, {"tenant": caller_tenant, "orders": mine}


def _read(caller_tenant, order_id, headers=None):
    if caller_tenant is not None and headers and headers.get("x-tenant"):
        caller_tenant = headers["x-tenant"]
    order = ORDERS.get(order_id)
    if caller_tenant is None:
        audit(None, order["tenant"] if order else None, order_id, 401)
        return 401, {"error": "login required"}
    if order is None or not authz.can_read(caller_tenant, order):
        audit(caller_tenant, order["tenant"] if order else None, order_id, 404)
        return 404, {"error": "not found"}
    audit(caller_tenant, order["tenant"], order_id, 200)
    return 200, view(order_id, order)


def read_order(caller_tenant, order_id, headers):
    return _read(caller_tenant, order_id, headers)


def legacy_read_order(caller_tenant, order_id, headers):
    """Old mobile-app route kept for compatibility; same rule as the main route."""
    return _read(caller_tenant, order_id, headers)
