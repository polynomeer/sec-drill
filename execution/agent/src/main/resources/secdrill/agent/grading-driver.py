"""Patch grading supervisor (T08, ADR 0009).

Runs in its own container next to the patched app, never inside it. Reads the hidden test plan from stdin, sends
each request to the fixed target and judges the response itself. Prints one JSON object: whether the app became
ready and, per test id, whether the expectation held. Nothing the app prints, writes or returns as a "verdict" is
trusted; only status codes and order ids in responses are inspected.
"""

import json
import sys
import time
import urllib.error
import urllib.request

TARGET = "http://app:8080"


def call(method, path, token=None, headers=None, body=None, timeout=5):
    request = urllib.request.Request(TARGET + path, method=method, data=body)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read(1048576)
    except urllib.error.HTTPError as error:
        return error.code, error.read(1048576)


def order_ids(raw):
    try:
        body = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(body, dict):
        return None
    ids = set()
    if isinstance(body.get("id"), int):
        ids.add(body["id"])
    for item in body.get("orders") or []:
        if isinstance(item, dict) and isinstance(item.get("id"), int):
            ids.add(item["id"])
    return ids


def judge(expect, status, raw):
    if status not in expect.get("statusIn", [status]):
        return False
    ids = order_ids(raw)
    if expect.get("idsInclude") and (ids is None or not set(expect["idsInclude"]) <= ids):
        return False
    # A successful response that cannot be read counts as a possible leak.
    if expect.get("idsExclude") and 200 <= status < 300 and (ids is None or ids & set(expect["idsExclude"])):
        return False
    return True


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
        token = None
        try:
            status, raw = call("POST", "/api/login", body=json.dumps(plan["login"]).encode())
            if status == 200:
                token = json.loads(raw).get("token")
        except Exception:
            token = None
        for test in plan["tests"]:
            request = test["request"]
            try:
                status, raw = call(request["method"], request["path"], token if request.get("auth") else None, request.get("headers"))
                results[test["id"]] = judge(test["expect"], status, raw)
            except Exception:
                results[test["id"]] = False
    print(json.dumps({"ready": ready, "results": results}, sort_keys=True))


main()
