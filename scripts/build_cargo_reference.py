"""Build an offline scanner reference; never modifies operational PED/tally data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/cargo-reference-v1.db"))
args = parser.parse_args()
raw = args.source.read_bytes()
rows = json.loads(raw)["vehicle_eqpt_ref_record"]
values = [str(row.get("Cargo_ID") or "").strip().upper() for row in rows]
unique = set(values)
accepted = sorted(v for v in unique if re.fullmatch(r"[A-Z0-9]{6,25}", v) and any(c.isdigit() for c in v))
signature = str.maketrans({"O": "0", "Q": "0", "I": "1", "L": "1", "B": "8", "S": "5", "Z": "2"})
args.output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.NamedTemporaryFile(dir=args.output.parent, suffix=".db", delete=False) as temp:
    tmp = Path(temp.name)
try:
    with sqlite3.connect(tmp) as db:
        db.execute("CREATE TABLE cargo_reference (cargo_id TEXT PRIMARY KEY, signature TEXT NOT NULL) WITHOUT ROWID")
        db.executemany("INSERT INTO cargo_reference VALUES (?, ?)", ((v, v.translate(signature)) for v in accepted))
        db.execute("CREATE INDEX cargo_signature ON cargo_reference(signature)")
        db.execute("PRAGMA user_version = 1")
        db.commit()
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    tmp.replace(args.output)
finally:
    tmp.unlink(missing_ok=True)
metadata = {
    "source": args.source.name,
    "source_sha256": hashlib.sha256(raw).hexdigest(),
    "source_rows": len(rows),
    "distinct_normalized": len(unique),
    "indexed_cargo_ids": len(accepted),
    "excluded_distinct": len(unique) - len(accepted),
    "duplicate_rows": len(values) - len(unique),
    "asset_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
    "policy": "Uppercase and trim; 6–25 ASCII letters/digits including a digit. No punctuation removal or character correction.",
}
args.output.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps(metadata, indent=2))
