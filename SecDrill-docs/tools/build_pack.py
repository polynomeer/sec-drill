import csv
import hashlib
import json
import posixpath
import re
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LINK_PATTERN = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def check_local_links():
    failures = []
    for path in sorted(ROOT.rglob("*.md")):
        if path.name == "ALL-IN-ONE.md":
            continue
        for target in LINK_PATTERN.findall(path.read_text(encoding="utf-8")):
            target = target.strip("<>")
            if "://" in target or target.startswith("#"):
                continue
            local = target.split("#", 1)[0]
            if local and not (path.parent / local).resolve().exists():
                failures.append(f"{path.relative_to(ROOT)}: {target}")
    if failures:
        raise ValueError("Broken links: " + ", ".join(failures))


def check_references(document):
    def walk(value):
        if isinstance(value, dict):
            reference = value.get("$ref", "")
            if reference.startswith("#/"):
                cursor = document
                for part in reference[2:].split("/"):
                    cursor = cursor[part.replace("~1", "/").replace("~0", "~")]
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)
    walk(document)


def build_combined():
    sections = [
        "# SecDrill 개발 문서 통합본\n\n"
        "작성일 2026-10-04 · 버전 0.1 · 설계 제안 초안\n\n"
        "개별 docs 문서가 단일 출처입니다. 다음 내용은 편집 가능한 통합 읽기본입니다.\n"
    ]
    for path in sorted((ROOT / "docs").glob("*.md")):
        content = path.read_text(encoding="utf-8")
        def rebase(match):
            target = match.group(1)
            if "://" in target or target.startswith("#"):
                return match.group(0)
            normalized = posixpath.normpath("docs/" + target)
            return match.group(0).replace(target, normalized)
        content = LINK_PATTERN.sub(rebase, content)
        sections.append(f"\n\n출처 파일: `{path.relative_to(ROOT)}`\n\n{content}")
    (ROOT / "ALL-IN-ONE.md").write_text("".join(sections), encoding="utf-8")


def validate():
    documents = sorted((ROOT / "docs").glob("*.md"))
    assert len(documents) == 35
    assert [int(path.name[:2]) for path in documents] == list(range(35))
    for path in documents:
        content = path.read_text(encoding="utf-8")
        assert content.startswith("# SecDrill "), path
        assert content.count("```") % 2 == 0, path
    check_local_links()
    api = json.loads((ROOT / "contracts/openapi.yaml").read_text(encoding="utf-8"))
    check_references(api)
    event = json.loads((ROOT / "contracts/event.schema.json").read_text(encoding="utf-8"))
    scenario = json.loads((ROOT / "examples/scenario.json").read_text(encoding="utf-8"))
    oracle = json.loads((ROOT / "examples/private-oracle.json").read_text(encoding="utf-8"))
    assert scenario["scenarioVersionId"] == oracle["scenarioVersionId"]
    assert sum(oracle["weights"].values()) == 100
    assert scenario["runtime"]["hardTtlSeconds"] == 3600
    assert scenario["publishable"] is False and oracle["publishable"] is False
    with (ROOT / "examples/acceptance-matrix.csv").open(encoding="utf-8", newline="") as handle:
        requirements = list(csv.DictReader(handle))
    assert len(requirements) == 17
    operations = [operation for path in api["paths"].values() for operation in path.values()]
    assert len({operation["operationId"] for operation in operations}) == len(operations)
    checks = ["35 documents and numbering", "local links", "JSON parse and local schema refs", "API operationId uniqueness", "scenario/oracle consistency", "100-point rubric", "17 requirements traced"]
    limitations = ["PostgreSQL live migration/constraint behavior not executed", "Product implementation, strong runtime, performance and chaos tests not executed", "Markdown display and Mermaid rendering depend on reader"]
    try:
        from openapi_spec_validator import validate as validate_openapi
        from jsonschema import Draft202012Validator, FormatChecker
        from pglast import parse_sql
        validate_openapi(api)
        Draft202012Validator.check_schema(event)
        sample_event = {
            "eventId": "20000000-0000-4000-8000-000000000001",
            "type": "SessionCreated", "schemaVersion": 1,
            "aggregateId": "20000000-0000-4000-8000-000000000002",
            "aggregateVersion": 0,
            "sessionId": "20000000-0000-4000-8000-000000000002",
            "occurredAt": "2026-10-04T00:00:00Z",
            "correlationId": "20000000-0000-4000-8000-000000000003",
            "payload": {"sessionId": "20000000-0000-4000-8000-000000000002", "versionId": scenario["scenarioVersionId"], "mode": "CTF"}
        }
        validator = Draft202012Validator(event, format_checker=FormatChecker())
        validator.validate(sample_event)
        invalid_event = dict(sample_event, payload={"mode": "CTF"})
        assert list(validator.iter_errors(invalid_event))
        parse_sql((ROOT / "contracts/schema.sql").read_text(encoding="utf-8"))
        checks.extend(["OpenAPI 3.1 validator", "JSON Schema draft 2020-12 meta-schema", "valid event accepted and invalid payload rejected", "PostgreSQL SQL syntax parser"])
    except ImportError:
        limitations.append("Optional OpenAPI/JSON Schema/SQL validators unavailable on this run")
    return {"documentCount": len(documents), "apiOperationCount": len(operations), "checksPassed": checks, "limitations": limitations}


def package(report):
    report["textBytes"] = sum(path.stat().st_size for path in (ROOT / "docs").glob("*.md"))
    (ROOT / "VALIDATION.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    source_paths = sorted(path for path in ROOT.rglob("*") if path.is_file() and path.name != "MANIFEST.sha256" and "__pycache__" not in path.parts)
    manifest = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT).as_posix()}\n" for path in source_paths)
    (ROOT / "MANIFEST.sha256").write_text(manifest, encoding="utf-8")
    archive = ROOT.parent / "SecDrill-development-docs-v0.1.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in source_paths + [ROOT / "MANIFEST.sha256"]:
            bundle.write(path, Path(ROOT.name) / path.relative_to(ROOT))
    with zipfile.ZipFile(archive) as bundle:
        assert bundle.testzip() is None
        assert len(bundle.namelist()) == len(source_paths) + 1
    return archive


if __name__ == "__main__":
    verification = validate()
    build_combined()
    archive_path = package(verification)
    print(json.dumps({"archive": str(archive_path), **verification}, ensure_ascii=False, indent=2))
