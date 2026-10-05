from app.data import ROLES
def permitted(action, project, principal, headers):
    return action in ROLES.get(principal["role"], [])
