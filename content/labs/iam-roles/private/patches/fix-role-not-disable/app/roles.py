"""Role enforcement. Learner patch path."""

from app.data import ROLES


def permitted(action, project, principal, headers):
    # The principal's team must own the project and its role must permit the action; client headers are ignored.
    return project["team"] == principal["team"] and action in ROLES.get(principal["role"], [])
