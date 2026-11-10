import json
import secrets
import sys
from pathlib import Path


def load(path: str) -> dict:
    file = Path(path)
    if not file.exists():
        return {}
    try:
        return json.loads(file.read_text())
    except json.JSONDecodeError:
        return {}


def save(path: str, tokens: dict):
    file = Path(path)
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(tokens, indent=2, sort_keys=True))
    file.chmod(0o600)


def resolve(path: str, token: str) -> str | None:
    if not token:
        return None
    for name, value in load(path).items():
        if secrets.compare_digest(value, token):
            return name
    return None


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: tokens.py <file> add <name> | list | revoke <name>", file=sys.stderr)
        return 2
    path, command = argv[1], argv[2]
    tokens = load(path)
    if command == "add" and len(argv) == 4:
        name = argv[3]
        token = secrets.token_urlsafe(32)
        tokens[name] = token
        save(path, tokens)
        print(token)
        return 0
    if command == "list":
        for name in sorted(tokens):
            print(name)
        return 0
    if command == "revoke" and len(argv) == 4:
        if tokens.pop(argv[3], None) is None:
            print(f"no such token: {argv[3]}", file=sys.stderr)
            return 1
        save(path, tokens)
        return 0
    print("unknown command", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
