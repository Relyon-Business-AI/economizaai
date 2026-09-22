#!/usr/bin/env python3
"""Scan recent Render logs for a NEW application error and hand it to the fixer.

Replaces the old local `docker logs` tail. Pulls the last window of logs via the
Render Logs API, applies the same detection rules the watchdog used (skip WARN,
skip stack-trace continuations, require a real ERROR/exception, ignore known
framework noise), builds a snippet with the offending com.relyon frame, and dedups
against AUTONOMOUS_FIXES.md so an already-handled signature is not re-fixed.

Outputs (to $GITHUB_OUTPUT): found=true|false, signature, context.
"""
import json
import os
import re
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

RENDER_KEY = os.environ["RENDER_API_KEY"]
SERVICE = os.environ["SERVICE_ID"]
OWNER = os.environ["OWNER_ID"]
LEDGER = os.environ.get("LEDGER", "AUTONOMOUS_FIXES.md")
WINDOW_MIN = int(os.environ.get("LOG_WINDOW_MIN", "150"))

CONTINUATION = re.compile(r"^\s*(at\s+\w|Caused by:|\.\.\.\s+\d+\s+(more|common))")
NOISE = re.compile(r"PageImpl|PagedModel|SpringDataJackson|WarningLoggingModifier")
IS_ERROR = re.compile(r"\b(ERROR|FATAL)\b|Exception(:| in thread)|OutOfMemory|Unexpected error")
RELYON_FRAME = re.compile(r"at com\.relyon\.[\w.$]+\([\w.]+:\d+\)")
STRIP_TS = re.compile(r"^\d{4}-\d{2}-\d{2}[ T][\d:.]+Z?\s*")
STRIP_MDC = re.compile(r"\[req=[^\]]*\]")


def api_get(url):
    request = urllib.request.Request(
        url, headers={"Authorization": f"Bearer {RENDER_KEY}", "Accept": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        return json.loads(response.read().decode())


def fetch_messages():
    start = (datetime.now(timezone.utc) - timedelta(minutes=WINDOW_MIN)).strftime("%Y-%m-%dT%H:%M:%SZ")
    params = urllib.parse.urlencode({"ownerId": OWNER, "resource": SERVICE, "startTime": start, "limit": 100})
    data = api_get(f"https://api.render.com/v1/logs?{params}")
    logs = data.get("logs") or []
    logs.sort(key=lambda entry: entry.get("timestamp", ""))
    return [entry.get("message", "") for entry in logs]


def normalize(line):
    line = STRIP_TS.sub("", line)
    line = STRIP_MDC.sub("[req=]", line)
    return line.strip()


def build_snippet(messages, index):
    parts = [messages[index]]
    for follow in messages[index + 1: index + 12]:
        if CONTINUATION.match(follow):
            parts.append(follow)
        elif follow.strip() == "":
            continue
        else:
            break
    # Snippets land verbatim in GitHub issues — strip the MDC block (it can
    # carry user identifiers) the same way normalize() does for signatures.
    return STRIP_MDC.sub("[req=]", "\n".join(parts))


def signature_for(snippet, header):
    frame = RELYON_FRAME.search(snippet)
    if frame:
        return frame.group(0)
    return normalize(header)[:80]


def already_in_ledger(signature, snippet):
    try:
        with open(LEDGER, "r", encoding="utf-8") as handle:
            ledger = handle.read()
    except FileNotFoundError:
        return False
    if signature and signature in ledger:
        return True
    frame = RELYON_FRAME.search(snippet)
    return bool(frame and frame.group(0) in ledger)


def emit(found, signature="", context=""):
    out = os.environ.get("GITHUB_OUTPUT")
    if not out:
        print(f"found={found} signature={signature}")
        return
    with open(out, "a", encoding="utf-8") as handle:
        handle.write(f"found={'true' if found else 'false'}\n")
        handle.write(f"signature={signature}\n")
        handle.write("context<<AUTOFIX_EOF\n" + context + "\nAUTOFIX_EOF\n")


def main():
    try:
        messages = fetch_messages()
    except Exception as error:
        print(f"[log-sweep] logs api error: {error}")
        emit(False)
        return 0

    newest = None
    for index, message in enumerate(messages):
        if not message or CONTINUATION.match(message) or "WARN" in message:
            continue
        if not IS_ERROR.search(message) or NOISE.search(message):
            continue
        snippet = build_snippet(messages, index)
        signature = signature_for(snippet, message)
        if not signature or already_in_ledger(signature, snippet):
            continue
        newest = (signature, snippet)  # keep the LAST (most recent) new error

    if not newest:
        print("[log-sweep] no new application error in window")
        emit(False)
        return 0

    signature, snippet = newest
    print(f"[log-sweep] new error signature: {signature[:80]}")
    emit(True, signature, snippet)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
