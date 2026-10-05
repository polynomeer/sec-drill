from app.data import ROLES
def permitted(action, project, principal, headers):
    role = (headers or {}).get("x-role", principal["role"])
    return project["team"] == principal["team"] and action in ROLES.get(role, [])
