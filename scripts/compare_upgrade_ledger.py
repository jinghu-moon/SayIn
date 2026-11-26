"""Compare a closed v2 ledger with its v3 upgrade without exposing stored data."""

import argparse
import json
import sqlite3
from pathlib import Path


def compare(source: Path, upgraded: Path, with_image: bool) -> dict:
    with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True) as old:
        with sqlite3.connect(upgraded.as_uri() + "?mode=ro", uri=True) as new:
            before_version = old.execute("PRAGMA user_version").fetchone()[0]
            after_version = new.execute("PRAGMA user_version").fetchone()[0]
            assert (before_version, after_version) == (6, 7), "Unexpected ledger versions"
            counts = {}
            for before_table, after_table, order in (
                ("devices", "pairs", "id"),
                ("receipts", "receipts", "accept_order"),
                ("message_history", "message_history", "accept_order"),
                ("image_resources", "image_resources", "resource_id"),
            ):
                columns = [row[1] for row in old.execute(f'PRAGMA table_info("{before_table}")')]
                after_columns = [
                    "pair_id" if column == "device" else column for column in columns
                ]
                before_select = ",".join(f'"{column}"' for column in columns)
                after_select = ",".join(f'"{column}"' for column in after_columns)
                before_rows = old.execute(
                    f'SELECT {before_select} FROM "{before_table}" ORDER BY "{order}"'
                ).fetchall()
                after_rows = new.execute(
                    f'SELECT {after_select} FROM "{after_table}" ORDER BY "{order}"'
                ).fetchall()
                assert before_rows == after_rows, f"Stored {before_table} data changed"
                counts[after_table] = len(after_rows)

            assert counts["pairs"] == 1 and counts["receipts"] == (4 if with_image else 3)
            assert counts["image_resources"] == int(with_image)
            states = dict(new.execute("SELECT state,COUNT(*) FROM receipts GROUP BY state"))
            assert states == {"expired": 1, "injected": 2 if with_image else 1, "uncertain": 1}
            assert new.execute(
                "SELECT COUNT(*) FROM receipts WHERE request_rev != 0 "
                "OR execution_stage IS NOT NULL OR confirmed IS NOT NULL "
                "OR uncertain_scope IS NOT CASE WHEN state='uncertain' THEN 'global' END"
            ).fetchone()[0] == 0, "Unexpected migrated execution metadata"
            assert new.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            return {
                "before_schema": before_version,
                "after_schema": after_version,
                "preserved_rows": counts,
                "receipt_states": states,
                "original_columns_equal": True,
                "uncertain_scope": "global",
                "integrity_check": "ok",
            }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("upgraded", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--with-image", action="store_true")
    args = parser.parse_args()
    evidence = compare(args.source.resolve(), args.upgraded.resolve(), args.with_image)
    args.output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence))
