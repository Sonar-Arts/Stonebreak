#!/usr/bin/env python3
"""Stonebreak test harness core: system registry resolver + surefire summarizer.

Driven by run-tests.sh; can also be used standalone. Stdlib only.

Subcommands:
  list                          print the system catalog (validates the registry)
  resolve   --scope SCOPE       emit MODULES/TESTS (or GROUPS) lines for the runner
  summarize --scope SCOPE [--update-baseline]
                                parse surefire XML reports, print the concise
                                stats table, diff against the baseline, set the
                                exit code (0 green, 1 failures/regressions,
                                2 infra error)

SCOPE is "all", "integration", "regression", or comma-separated system names
from systems.map.

The regression baseline lives OUTSIDE the repo, in the per-user state dir
(Linux: $XDG_STATE_HOME/stonebreak-tests, Windows: %APPDATA%\\stonebreak-tests,
macOS: ~/Library/Application Support/stonebreak-tests) and is only written via
--update-baseline on a fully green "all" run.
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MODULES = ["openmason-engine", "stonebreak-game", "openmason-tool"]
TAG_SCOPES = ("integration", "regression")
REGISTRY = HERE / "systems.map"
SKIP_IGNORE = HERE / "skip-ignore.list"


def fail(msg, code=2):
    print(f"harness: {msg}", file=sys.stderr)
    sys.exit(code)


# ---------------------------------------------------------------- registry

def glob_to_regex(pattern):
    """Translate a systems.map glob (no .java suffix) to a regex.

    `**/` matches zero or more directory levels, a trailing/inner `**`
    matches anything, `*` stays within one path segment.
    """
    out, i = [], 0
    while i < len(pattern):
        c = pattern[i]
        if c == "*":
            if pattern[i : i + 3] == "**/":
                out.append(r"(?:.*/)?")
                i += 3
            elif pattern[i : i + 2] == "**":
                out.append(r".*")
                i += 2
            else:
                out.append(r"[^/]*")
                i += 1
        else:
            out.append(re.escape(c))
            i += 1
    return re.compile("".join(out) + r"$")


def parse_registry():
    """Return ordered [(system, module, includes, excludes)] rows."""
    if not REGISTRY.is_file():
        fail(f"registry not found: {REGISTRY}")
    rows = []
    for lineno, raw in enumerate(REGISTRY.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            fail(f"systems.map:{lineno}: expected 3 tab-separated fields, got {len(parts)}")
        system, module, patterns = parts
        if module not in MODULES:
            fail(f"systems.map:{lineno}: unknown module '{module}'")
        includes, excludes = [], []
        for pat in patterns.split(","):
            pat = pat.strip()
            if not pat:
                continue
            if pat.startswith("!"):
                excludes.append(glob_to_regex(pat[1:]))
            else:
                includes.append(glob_to_regex(pat))
        if not includes:
            fail(f"systems.map:{lineno}: no positive pattern")
        rows.append((system, module, includes, excludes))
    return rows


def scan_test_classes(module):
    """All *Test.java under the module's test root, as ext-less posix relpaths."""
    root = ROOT / module / "src" / "test" / "java"
    if not root.is_dir():
        return []
    return sorted(
        p.relative_to(root).with_suffix("").as_posix()
        for p in root.rglob("*Test.java")
    )


def resolve_registry():
    """Map every test class to exactly one system; abort loudly on drift.

    Returns (ordered system names, {system: {module: [fqcn, ...]}}).
    """
    rows = parse_registry()
    systems = list(dict.fromkeys(r[0] for r in rows))
    assignment = {}  # (module, relpath) -> system
    errors = []
    for module in MODULES:
        for rel in scan_test_classes(module):
            owners = [
                system
                for system, mod, inc, exc in rows
                if mod == module
                and any(rx.match(rel) for rx in inc)
                and not any(rx.match(rel) for rx in exc)
            ]
            if len(owners) == 1:
                assignment[(module, rel)] = owners[0]
            elif not owners:
                errors.append(f"unmapped test class: {module}:{rel} — add it to systems.map")
            else:
                errors.append(f"test class in multiple systems ({', '.join(owners)}): {module}:{rel}")
    if errors:
        fail("registry validation failed:\n  " + "\n  ".join(errors))
    by_system = {s: {} for s in systems}
    for (module, rel), system in assignment.items():
        by_system[system].setdefault(module, []).append(rel.replace("/", "."))
    for buckets in by_system.values():
        for fqcns in buckets.values():
            fqcns.sort()
    return systems, by_system


