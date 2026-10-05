"""Synthetic API gateway for the SecDrill "over-broad API token" scenario (09, T13).

All data is synthetic. The intended weakness: a token scoped to one collection can read resources in other
collections, and revoked tokens still work. Patchable logic is app/tokens.py and app/scopes.py. Learners reach this
app only over HTTP through the Lab Gateway; no route serves the access record, the environment or tokens.
"""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from app import scopes, tokens
from app.data import RESOURCES, audit


class Handler(BaseHTTPRequestHandler):
    server_version = "SyntheticGateway/1"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def token(self):
        header = self.headers.get("Authorization", "")
        return header[7:] if header.startswith("Bearer ") else ""

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/healthz":
            return self.send_json(200, {"ok": True})
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "api" and parts[1] in RESOURCES and parts[2].isdigit():
            collection, resource_id = parts[1], int(parts[2])
            headers = {k.lower(): v for k, v in self.headers.items()}
            granted = tokens.granted_scopes(self.token(), headers)
            if granted is None:
                return self.send_json(401, {"error": "invalid or revoked token"})
            required = collection + ":read"
            if not scopes.authorized(required, granted, headers):
                audit(self.token(), collection, resource_id, granted, 403)
                return self.send_json(403, {"error": "insufficient scope"})
            resource = RESOURCES[collection].get(resource_id)
            if resource is None:
                audit(self.token(), collection, resource_id, granted, 404)
                return self.send_json(404, {"error": "not found"})
            audit(self.token(), collection, resource_id, granted, 200)
            return self.send_json(200, resource)
        self.send_json(404, {"error": "not found"})


def main():
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
