"""Synthetic data and the server-side access record for the tenant-orders scenario. Not a learner patch path."""

import json
import os
import time

AUDIT_PATH = "/tmp/secdrill/audit.jsonl"
FLAG = os.environ.pop("SECDRILL_FLAG_CROSS_TENANT_ORDER", "")

TENANTS = {"northwind": "Northwind Synthetic Traders", "contoso": "Contoso Synthetic Retail"}
USERS = {"alice": {"password": "lab-alice", "tenant": "northwind"}}
ORDERS = {
    1001: {"tenant": "northwind", "item": "Synthetic paper, 10 boxes", "total": 12000, "note": "Deliver to dock 2"},
    1002: {"tenant": "northwind", "item": "Synthetic toner", "total": 8900, "note": "Monthly restock"},
    1003: {"tenant": "contoso", "item": "Synthetic server rack", "total": 410000, "note": "Confidential contract ref " + FLAG},
    1004: {"tenant": "contoso", "item": "Synthetic cables", "total": 3200, "note": "Rush order"},
}


def audit(actor_tenant, resource_tenant, order_id, status):
    """Facts the server decided: the caller's token tenant, the owning tenant and the response status."""
    os.makedirs(os.path.dirname(AUDIT_PATH), exist_ok=True)
    record = {"at": int(time.time()), "route": "order-read", "actorTenant": actor_tenant,
              "resourceTenant": resource_tenant, "orderId": order_id, "status": status}
    with open(AUDIT_PATH, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")


def view(order_id, order):
    return {"id": order_id, "tenant": order["tenant"], "tenantName": TENANTS[order["tenant"]],
            "item": order["item"], "total": order["total"], "note": order["note"]}
