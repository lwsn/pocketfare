#!/usr/bin/env python3
"""
Show what changed upstream in FanFicFare for the files our adapters were ported from.

    tools/fff-sync.py               # summary + relevant diffs since the pinned commit
    tools/fff-sync.py --summary     # summary only (commit list, files touched)
    tools/fff-sync.py --update-pin  # after porting: pin to the fetched upstream HEAD
    tools/fff-sync.py --update-pin <commit>

Pin and file mapping live in docs/fff-upstream.lock.json. Upstream is cached (blobless clone) in
.fff-upstream/ (gitignored). Exit code: 0 = nothing relevant changed, 10 = relevant changes, 1 = error.
Only git and the Python standard library are needed.
"""
import datetime
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCK = os.path.join(ROOT, "docs", "fff-upstream.lock.json")
CACHE = os.path.join(ROOT, ".fff-upstream")


def git(*args, check=True):
    r = subprocess.run(["git", "-C", CACHE, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed:\n{r.stderr}")
    return r.stdout


def fetch(lock):
    if not os.path.isdir(os.path.join(CACHE, ".git")):
        print(f"Cloning {lock['repo']} into .fff-upstream/ ...", file=sys.stderr)
        r = subprocess.run(["git", "clone", "--quiet", "--filter=blob:none", "--no-checkout", lock["repo"], CACHE])
        if r.returncode != 0:
            sys.exit("clone failed")
    else:
        git("fetch", "--quiet", "origin", lock["branch"])
    return git("rev-parse", f"origin/{lock['branch']}").strip()


def ini_section_at(text, line_no):
    """Section header in effect at 1-based line_no of an ini file."""
    section = None
    for i, line in enumerate(text.splitlines(), 1):
        if i > line_no:
            break
        m = re.match(r"^\[([^\]]+)\]", line)
        if m:
            section = m.group(1)
    return section


def filter_ini_diff(diff, new_text, sections):
    """Keep only hunks of defaults.ini that fall in sections relevant to our adapters."""
    out, hunk, keep = [], [], False
    header = []
    for line in diff.splitlines():
        if line.startswith("@@"):
            if hunk and keep:
                out.extend(hunk)
            m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)", line)
            start = int(m.group(1)) if m else 1
            # Section may be declared inside the hunk itself; check the hunk's last line too.
            sec = ini_section_at(new_text, start)
            hunk, keep = [line + f"   [section: {sec}]"], sec in sections
        elif hunk:
            hunk.append(line)
            m = re.match(r"^[+ ]\[([^\]]+)\]", line)
            if m and m.group(1) in sections:
                keep = True
        else:
            header.append(line)
    if hunk and keep:
        out.extend(hunk)
    return "\n".join(header + out) if out else ""


def main():
    args = sys.argv[1:]
    with open(LOCK) as f:
        lock = json.load(f)
    head = fetch(lock)
    pin = lock["commit"]
    files = list(lock["files"].keys())

    if args and args[0] == "--update-pin":
        new = args[1] if len(args) > 1 else head
        new = git("rev-parse", new).strip()
        lock["commit"] = new
        lock["synced"] = datetime.date.today().isoformat()
        with open(LOCK, "w") as f:
            json.dump(lock, f, indent=2)
            f.write("\n")
        print(f"Pinned to {new} ({git('log', '-1', '--format=%cs %s', new).strip()})")
        return 0

    log = git("log", "--format=%h %cs %s", f"{pin}..{head}", "--", *files).strip()
    print(f"Pinned:   {pin[:12]}  ({git('log', '-1', '--format=%cs %s', pin).strip()}), synced {lock.get('synced')}")
    print(f"Upstream: {head[:12]}  ({git('log', '-1', '--format=%cs %s', head).strip()})")
    if not log:
        print("\nNo upstream changes to ported files. Nothing to do (you may still --update-pin).")
        return 0

    touched = git("diff", "--name-only", pin, head, "--", *files).split()
    print(f"\nUpstream commits touching ported files:\n{log}\n")
    print("Files changed -> where to port:")
    for t in touched:
        print(f"  {t}\n      -> {lock['files'].get(t, '?')}")

    relevant = False
    for t in touched:
        diff = git("diff", "--no-color", pin, head, "--", t)
        if t.endswith("defaults.ini"):
            diff = filter_ini_diff(diff, git("show", f"{head}:{t}"), set(lock["ini_sections"]))
            if not diff:
                print(f"\n(defaults.ini changed, but not in sections relevant to our adapters)")
                continue
        relevant = True
        if "--summary" not in args:
            print(f"\n{'=' * 100}\n{t}\n{'=' * 100}\n{diff}")
    if not relevant:
        print("\nNo relevant changes. Run --update-pin to record the check.")
        return 0
    return 10


if __name__ == "__main__":
    sys.exit(main())
