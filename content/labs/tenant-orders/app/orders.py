"""Order handlers. Learner patch path. Each returns (status, body)."""

from app import authz
from app.data import ORDERS, audit, view


def list_orders(caller_tenant, query, headers):
    if caller_tenant is None:
        return 401, {"error": "login required"}
    # Weakness: the client may choose the tenant to list.
    tenant = query.get("tenant", caller_tenant)
    mine = [{"id": order_id, "item": order["item"]} for order_id, order in ORDERS.items() if order["tenant"] == tenant]
    return 200, {"tenant": tenant, "orders": mine}


def read_order(caller_tenant, order_id, headers):
    order = ORDERS.get(order_id)
    if caller_tenant is None:
        audit(None, order["tenant"] if order else None, order_id, 401)
        return 401, {"error": "login required"}
    if order is None:
        audit(caller_tenant, None, order_id, 404)
        return 404, {"error": "not found"}
    if not authz.can_read(caller_tenant, order):
        audit(caller_tenant, order["tenant"], order_id, 404)
        return 404, {"error": "not found"}
    audit(caller_tenant, order["tenant"], order_id, 200)
    return 200, view(order_id, order)


def legacy_read_order(caller_tenant, order_id, headers):
    """Old mobile-app route kept for compatibility."""
    order = ORDERS.get(order_id)
    if caller_tenant is None:
        return 401, {"error": "login required"}
    if order is None:
        return 404, {"error": "not found"}
    audit(caller_tenant, order["tenant"], order_id, 200)
    return 200, view(order_id, order)
