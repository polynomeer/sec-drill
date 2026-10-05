"""Role enforcement. Learner patch path."""

from app.data import ROLES


def permitted(action, project, principal, headers):
    # Weakness: any authenticated principal may take any action on any project.
    return True
