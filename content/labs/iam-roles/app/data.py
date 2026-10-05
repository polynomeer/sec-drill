"""Synthetic projects, principals and the access record for the over-broad-token Transfer (RBAC). Not patchable."""

import json
import os
import time

AUDIT_PATH = "/tmp/secdrill/audit.jsonl"
FLAG = os.environ.pop("SECDRILL_FLAG_RBAC_BYPASS", "")

# Projects grouped by owning team.
PROJECTS = {
    1: {"id": 1, "team": "team-a", "name": "Synthetic site"},
    2: {"id": 2, "team": "team-a", "name": "Synthetic app"},
    3: {"id": 3, "team": "team-b", "name": "Synthetic ledger", "secret": FLAG},
}
# Role -> permitted actions.
ROLES = {"viewer": ["read"], "editor": ["read", "write"]}
# token -> principal {team, role, disabled}.
PRINCIPALS = {
    "tok-view-a": {"team": "team-a", "role": "viewer", "disabled": False},
    "tok-edit-a": {"team": "team-a", "role": "editor", "disabled": False},
    "tok-svc-a": {"team": "team-a", "role": "viewer", "disabled": False},
    "tok-old": {"team": "team-a", "role": "editor", "disabled": True},
}


def audit(token, action, project_id, team, principal_team, status):
    os.makedirs(os.path.dirname(AUDIT_PATH), exist_ok=True)
    record = {"at": int(time.time()), "route": "project-access", "token": token, "action": action,
              "projectId": project_id, "resourceTeam": team, "principalTeam": principal_team, "status": status}
    with open(AUDIT_PATH, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")
