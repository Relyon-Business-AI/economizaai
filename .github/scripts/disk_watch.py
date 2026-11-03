#!/usr/bin/env python3
"""Watch the prod Postgres disk via the Render *metrics* API.

Render Postgres disks can only grow, and when one fills up the database stops
accepting writes — every receipt submit then fails. `pg_database_size` under-reports
(no WAL/temp), so this reads Render's own `disk-usage` / `disk-capacity` series
and alerts when usage crosses a threshold. The alert signature carries the
threshold bucket (80 / 90 / 95 %) so each escalation opens a fresh `prod-error`
issue even after the previous one was closed (prod_alert.py dedups by signature).

Outputs (to $GITHUB_OUTPUT): found=true|false, signature, context.
"""
import json
import os
import urllib.request

RENDER_API_KEY = os.environ["RENDER_API_KEY"]
RESOURCE_ID = os.environ["RESOURCE_ID"]
BUCKETS = [int(value) for value in os.environ.get("DISK_ALERT_BUCKETS", "80,90,95").split(",")]
API = "https://api.render.com/v1"


def latest_metric(name):
    request = urllib.request.Request(
        f"{API}/metrics/{name}?resource={RESOURCE_ID}",
        headers={"Authorization": f"Bearer {RENDER_API_KEY}", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        series = json.loads(response.read().decode())
    values = [point["value"] for entry in series for point in entry.get("values", [])]
    if not values:
        raise RuntimeError(f"no {name} samples for {RESOURCE_ID}")
    return values[-1]


def emit(found, signature="", context=""):
    out = os.environ.get("GITHUB_OUTPUT")
    if not out:
        print(f"found={found} signature={signature}\n{context}")
        return
    with open(out, "a") as handle:
        handle.write(f"found={'true' if found else 'false'}\n")
        handle.write(f"signature={signature}\n")
        handle.write("context<<AUTOFIX_EOF\n" + context + "\nAUTOFIX_EOF\n")


def main():
    used = latest_metric("disk-usage")
    capacity = latest_metric("disk-capacity")
    percent = used * 100 / capacity
    used_mb, capacity_mb = used / 1e6, capacity / 1e6
    crossed = [bucket for bucket in BUCKETS if percent >= bucket]
    print(f"[disk-watch] {RESOURCE_ID} {used_mb:.0f}/{capacity_mb:.0f} MB ({percent:.1f}%)")
    if not crossed:
        emit(False)
        return 0

    bucket = max(crossed)
    signature = f"prod infra: db disk >= {bucket}%"
    context = (
        f"**Prod Postgres disk at {percent:.1f}%** — {used_mb:.0f} MB of {capacity_mb:.0f} MB "
        f"(Render metrics API, resource `{RESOURCE_ID}`).\n\n"
        "When the disk fills, Postgres stops accepting writes and every receipt submit fails. "
        "Grow the disk from the Render dashboard (Postgres → Settings → Disk; disks only grow, "
        "never shrink) or move to the next plan. Check `pg_stat_user_tables` for bloat and "
        "`raw_html` on `receipts` as the usual culprit before growing."
    )
    emit(True, signature, context)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
