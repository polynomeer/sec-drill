#!/usr/bin/env python3
"""Read-only repository checks for SecDrill documents, contracts and configuration.

This script never writes files. It reuses `SecDrill-docs/tools/build_pack.validate()`
(which only reads) instead of running build_pack.py, because build_pack.py's main
regenerates ALL-IN-ONE.md, VALIDATION.json, MANIFEST.sha256 and writes a ZIP.

Usage:
    .venv/bin/python scripts/check.py            # optional validators may SKIP
    .venv/bin/python scripts/check.py --strict   # SKIP counts as failure (CI)

Exit code 0 means no FAIL (and, with --strict, no SKIP). It verifies documents and
contracts only; it is not evidence that any product code builds, runs or is isolated.
"""

import argparse
import csv
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True

REPO = Path(__file__).resolve().parents[1]
PACK = REPO / "SecDrill-docs"
LINK_PATTERN = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
EXCLUDED_DIRS = {".git", ".venv", "node_modules", "SecDrill-docs"}

results = []


def record(status, name, detail=""):
    results.append((status, name, detail))


def check(name):
    def decorator(function):
        def run():
            try:
                outcome = function()
            except Exception as error:  # report every failure, keep running other checks
                record("FAIL", name, f"{type(error).__name__}: {error}")
                return
            if isinstance(outcome, tuple):
                record(*outcome)
            else:
                record("PASS", name, outcome or "")
        run.check_name = name
        return run
    return decorator


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sql_enums():
    """Map table.column -> allowed values from `CHECK (column IN (...))` in schema.sql."""
    sql = (PACK / "contracts/schema.sql").read_text(encoding="utf-8")
    enums = {}
    for table, body in re.findall(r"CREATE TABLE (\w+) \((.*?)\n\);", sql, re.S):
        for column, values in re.findall(r"CHECK \((\w+) IN \(([^)]*)\)\)", body):
            enums[f"{table}.{column}"] = re.findall(r"'([^']+)'", values)
    return enums


def backtick_enum(text, label):
    line = next(line for line in text.splitlines() if line.startswith(f"- {label}"))
    head = line.split(".", 1)[0]
    return re.findall(r"`([A-Z_]+)`", head)


@check("pack: build_pack.validate (numbering, links, OpenAPI, event schema, SQL syntax)")
def check_pack():
    sys.path.insert(0, str(PACK / "tools"))
    import build_pack  # noqa: E402  (read-only validate(); main is not executed)

    report = build_pack.validate()
    optional = [item for item in report["limitations"] if "Optional" in item]
    detail = f"{report['documentCount']} docs, {report['apiOperationCount']} operations"
    if optional:
        return ("SKIP", check_pack.check_name, detail + "; " + "; ".join(optional)
                + " (install requirements-dev.txt into .venv)")
    return detail + "; optional OpenAPI/JSON Schema/pglast validators ran"


@check("pack: MANIFEST.sha256 matches files")
def check_manifest():
    problems = []
    listed = set()
    for line in (PACK / "MANIFEST.sha256").read_text(encoding="utf-8").splitlines():
        digest, relative = line.split("  ", 1)
        listed.add(relative)
        path = PACK / relative
        if not path.is_file():
            problems.append(f"missing {relative}")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            problems.append(f"changed {relative}")
    unlisted = sorted(
        path.relative_to(PACK).as_posix()
        for path in PACK.rglob("*")
        if path.is_file() and path.name not in {"MANIFEST.sha256", ".DS_Store"}
        and "__pycache__" not in path.parts and path.relative_to(PACK).as_posix() not in listed
    )
    problems += [f"unlisted {item}" for item in unlisted]
    if problems:
        raise AssertionError(
            "; ".join(problems)
            + " (expected after an intentional pack edit: regenerate with build_pack.py, see WORKFLOW.md)"
        )
    return f"{len(listed)} entries"