def scope_selection(scope, systems, by_system):
    """Return (modules, fqcns or None-for-tag-scopes) for a scope string."""
    if scope in TAG_SCOPES:
        return list(MODULES), None
    if scope == "all":
        wanted = systems
    else:
        wanted = [s.strip() for s in scope.split(",") if s.strip()]
        unknown = [s for s in wanted if s not in systems]
        if unknown:
            fail(f"unknown system(s): {', '.join(unknown)} — run with --list to see the catalog")
    modules, fqcns = [], []
    for system in wanted:
        for module, classes in by_system[system].items():
            if module not in modules:
                modules.append(module)
            fqcns.extend(classes)
    modules.sort(key=MODULES.index)
    return modules, sorted(fqcns)


# ---------------------------------------------------------------- baseline

def state_dir():
    if sys.platform.startswith("win"):
        base = os.environ.get("APPDATA", str(Path.home() / "AppData" / "Roaming"))
    elif sys.platform == "darwin":
        base = str(Path.home() / "Library" / "Application Support")
    else:
        base = os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local" / "state"))
    return Path(base) / "stonebreak-tests"


def baseline_path():
    return state_dir() / "baseline.tsv"


def load_baseline():
    """{(class, method): (invocations, skipped)} or None when absent."""
    path = baseline_path()
    if not path.is_file():
        return None
    entries = {}
    for raw in path.read_text().splitlines():
        if not raw or raw.startswith("#"):
            continue
        cls, method, inv, skip = raw.split("\t")
        entries[(cls, method)] = (int(inv), int(skip))
    return entries


def write_baseline(cases):
    path = baseline_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"# Stonebreak test baseline — written {date.today().isoformat()} by run-tests.sh all --update-baseline"]
    for (cls, method), rec in sorted(cases.items()):
        lines.append(f"{cls}\t{method}\t{rec['runs']}\t{rec['skipped']}")
    path.write_text("\n".join(lines) + "\n")
    return path


def load_skip_ignore():
    if not SKIP_IGNORE.is_file():
        return set()
    return {
        line.strip()
        for line in SKIP_IGNORE.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    }


# ---------------------------------------------------------------- reports

FRAME_RX = re.compile(r"at .*(?:stonebreak|openmason)[^(]*\((\w+\.java:\d+)\)")


def normalize_method(name):
    """Collapse parameterized/repeated invocation names to the base method."""
    for sep in ("(", "["):
        idx = name.find(sep)
        if idx > 0:
            name = name[:idx]
    return name.strip()


def parse_reports(modules):
    """Aggregate testcases from in-scope surefire reports.

    Returns ({(class, method): {runs, skipped, failed, time}}, [failure dicts]).
    """
    cases, failures = {}, []
    found_any = False
    for module in modules:
        reports = ROOT / module / "target" / "surefire-reports"
        for xml_file in sorted(reports.glob("TEST-*.xml")):
            try:
                suite = ET.parse(xml_file).getroot()
            except ET.ParseError as e:
                fail(f"unparseable report {xml_file}: {e}")
            if suite.get("tests") == "0":
                continue
            found_any = True
            for tc in suite.iter("testcase"):
                cls = tc.get("classname", "?")
                method = normalize_method(tc.get("name", "?"))
                rec = cases.setdefault(
                    (cls, method), {"runs": 0, "skipped": 0, "failed": 0, "time": 0.0}
                )
                rec["runs"] += 1
                rec["time"] += float(tc.get("time") or 0.0)
                problem = tc.find("failure")
                if problem is None:
                    problem = tc.find("error")
                if problem is not None:
                    rec["failed"] += 1
                    text = problem.get("message") or (problem.text or "")
                    first_line = next(
                        (l.strip() for l in text.splitlines() if l.strip()), "(no message)"
                    )
                    frame = FRAME_RX.search(problem.text or "")
                    where = f" ({frame.group(1)})" if frame else ""
                    failures.append(
                        {"case": f"{cls}#{method}", "msg": first_line[:140] + where}
                    )
                elif tc.find("skipped") is not None:
                    rec["skipped"] += 1
    return (cases, failures) if found_any else (None, None)


# ---------------------------------------------------------------- commands

def cmd_list(_args):
    systems, by_system = resolve_registry()
    total = 0
    print(f"{'SYSTEM':<14} {'MODULES':<38} CLASSES")
    for system in systems:
        buckets = by_system[system]
        count = sum(len(v) for v in buckets.values())
        total += count
        print(f"{system:<14} {', '.join(buckets):<38} {count:>7}")
    print(f"{'TOTAL':<14} {'':<38} {total:>7}")
    print(f"\ncategories (via @Tag): {', '.join(TAG_SCOPES)}")


