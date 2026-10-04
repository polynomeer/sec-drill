import hashlib
import json
import re
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def validate_sources():
    prompts = sorted((ROOT / "prompts").glob("*.md"))
    extras = sorted((ROOT / "extras").glob("*.md"))
    assert len(prompts) == 23
    assert [int(path.name[:2]) for path in prompts] == list(range(23))
    assert len(extras) == 3
    for path in [ROOT / "README.md", *prompts, *extras]:
        text = path.read_text(encoding="utf-8")
        assert text.startswith("# "), path
        assert text.count("```") % 2 == 0, path
        assert len(text.strip()) > 100, path
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
            if "://" not in target and not target.startswith("#"):
                assert (path.parent / target.split("#", 1)[0]).resolve().is_file(), target
    design_dir = ROOT.parent / "SecDrill-docs"
    if design_dir.is_dir():
        known_names = {path.name.removesuffix(".md") for path in (design_dir / "docs").glob("*.md")}
        for path in prompts:
            names = re.findall(r"\b\d{2}-[a-z][a-z-]+", path.read_text(encoding="utf-8"))
            assert all(name in known_names for name in names), (path, names)
    return prompts, extras


def build():
    prompts, extras = validate_sources()
    intro = (
        "# SecDrill 전체 개발 프롬프트 통합본\n\n"
        "작성일: 2026-10-04. 초기화 1개, 후속 프롬프트 22개와 추가 예제를 포함한다.\n\n"
        "각 번호를 따로 실행한다. 모든 프롬프트를 한 번에 개발 요청으로 전달하지 않는다.\n\n"
        "사용 방법과 복사 경로는 [README](README.md)를 따른다.\n"
    )
    parts = [intro]
    for path in [*prompts, *extras]:
        parts.append(f"\n\n원본 파일: `{path.relative_to(ROOT).as_posix()}`\n\n")
        parts.append(path.read_text(encoding="utf-8"))
    (ROOT / "ALL-PROMPTS.md").write_text("".join(parts), encoding="utf-8")
    report = {
        "numberedPromptCount": len(prompts),
        "additionalExampleFileCount": len(extras),
        "checksPassed": ["numbering 00 through 22", "UTF-8 and headings", "local Markdown links", "code fence balance", "referenced design document names", "archive CRC and SHA-256 manifest"],
        "limitations": ["Prompts have not been executed", "No Claude Code configuration or product code was changed"],
    }
    (ROOT / "VALIDATION.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    files = sorted(path for path in ROOT.rglob("*") if path.is_file() and path.name != "MANIFEST.sha256" and "__pycache__" not in path.parts)
    manifest = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT).as_posix()}\n" for path in files)
    (ROOT / "MANIFEST.sha256").write_text(manifest, encoding="utf-8")
    archive = ROOT.parent / "SecDrill-development-prompts-v0.1.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in [*files, ROOT / "MANIFEST.sha256"]:
            bundle.write(path, Path(ROOT.name) / path.relative_to(ROOT))
    with zipfile.ZipFile(archive) as bundle:
        assert bundle.testzip() is None
        for line in manifest.splitlines():
            expected, relative = line.split("  ", 1)
            actual = hashlib.sha256(bundle.read(f"{ROOT.name}/{relative}")).hexdigest()
            assert expected == actual, relative
    print(json.dumps({"archive": str(archive), **report}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    build()
