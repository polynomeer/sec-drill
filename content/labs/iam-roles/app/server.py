"""Synthetic IAM/RBAC gateway for the SecDrill over-broad-token Transfer (09, T13). Same authorization-boundary
concept as api-gateway, a different family: action-level roles on team-owned projects, plus grant revocation.
Patchable logic is app/grants.py and app/roles.py. All data is synthetic."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from app import grants, roles
from app.data import PROJECTS, audit


class Handler(BaseHTTPRequestHandler):
    server_version = "SyntheticIam/1"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

    def token(self):
        header = self.headers.get("Authorization", "")
        return header[7:] if header.startswith("Bearer ") else ""

    def handle_access(self, action):
        path = urlsplit(self.path).path
        parts = path.strip("/").split("/")
        if len(parts) != 3 or parts[0] != "api" or parts[1] != "projects" or not parts[2].isdigit():
            return self.send_json(404, {"error": "not found"})
        project_id = int(parts[2])
        headers = {k.lower(): v for k, v in self.headers.items()}
        who = grants.principal(self.token(), headers)
        if who is None:
            return self.send_json(401, {"error": "invalid or disabled token"})
        project = PROJECTS.get(project_id)
        if project is None:
            return self.send_json(404, {"error": "not found"})
        if not roles.permitted(action, project, who, headers):
            audit(self.token(), action, project_id, project["team"], who["team"], 403)
            return self.send_json(403, {"error": "forbidden"})
        audit(self.token(), action, project_id, project["team"], who["team"], 200)
        return self.send_json(200, project if action == "read" else {"id": project_id, "written": True})

    def do_GET(self):
        if urlsplit(self.path).path == "/healthz":
            return self.send_json(200, {"ok": True})
        self.handle_access("read")

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(min(length, 8192))
        self.handle_access("write")


def main():
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
