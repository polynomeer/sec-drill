#!/usr/bin/env python3
"""PostToolUse hook: syntax-check a JSON file Claude just edited.

Fast and deterministic: reads only the edited file, never modifies anything.
`SecDrill-docs/contracts/openapi.yaml` is JSON content, so it is checked too.
Exit 2 sends the error to Claude (the edit already happened; PostToolUse cannot block).
"""

import json
import sys
from pathlib import Path

JSON_SUFFIXES = {".json"}
JSON_FILES = {"openapi.yaml"}


def main():
    try:
        event = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0
    file_path = (event.get("tool_input") or {}).get("file_path")
    if not file_path:
        return 0
    path = Path(file_path)
    if path.suffix not in JSON_SUFFIXES and path.name not in JSON_FILES:
        return 0
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return 0
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        print(f"{path}: invalid JSON after edit: {error}. Fix the syntax before continuing.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