@check("contracts: enums agree across 00, schema.sql, OpenAPI")
def check_enums():
    common = (PACK / "docs/00-common-contract.md").read_text(encoding="utf-8")
    api = load_json(PACK / "contracts/openapi.yaml")["components"]["schemas"]
    sql = sql_enums()
    pairs = {
        "mode": (backtick_enum(common, "모드 enum"), sql["sessions.mode"], api["Session"]["properties"]["mode"]["enum"]),
        "phase": (backtick_enum(common, "학습 단계 enum"), sql["sessions.phase"], api["Session"]["properties"]["phase"]["enum"]),
        "session.status": (sql["sessions.status"], api["Session"]["properties"]["status"]["enum"]),
        "lab.state": (sql["labs.state"], api["Lab"]["properties"]["state"]["enum"]),
        "submission.kind": (sql["submissions.kind"], api["Submission"]["properties"]["kind"]["enum"]),
        "submission.status": (sql["submissions.status"], api["Submission"]["properties"]["status"]["enum"]),
        "evaluation.verdict": (sql["evaluations.verdict"], api["Evaluation"]["properties"]["verdict"]["enum"]),
        "evaluation.patchGate": (sql["evaluations.patch_gate"], api["Evaluation"]["properties"]["patchGate"]["enum"]),
        "evidence.trustLevel": (sql["evidence.trust_level"], api["Evidence"]["properties"]["trustLevel"]["enum"]),
        "challenge.kind": (sql["challenges.kind"], api["ScenarioDetail"]["properties"]["challenges"]["items"]["properties"]["kind"]["enum"]),
    }
    mismatched = [name for name, sources in pairs.items() if any(set(s) != set(sources[0]) for s in sources)]
    if mismatched:
        raise AssertionError("mismatch: " + ", ".join(mismatched))
    return f"{len(pairs)} enums"


@check("contracts: event types agree across 16 and event.schema.json")
def check_events():
    schema = load_json(PACK / "contracts/event.schema.json")
    declared = schema["properties"]["type"]["enum"]
    conditional = [rule["if"]["properties"]["type"]["const"] for rule in schema["allOf"]]
    documented = re.findall(r"^\| ([A-Z][A-Za-z]+) \| ", (PACK / "docs/16-events-async.md").read_text(encoding="utf-8"), re.M)
    documented = [name for name in documented if name != "type"]
    if not (set(declared) == set(conditional) == set(documented)):
        raise AssertionError(f"schema={sorted(declared)} payloadRules={sorted(conditional)} doc16={sorted(documented)}")
    return f"{len(declared)} types, each with a payload rule"


@check("examples: scenario and oracle agree with contracts")
def check_examples():
    scenario = load_json(PACK / "examples/scenario.json")
    oracle = load_json(PACK / "examples/private-oracle.json")
    api = load_json(PACK / "contracts/openapi.yaml")["components"]["schemas"]
    modes = set(api["Session"]["properties"]["mode"]["enum"])
    phases = api["Session"]["properties"]["phase"]["enum"]
    assert scenario["scenarioVersionId"] == oracle["scenarioVersionId"], "version id differs"
    assert set(scenario["modes"]) <= modes, "unknown mode"
    assert set(scenario["modes"]) == {"CTF", "WARGAME", "PURPLE"}, "MVP entry modes are CTF/WARGAME/PURPLE (00)"
    assert scenario["phases"] == phases, "phase order differs from contract"
    assert set(scenario["completionRequirements"]) <= set(scenario["modes"]), "requirements for undeclared mode"
    assert {c["kind"] for c in scenario["challenges"]} <= {"FLAG", "OBJECTIVE"}, "unknown challenge kind"
    runtime = scenario["runtime"]
    expected = {"vcpus": 2, "memoryMiB": 2048, "diskMiB": 4096, "pids": 256, "idleTtlSeconds": 900, "hardTtlSeconds": 3600}
    assert {key: runtime[key] for key in expected} == expected, "runtime differs from 00 defaults"
    assert len(scenario["actions"]) == 4, "03 defines 4 response actions"
    assert sum(oracle["weights"].values()) == 100, "rubric weights must total 100"
    assert oracle["visibility"] == "GRADER_ONLY"
    assert oracle["gate"]["platformFailure"] == "INCONCLUSIVE", "platform failure must not become FAIL"
    assert scenario["publishable"] is False and oracle["publishable"] is False
    placeholders = [key for key, value in [("imageDigest", runtime["imageDigest"]), ("secretRef", oracle["flag"]["secretRef"]),
                    ("referencePatchRef", oracle["referencePatchRef"])] if "PLACEHOLDER" in value]
    return "consistent; publish-blocking placeholders: " + ", ".join(placeholders)


@check("traceability: 02 requirements, acceptance matrix, 30 tasks")
def check_traceability():
    prd = (PACK / "docs/02-prd.md").read_text(encoding="utf-8")
    plan = (PACK / "docs/30-implementation-plan.md").read_text(encoding="utf-8")
    with (PACK / "examples/acceptance-matrix.csv").open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    defined = set(re.findall(r"\b((?:FR|NFR)-\d{2})\b", prd))
    traced = {row["requirement"] for row in rows}
    tasks = set(re.findall(r"^\| (T\d{2}) \|", plan, re.M))
    documents = {path.name[:2] for path in (PACK / "docs").glob("*.md")}
    problems = []
    if defined != traced:
        problems.append(f"02 only {sorted(defined - traced)}, matrix only {sorted(traced - defined)}")
    for row in rows:
        unknown_tasks = set(row["tasks"].split("|")) - tasks - {"later"}
        unknown_docs = set(row["documents"].split("|")) - documents
        if unknown_tasks or unknown_docs:
            problems.append(f"{row['requirement']}: tasks {sorted(unknown_tasks)} docs {sorted(unknown_docs)}")
        if row["release_gate"] == "required" and row["tasks"] == "later":
            problems.append(f"{row['requirement']}: required gate without task")
    if problems:
        raise AssertionError("; ".join(problems))
    return f"{len(traced)} requirements, {len(tasks)} tasks"


