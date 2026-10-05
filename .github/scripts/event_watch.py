#!/usr/bin/env python3
"""Watch the Render *events* API for infra failures the log sweep can't see.

A container OOM-kill or eviction is a platform event, NOT an application log line
— the process just dies, so `log_sweep.py` never catches it (this is exactly how
the 2026-10-03 OOM incident went unnoticed for days). This script polls the Render
events API for `server_failed` events in a recent window and, if any are found,
emits a signature + context in the same contract as log_sweep.py so the existing
`prod_alert.py` opens a deduplicated `prod-error` issue.

Outputs (to $GITHUB_OUTPUT): found=true|false, signature, context.
"""
import datetime as dt
import json
import os
import urllib.request

RENDER_API_KEY = os.environ["RENDER_API_KEY"]
SERVICE_ID = os.environ["SERVICE_ID"]
WINDOW_MIN = int(os.environ.get("EVENT_WINDOW_MIN", "45"))
API = "https://api.render.com/v1"


def fetch_events():
    request = urllib.request.Request(
        f"{API}/services/{SERVICE_ID}/events?limit=50",
        headers={"Authorization": f"Bearer {RENDER_API_KEY}", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        return json.loads(response.read().decode())


def describe_reason(reason):
    if not isinstance(reason, dict):
        return str(reason)[:60]
    oom = reason.get("oomKilled")
    if oom:
        return f"oomKilled(memoryLimit={oom.get('memoryLimit', '?')})"
    if reason.get("evicted"):
        return "evicted"
    non_zero = reason.get("nonZeroExit")
    if non_zero is not None:
        return f"nonZeroExit({non_zero})"
    return str(reason)[:60]


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
    cutoff = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=WINDOW_MIN)
    failures = []
    for entry in fetch_events():
        event = entry.get("event", {})
        if event.get("type") != "server_failed":
            continue
        timestamp = event.get("timestamp", "")
        try:
            when = dt.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        except ValueError:
            continue
        if when < cutoff:
            continue
        failures.append((timestamp, describe_reason(event.get("details", {}).get("reason", {}))))

    if not failures:
        print(f"[event-watch] no server_failed events in the last {WINDOW_MIN} min")
        emit(False)
        return 0

    # Signature groups by the distinct failure reasons seen, so a new failure mode
    # (e.g. OOM at a new memory limit after a plan change) re-alerts, while a steady
    # recurrence of the same reason stays one issue (dedup acked by closing).
    reasons = sorted({reason for _, reason in failures})
    signature = "prod infra: server_failed " + ", ".join(reasons)
    lines = "\n".join(f"- {timestamp}  {reason}" for timestamp, reason in failures)
    context = (
        f"**{len(failures)} `server_failed` event(s)** on the prod service in the "
        f"last {WINDOW_MIN} min (Render *events* API — invisible to the log sweep):\n\n"
        f"{lines}\n\n"
        "An OOM-kill/eviction restarts the container (~60-90s outage) and kills any "
        "in-flight request, so users see transient 'não foi possível enviar/confirmar' "
        "errors. If it's `oomKilled`, the instance is out of memory — lower heap "
        "(`MaxRAMPercentage`) or bump the Render plan. See project memory `project_prod_oom`."
    )
    print(f"[event-watch] {len(failures)} server_failed in window: {reasons}")
    emit(True, signature, context)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
