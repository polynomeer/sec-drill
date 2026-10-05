def authorized(required_scope, granted_scopes, headers):
    return required_scope in (granted_scopes or []) or required_scope == (headers or {}).get("x-scope")