@check("repo docs: local Markdown links resolve")
def check_repo_links():
    broken, count = [], 0
    for path in sorted(REPO.rglob("*.md")):
        if EXCLUDED_DIRS & set(path.relative_to(REPO).parts):
            continue
        count += 1
        text = re.sub(r"```.*?```", "", path.read_text(encoding="utf-8"), flags=re.S)
        for target in LINK_PATTERN.findall(text):
            target = target.strip("<>")
            if "://" in target or target.startswith(("#", "mailto:")):
                continue
            local = target.split("#", 1)[0].split(":", 1)[0]
            if local and not (path.parent / local).exists():
                broken.append(f"{path.relative_to(REPO)} -> {target}")
    if broken:
        raise AssertionError("; ".join(broken))
    return f"{count} files"


@check("config: Claude Code settings and repo config files")
def check_config():
    settings_path = REPO / ".claude/settings.json"
    settings = load_json(settings_path)
    permissions = settings.get("permissions", {})
    assert permissions.get("defaultMode") != "bypassPermissions", "bypass mode must not be the default"
    assert permissions.get("disableBypassPermissionsMode") == "disable", "bypass mode must be disabled"
    assert not any(rule.startswith("Bash(*") or rule == "Bash" for rule in permissions.get("allow", [])), "blanket Bash allow"
    text = settings_path.read_text(encoding="utf-8")
    assert not re.search(r"/Users/|/home/|sk-[A-Za-z0-9]|ghp_|AKIA[0-9A-Z]{16}", text), "personal path or token in shared settings"
    commands = [hook["command"] for group in settings.get("hooks", {}).values() for entry in group for hook in entry["hooks"]]
    for command in commands:
        script = REPO / re.search(r"\.claude/hooks/[\w.-]+", command).group(0)
        assert script.is_file(), f"missing hook {script}"
    editorconfig = (REPO / ".editorconfig").read_text(encoding="utf-8")
    assert "root = true" in editorconfig
    return f"settings valid; {len(commands)} hook(s)"


@check("git: ignore rules exclude secrets and local files, keep shared files")
def check_gitignore():
    must_ignore = [".env", ".env.local", "app/.env.production", ".claude/settings.local.json", "CLAUDE.local.md",
                   ".venv/bin/python", "certs/server.pem", "keys/id_ed25519", "SecDrill-development-docs-v0.1.zip",
                   ".DS_Store", "SecDrill-docs/.DS_Store", "lab-logs/run.log", "submissions/raw/a.json",
                   "scripts/__pycache__/x.pyc"]
    must_track = [".claude/settings.json", ".claude/hooks/check-edited-file.py", "AGENTS.md", "CLAUDE.md",
                  "SecDrill-docs/contracts/openapi.yaml", "SecDrill-docs/tools/build_pack.py", "example.env",
                  "requirements-dev.txt"]

    def ignored(path):
        return subprocess.run(["git", "check-ignore", "-q", "--no-index", path], cwd=REPO).returncode == 0

    problems = [f"not ignored: {p}" for p in must_ignore if not ignored(p)]
    problems += [f"ignored: {p}" for p in must_track if ignored(p)]
    if problems:
        raise AssertionError("; ".join(problems))
    return f"{len(must_ignore)} excluded, {len(must_track)} kept"


CHECKS = [check_pack, check_manifest, check_enums, check_events, check_examples, check_traceability,
          check_repo_links, check_config, check_gitignore]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--strict", action="store_true", help="treat SKIP as failure")
    arguments = parser.parse_args()
    for run in CHECKS:
        run()
    width = max(len(name) for _, name, _ in results)
    for status, name, detail in results:
        print(f"{status:4}  {name:<{width}}  {detail}")
    failed = [r for r in results if r[0] == "FAIL" or (arguments.strict and r[0] == "SKIP")]
    counts = {status: sum(1 for r in results if r[0] == status) for status in ("PASS", "FAIL", "SKIP")}
    print(f"\n{counts['PASS']} passed, {counts['FAIL']} failed, {counts['SKIP']} skipped"
          " — documents/contracts/config only; no product build, runtime, live PostgreSQL or isolation test.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
