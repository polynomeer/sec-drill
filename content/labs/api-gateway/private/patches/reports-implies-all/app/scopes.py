def authorized(required_scope, granted_scopes, headers):
    granted = granted_scopes or []
    return required_scope in granted or "reports:read" in granted
