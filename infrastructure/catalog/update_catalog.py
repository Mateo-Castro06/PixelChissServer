"""Review or migrate the PixelChiss catalog using mongosh in a Compose container."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
SEED = ROOT / "game-service/src/main/resources/seed"
OPERATIONS = Path(__file__).with_name("catalog_operations.js").read_text()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", type=Path, action="append")
    parser.add_argument("--mongo-service", default="mongodb")
    parser.add_argument("--database", default="pixelchiss")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup", type=Path, help="New local file required by --apply")
    args = parser.parse_args()
    if args.apply != bool(args.backup):
        parser.error("--apply and --backup must be supplied together")

    files = args.compose_file or [ROOT / "infrastructure/docker/docker-compose.yml"]
    compose = ["docker", "compose", "-p", args.project]
    for file in files:
        compose += ["-f", str(file.resolve())]
    config = json.loads(subprocess.check_output([*compose, "config", "--format", "json"], text=True))
    for name in ("game-service", "game"):
        if name in config["services"]:
            uri = urlsplit(config["services"][name]["environment"]["MONGODB_URI"])
            if (uri.hostname != args.mongo_service or uri.port not in (None, 27017)
                    or uri.path != "/" + args.database or uri.username is not None):
                parser.error("MongoDB does not match the configured Game target; use an explicitly isolated maintenance project")
    if args.apply:
        running = subprocess.check_output([*compose, "ps", "--status", "running", "--services"], text=True)
        if {"game-service", "game"} & set(running.splitlines()):
            parser.error("Stop Game and other catalog writers before applying the migration")

    current = json.loads((SEED / "catalog.json").read_text())
    retired = json.loads((SEED / "legacy-challenges.json").read_text())
    if len(current["cards"]) != 6 or len(current["questions"]) != 32:
        parser.error("Invalid canonical catalog size")
    if Counter(card["type"] for card in current["cards"]) != {"BUFF": 3, "DEBUFF": 3}:
        parser.error("Invalid BUFF/DEBUFF distribution")
    if Counter(question["mapId"] for question in current["questions"]) != {"classic": 16, "science": 16}:
        parser.error("Invalid map distribution")
    for question in current["questions"]:
        options = question["options"]
        if (len(options) != 3 or len({value.strip().casefold() for value in options if value.strip()}) != 3
                or type(question["correctIndex"]) is not int or not 0 <= question["correctIndex"] < len(options)):
            parser.error("Invalid question options or answer index")
    for key in ("cards", "questions"):
        ids = [item["id"] for item in current[key] + retired[key]]
        if len(ids) != len(set(ids)):
            parser.error("Duplicate catalog IDs")

    def mongo(**data):
        context = json.dumps(dict(database=args.database, current=current, retired=retired, **data))
        return subprocess.run([*compose, "exec", "-T", args.mongo_service, "mongosh",
                               "--quiet", "--norc", "--file", "/dev/stdin"],
                              text=True, input=f"const input = {context};\n{OPERATIONS}",
                              stdout=subprocess.PIPE, check=True).stdout.strip()

    backup = mongo(apply=False)
    before = json.loads(backup)
    if not args.apply:
        print(json.dumps({"mode": "review", "database": args.database,
                          "cardIds": [doc["_id"] for doc in before["game_cards"]],
                          "questionIds": [doc["_id"] for doc in before["game_questions"]]}, ensure_ascii=False))
        return
    # Keep the original Extended JSON representation (including ObjectIds) and never overwrite a backup.
    with open(args.backup, "x", encoding="utf-8", opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
        output.write(backup + "\n")
        output.flush()
        os.fsync(output.fileno())
    print(mongo(apply=True, backup=backup))


if __name__ == "__main__":
    main()
