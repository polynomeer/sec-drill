"""Patch grading supervisor (T08, T13, ADR 0009).

Runs in its own container next to the patched app, never inside it. Reads the hidden test plan from stdin, sends
each request to the fixed target and judges the response itself. Prints one JSON object: whether the app became
ready and, per test id, whether the expectation held. Nothing the app prints or returns as a self-declared
"verdict" is trusted; only status codes and JSON fields the server computed are inspected.

A test may run `setup` requests first (e.g. deliver the same event twice) and then judge one `request`. The
expectation primitives are generic across incident families (tenant leak, webhook replay, over-broad token):
  statusIn, idsInclude, idsExclude, fieldEquals, bodyContains, bodyExcludes.
"""

import json
import sys
import time
import urllib.error
import urllib.request

TARGET = "http://app:8080"


def call(method, path, token=None, headers=None, body=None, raw=None, timeout=5):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(TARGET + path, method=method, data=data)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read(1048576)
    except urllib.error.HTTPError as error:
        return error.code, error.read(1048576)


def parse(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return None


def collect_ids(value, ids):
    """All integer `id` fields anywhere in the response, so a leaked record is caught in any shape."""
    if isinstance(value, dict):
        if isinstance(value.get("id"), int):
            ids.add(value["id"])
        for item in value.values():
            collect_ids(item, ids)
    elif isinstance(value, list):
        for item in value:
            collect_ids(item, ids)
    return ids


def judge(expect, status, raw):
    if status not in expect.get("statusIn", [status]):
        return False
    body = parse(raw)
    ids = collect_ids(body, set()) if body is not None else set()
    if expect.get("idsInclude") and not set(expect["idsInclude"]) <= ids:
        return False
    # A successful response that cannot be read counts as a possible leak.
    if expect.get("idsExclude") and 200 <= status < 300 and (body is None or ids & set(expect["idsExclude"])):
        return False
    for field, value in (expect.get("fieldEquals") or {}).items():
        if not isinstance(body, dict) or body.get(field) != value:
            return False
    text = raw.decode("utf-8", "replace")
    if any(token in text for token in expect.get("bodyExcludes") or []):
        return False
    if any(token not in text for token in expect.get("bodyContains") or []):
        return False
    return True


def run_request(spec, token):
    headers = dict(spec.get("headers") or {})
    raw = None
    # "from": build this request from a resource the server returns (e.g. replay a captured signed delivery). The
    # learner only ever possesses what the server hands out; no secret is needed to construct the request.
    source = spec.get("from")
    if source:
        _, fetched = call("GET", source["path"], token if spec.get("auth") else None)
        body = parse(fetched) or {}
        raw = (body.get(source.get("bodyField", "body")) or "").encode()
        for header, field in (source.get("headers") or {}).items():
            headers[header] = str(body.get(field, ""))
    return call(spec["method"], spec["path"], token if spec.get("auth") else None, headers, spec.get("body"), raw)


def main():
    plan = json.load(sys.stdin)
    deadline = time.time() + plan.get("readyTimeoutSeconds", 30)
    ready = False
    while time.time() < deadline:
        try:
            if call("GET", "/healthz", timeout=2)[0] == 200:
                ready = True
                break
        except Exception:  # a misbehaving app must not crash the supervisor
            pass
        time.sleep(0.5)
    results = {test["id"]: False for test in plan["tests"]}
    if ready:
        for test in plan["tests"]:
            token = None
            try:
                if plan.get("login"):
                    status, raw = call("POST", plan.get("loginPath", "/api/login"), body=plan["login"])
                    if status == 200:
                        token = (parse(raw) or {}).get("token")
                for setup in test.get("setup", []):
                    run_request(setup, token)
                status, raw = run_request(test["request"], token)
                results[test["id"]] = judge(test["expect"], status, raw)
            except Exception:
                results[test["id"]] = False
    print(json.dumps({"ready": ready, "results": results}, sort_keys=True))


main()