def cmd_resolve(args):
    systems, by_system = resolve_registry()
    modules, fqcns = scope_selection(args.scope, systems, by_system)
    print("MODULES " + " ".join(modules))
    if fqcns is None:
        print("GROUPS " + args.scope)
    else:
        print("TESTS " + ",".join(fqcns))


def cmd_summarize(args):
    systems, by_system = resolve_registry()
    modules, _ = scope_selection(args.scope, systems, by_system)
    class_to_system = {
        fqcn: system
        for system, buckets in by_system.items()
        for classes in buckets.values()
        for fqcn in classes
    }

    cases, failures = parse_reports(modules)
    if cases is None:
        fail("no surefire reports found — the maven run produced nothing (see the mvn log)")

    # Per-system rollup.
    stats = {}
    for (cls, _method), rec in cases.items():
        system = class_to_system.get(cls, "(unmapped)")
        row = stats.setdefault(system, {"runs": 0, "skipped": 0, "failed": 0, "time": 0.0})
        for key in row:
            row[key] += rec[key]

    print(f"{'SYSTEM':<14} {'TESTS':>6} {'PASS':>6} {'FAIL':>6} {'SKIP':>6} {'TIME':>8}")
    totals = {"runs": 0, "skipped": 0, "failed": 0, "time": 0.0}
    for system in [s for s in systems if s in stats] + (
        ["(unmapped)"] if "(unmapped)" in stats else []
    ):
        row = stats[system]
        passed = row["runs"] - row["failed"] - row["skipped"]
        print(
            f"{system:<14} {row['runs']:>6} {passed:>6} {row['failed']:>6}"
            f" {row['skipped']:>6} {row['time']:>7.1f}s"
        )
        for key in totals:
            totals[key] += row[key]
    passed = totals["runs"] - totals["failed"] - totals["skipped"]
    print(
        f"{'TOTAL':<14} {totals['runs']:>6} {passed:>6} {totals['failed']:>6}"
        f" {totals['skipped']:>6} {totals['time']:>7.1f}s"
    )

    if failures:
        print(f"\nFAILURES ({len(failures)})")
        for f in failures:
            print(f"  {f['case']} — {f['msg']}")

    exit_code = 1 if failures else 0
    green = not failures

    # Baseline handling — full runs only (scoped runs can't see the whole suite).
    if args.scope == "all":
        ignore = load_skip_ignore()
        baseline = load_baseline()
        if baseline is None:
            print(f"\nBASELINE: none yet at {baseline_path()}"
                  + (" — writing one now" if args.update_baseline and green else
                     " — run with --update-baseline on a green run to create it"))
        else:
            newly_skipped = [
                f"{cls}#{m}"
                for (cls, m), rec in cases.items()
                if cls not in ignore
                and rec["skipped"] > baseline.get((cls, m), (0, 0))[1]
            ]
            disappeared = [
                f"{cls}#{m}"
                for (cls, m), (inv, _s) in baseline.items()
                if cases.get((cls, m), {"runs": 0})["runs"] < inv
            ]
            new_tests = sum(1 for key in cases if key not in baseline)
            print(f"\nBASELINE vs {baseline_path()}")
            print(
                f"  new failures: {len(failures)}   newly skipped: {len(newly_skipped)}"
                f"   disappeared: {len(disappeared)}   new tests: +{new_tests}"
            )
            for label, items in (("newly skipped", newly_skipped), ("disappeared", disappeared)):
                for item in items:
                    print(f"  {label}: {item}")
            if newly_skipped or disappeared:
                exit_code = 1
            if new_tests and green and not args.update_baseline:
                print("  hint: refresh with run-tests.sh all --update-baseline")
        if args.update_baseline:
            if not green:
                print("BASELINE NOT UPDATED: run is not green")
            else:
                print(f"baseline written: {write_baseline(cases)}")
    elif args.update_baseline:
        fail("--update-baseline requires scope 'all'")
    else:
        print("\n(baseline diff runs on 'all' scope only)")

    print(f"RESULT: {'PASS' if exit_code == 0 else 'FAIL'}")
    sys.exit(exit_code)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list")
    p_resolve = sub.add_parser("resolve")
    p_resolve.add_argument("--scope", required=True)
    p_sum = sub.add_parser("summarize")
    p_sum.add_argument("--scope", required=True)
    p_sum.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()
    {"list": cmd_list, "resolve": cmd_resolve, "summarize": cmd_summarize}[args.command](args)


if __name__ == "__main__":
    main()
