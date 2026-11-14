#!/usr/bin/env python3
"""Has github.com - or any host attached to it - changed since the app's allow-list was reviewed?

Runs in GitHub Actions (workflow "Site drift"). Signed out, read only: no cookies, credentials,
query strings or page mutations. Standard library only.

It answers three separate questions and never mixes them up:

1. What does github.com name today?  The Content-Security-Policy of a fixed set of public pages is
   fetched, and (when --observations is given) the hosts a real signed-out browser actually
   requested are read from scripts/site-observations.cjs output.
2. What changed since the committed snapshot (docs/observed-github-csp.txt)?  Per page and CSP
   directive: sources added and removed. Script hashes/nonces are ignored (they change on deploy).
3. Does the app need to change?  Every host is classified against the REAL allow-list source
   (GitHubAllowlist.java) and the reviewed refusals (docs/site-reviewed-exclusions.json):

     ALLOWED     exact host on the allow-list
     PATTERN     matched by the one anchored host pattern on the allow-list
     EXCLUDED    deliberately refused, with the recorded reason
     UNREVIEWED  none of the above - a person has to decide. This fails the job (exit 1).

A CSP entry is evidence of a service name, NOT a rule to copy: nothing here edits the allow-list.

Exit codes: 0 no unreviewed host; 1 unreviewed host(s) or docs out of sync; 2 nothing observed.
"""

import argparse
import datetime
import glob
import json
import os
import re
import sys
import urllib.error
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Public pages only. The first three are the app's start page and its sign-in entry points.
PAGES = [
    ("home", "https://github.com/"),
    ("login", "https://github.com/login"),
    ("repository", "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed"),
    ("file", "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/blob/main/README.md"),
    ("issues", "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/issues"),
    ("pulls", "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/pulls"),
    ("actions", "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions"),
    ("releases", "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/releases"),
]

# A current Android Chrome identity, sent only so GitHub answers as it does for a phone browser.
USER_AGENT = ("Mozilla/5.0 (Linux; Android 14; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/130.0.0.0 Mobile Safari/537.36")

CSP_KEYWORD = re.compile(r"^(?:'.*'|[a-z][a-z0-9+.-]*:)$")  # 'self', 'sha256-…', data:, blob:, https:
VOLATILE = re.compile(r"^'(?:sha(?:256|384|512)|nonce)-")
HOST_SHAPE = re.compile(r"^(?:\*\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+$")


# --------------------------------------------------------------------------------------------------
# Inputs
# --------------------------------------------------------------------------------------------------

def fetch_csp(url):
    request = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.9",
    })
    record = {"url": url, "observed": now()}
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            record["status"] = response.status
            record["csp"] = response.headers.get("Content-Security-Policy") or ""
    except urllib.error.HTTPError as error:  # an HTTP error page still carries headers
        record["status"] = error.code
        record["csp"] = error.headers.get("Content-Security-Policy") or ""
    except Exception as error:  # noqa: BLE001 - reported, never hidden
        record["status"] = None
        record["csp"] = ""
        record["error"] = type(error).__name__ + ": " + str(error)[:200]
    return record


def now():
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


def parse_csp(text):
    """{directive: set(sources)}; volatile script hashes and nonces are dropped."""
    directives = {}
    for part in (text or "").split(";"):
        tokens = part.split()
        if not tokens:
            continue
        directives.setdefault(tokens[0].lower(), set()).update(
            token for token in tokens[1:] if not VOLATILE.match(token))
    return directives


def source_host(source):
    """'wss://alive.github.com' -> ('alive.github.com', ''); keywords -> (None, '')."""
    if CSP_KEYWORD.match(source):
        return None, ""
    rest = re.sub(r"^[a-z][a-z0-9+.-]*://", "", source.lower())
    host, _, path = rest.partition("/")
    host = host.split(":")[0]
    if not HOST_SHAPE.match(host):
        return None, ""
    return host, ("/" + path if path else "")


def read_snapshot(path):
    """Reads docs/observed-github-csp.txt: one or more 'Source:' blocks, each followed by a CSP line."""
    pages = {}
    if not path or not os.path.isfile(path):
        return pages, None
    current = None
    first_observed = None
    for line in open(path, encoding="utf-8").read().splitlines():
        line = line.strip()
        if line.startswith("Source:"):
            current = {"url": line.split(":", 1)[1].strip(), "csp": "", "observed": None}
            pages[current["url"]] = current
        elif line.startswith("Observed:") and current is not None:
            current["observed"] = line.split(":", 1)[1].strip()
            first_observed = first_observed or current["observed"]
        elif current is not None and re.match(r"^[a-z-]+-src\s", line):
            current["csp"] = line
    return pages, first_observed


def read_allowlist(java_path):
    """Exact entries and host patterns, read from the Java source that the APK is built from."""
    source = open(java_path, encoding="utf-8").read()
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)       # block comments / Javadoc
    source = re.sub(r"(?m)^\s*//.*$", "", source)               # whole-line comments
    entries = {}
    second = (r"(Collections\.<String>emptyList\(\)|paths\([^)]*\)"
              r"|Collections\.singletonList\([^)]*\))")
    calls = [(kind, host, "") for kind, host in
             re.findall(r"AllowEntry\.(anyPath)\(\s*\"([^\"]+)\"\s*,", source)]
    calls += re.findall(r"AllowEntry\.(resourceOnly|signIn)\(\s*\"([^\"]+)\"\s*,\s*" + second, source)
    declared = len(re.findall(r"AllowEntry\.(?:anyPath|resourceOnly|signIn)\(", source))
    if declared != len(calls):
        raise SystemExit("allow-list parser understood %d of %d entries in %s; update read_allowlist()"
                         % (len(calls), declared, java_path))
    for kind, host, argument in calls:
        if kind == "anyPath":
            role, paths = "PRODUCT", []
        else:
            role = "SIGN_IN" if kind == "signIn" else "ASSET"
            paths = re.findall(r"\"(/[^\"]*)\"", argument)
        if host in entries:
            # Two capability groups may name one host; the Java merge keeps the wider entry.
            old = entries[host]
            paths = [] if (not old["paths"] or not paths) else sorted(set(old["paths"]) | set(paths))
        entries[host] = {"role": role, "paths": paths}
    patterns = []
    for description, regex in re.findall(
            r"HostPatternEntry\.resourceOnly\(\s*\"([^\"]+)\"\s*,\s*\"([^\"]+)\"", source):
        patterns.append({"description": description,
                         "regex": regex.replace("\\\\", "\\"), "role": "ASSET"})
    if len(entries) < 20 or "github.com" not in entries:
        raise SystemExit("could not read the allow-list from " + java_path)
    return entries, patterns


def read_exclusions(path):
    data = json.load(open(path, encoding="utf-8"))
    rules = []
    for item in data["exclusions"]:
        if ("host" in item) == ("regex" in item):
            raise SystemExit("exclusion needs exactly one of host/regex: " + json.dumps(item))
        rules.append(item)
    return rules


def read_observations(directory):
    """Hosts a signed-out desktop browser actually requested (scripts/site-observations.cjs)."""
    runtime = {}   # host -> {"pages": set, "paths": set, "kinds": set}
    pages = []
    csps = {}
    if not directory:
        return runtime, pages, csps
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        try:
            record = json.load(open(path, encoding="utf-8"))
        except (OSError, ValueError):
            continue
        name = record.get("name") or os.path.basename(path)
        pages.append({"name": name, "url": record.get("requested"), "status": record.get("status"),
                      "final": record.get("finalUrl"), "error": record.get("observationError"),
                      "requests": len(record.get("requests") or [])})
        if record.get("csp"):
            csps[name] = {"url": record.get("requested"), "csp": record["csp"]}

        def seen(url, kind):
            match = re.match(r"^(?:https?|wss?)://([^/:]+)(?::\d+)?(/[^?#]*)?", url or "")
            if not match:
                return
            item = runtime.setdefault(match.group(1).lower(),
                                      {"pages": set(), "paths": set(), "kinds": set()})
            item["pages"].add(name)
            item["kinds"].add(kind)
            item["paths"].add(match.group(2) or "/")

        for request in record.get("requests") or []:
            seen(request.get("url"), request.get("type") or "request")
        for frame in record.get("frames") or []:
            seen(frame, "frame")
        for socket in record.get("websockets") or []:
            seen(socket, "websocket")
    return runtime, pages, csps


# --------------------------------------------------------------------------------------------------
# Classification
# --------------------------------------------------------------------------------------------------

class Policy:
    def __init__(self, entries, patterns, exclusions):
        self.entries = entries
        self.patterns = patterns
        self.exclusions = exclusions

    def classify(self, host):
        """(status, note). Status is ALLOWED, PATTERN, EXCLUDED or UNREVIEWED."""
        entry = self.entries.get(host)
        if entry:
            scope = "any path" if not entry["paths"] else "only " + ", ".join(entry["paths"])
            page = "may be a page" if entry["role"] != "ASSET" else "resource only, never a page"
            return "ALLOWED", "%s; %s; %s" % (entry["role"], scope, page)
        for pattern in self.patterns:
            if re.fullmatch(pattern["regex"], host):
                return "PATTERN", "matches %s; resource only, never a page" % pattern["description"]
        for rule in self.exclusions:
            if rule.get("host") == host or ("regex" in rule and re.fullmatch(rule["regex"], host)):
                return "EXCLUDED", "%s: %s" % (rule["category"], rule["reason"])
        return "UNREVIEWED", "not on the allow-list and not a recorded refusal"

    def path_allowed(self, host, path):
        entry = self.entries.get(host)
        if not entry or not entry["paths"]:
            return True
        return any(path.startswith(prefix) for prefix in entry["paths"])


def number_ranges(numbers):
    """[0, 1, 2, 5] -> '0-2, 5'."""
    parts, start, previous = [], None, None
    for number in sorted(set(numbers)):
        if start is None:
            start = previous = number
        elif number == previous + 1:
            previous = number
        else:
            parts.append(str(start) if start == previous else "%d-%d" % (start, previous))
            start = previous = number
    if start is not None:
        parts.append(str(start) if start == previous else "%d-%d" % (start, previous))
    return ", ".join(parts)


def collapse_pattern_rows(rows, policy):
    """One row per host pattern instead of one per numbered host (the report stays readable)."""
    kept, groups = [], {}
    for row in rows:
        if row[1] != "PATTERN":
            kept.append(row)
            continue
        description = next(p["description"] for p in policy.patterns if re.fullmatch(p["regex"], row[0]))
        groups.setdefault(description, []).append(row)
    for description, members in groups.items():
        numbers = [int(m.group()) for m in (re.search(r"\d+", row[0]) for row in members) if m]
        new_numbers = [int(re.search(r"\d+", row[0]).group()) for row in members
                       if row[4] == "new" and re.search(r"\d+", row[0])]
        label = "%d hosts" % len(members)
        if len(numbers) == len(members):
            label += ": numbers " + number_ranges(numbers)
        if new_numbers:
            label += "; new since the snapshot: " + number_ranges(new_numbers)
        where = "; ".join(sorted({row[2] for row in members}))
        kept.append([description, "PATTERN", where,
                     label + ". Resource only, never a page.", ""])
    return kept


def check_domains_doc(path, entries, patterns):
    """docs/DOMAINS.md must list exactly the hosts the code enforces."""
    if not path or not os.path.isfile(path):
        return []
    documented = set(re.findall(r"(?m)^\|\s*`([^`]+)`\s*\|\s*(?:PRODUCT|SIGN_IN|ASSET)\s*\|", open(
        path, encoding="utf-8").read()))
    enforced = set(entries) | {pattern["description"] for pattern in patterns}
    problems = []
    for host in sorted(enforced - documented):
        problems.append("enforced by the app but missing from docs/DOMAINS.md: " + host)
    for host in sorted(documented - enforced):
        problems.append("listed in docs/DOMAINS.md but not enforced by the app: " + host)
    return problems


# --------------------------------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------------------------------

def annotate(level, title, message):
    message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::%s title=%s::%s" % (level, title, message))


def cell(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--observations", default="", help="directory written by site-observations.cjs")
    parser.add_argument("--baseline", default=os.path.join(REPO, "docs/observed-github-csp.txt"))
    parser.add_argument("--exclusions", default=os.path.join(REPO, "docs/site-reviewed-exclusions.json"))
    parser.add_argument("--allowlist", default=os.path.join(
        REPO, "app/src/main/java/app/githubwebview/locked/policy/GitHubAllowlist.java"))
    parser.add_argument("--domains-doc", default=os.path.join(REPO, "docs/DOMAINS.md"))
    parser.add_argument("--out", default="site-drift")
    parser.add_argument("--offline", action="store_true",
                        help="do not fetch; use only the CSPs recorded in --observations (tests)")
    args = parser.parse_args()

    entries, patterns = read_allowlist(args.allowlist)
    policy = Policy(entries, patterns, read_exclusions(args.exclusions))
    baseline_pages, baseline_date = read_snapshot(args.baseline)
    runtime, browser_pages, browser_csps = read_observations(args.observations)

    # 1. What github.com names today ---------------------------------------------------------------
    fetched = []
    if not args.offline:
        for name, url in PAGES:
            record = fetch_csp(url)
            record["name"] = name
            fetched.append(record)
    known_urls = {record["url"] for record in fetched}
    for name, item in sorted(browser_csps.items()):   # pages only the browser visited (e.g. a job page)
        if item["url"] not in known_urls:
            fetched.append({"name": "browser:" + name, "url": item["url"], "status": 200,
                            "csp": item["csp"], "observed": now(), "via": "browser"})
    with_csp = [record for record in fetched if record.get("csp")]
    if not with_csp:
        annotate("error", "Site drift: nothing observed",
                 "No page returned a Content-Security-Policy; no conclusion can be drawn. "
                 + "; ".join("%s -> %s %s" % (r["name"], r.get("status"), r.get("error", ""))
                             for r in fetched))
        return 2

    named = {}     # host -> {"directives": set, "pages": set, "sources": set}
    for record in with_csp:
        for directive, sources in parse_csp(record["csp"]).items():
            for source in sources:
                host, _path = source_host(source)
                if not host:
                    continue
                item = named.setdefault(host, {"directives": set(), "pages": set(), "sources": set()})
                item["directives"].add(directive)
                item["pages"].add(record["name"])
                item["sources"].add(source)

    # 2. What changed since the committed snapshot --------------------------------------------------
    changes = []           # (page, directive, "+"/"-", source)
    compared, first_seen = [], []
    for record in with_csp:
        old = baseline_pages.get(record["url"])
        if not old or not old["csp"]:
            first_seen.append(record["name"])
            continue
        compared.append(record["name"])
        before, after = parse_csp(old["csp"]), parse_csp(record["csp"])
        for directive in sorted(set(before) | set(after)):
            for source in sorted(after.get(directive, set()) - before.get(directive, set())):
                changes.append((record["name"], directive, "+", source))
            for source in sorted(before.get(directive, set()) - after.get(directive, set())):
                changes.append((record["name"], directive, "-", source))
    baseline_hosts = set()
    for old in baseline_pages.values():
        for sources in parse_csp(old["csp"]).values():
            for source in sources:
                host, _path = source_host(source)
                if host:
                    baseline_hosts.add(host)

    # 3. Does the app need to change? ---------------------------------------------------------------
    rows = []
    unreviewed = []
    out_of_scope = []
    for host in sorted(set(named) | set(runtime)):
        status, note = policy.classify(host)
        where = []
        if host in named:
            where.append("CSP " + ", ".join(sorted(named[host]["directives"])))
        if host in runtime:
            where.append("requested by the browser on " + ", ".join(sorted(runtime[host]["pages"])))
        if status == "UNREVIEWED":
            unreviewed.append((host, "; ".join(where)))
        if status == "ALLOWED" and host in runtime:
            for path in sorted(runtime[host]["paths"]):
                if not policy.path_allowed(host, path):
                    out_of_scope.append((host, path, ", ".join(sorted(runtime[host]["pages"]))))
        rows.append([host, status, "; ".join(where), note, ""])

    doc_problems = check_domains_doc(args.domains_doc, entries, patterns)
    not_seen = sorted(host for host in entries if host not in named and host not in runtime)

    changed_hosts = {}
    for page, directive, sign, source in changes:
        host, _path = source_host(source)
        if host:
            changed_hosts.setdefault((sign, host), set()).add(page + " " + directive)
    added = sorted(host for sign, host in changed_hosts if sign == "+")
    removed = sorted(host for sign, host in changed_hosts if sign == "-")
    counts = {}
    for row in rows:
        counts[row[1]] = counts.get(row[1], 0) + 1
    for row in rows:
        if row[0] in added:
            row[4] = "new"
        elif baseline_hosts and row[0] in named and row[0] not in baseline_hosts:
            row[4] = "first recorded"   # only named on a page that had no earlier snapshot
    rows = collapse_pattern_rows(rows, policy)
    added_needing_change = [host for host in added if policy.classify(host)[0] == "UNREVIEWED"]
    removed_still_allowed = [host for host in removed
                             if policy.classify(host)[0] in ("ALLOWED", "PATTERN") and host not in named]

    # ---- Markdown report --------------------------------------------------------------------------
    run_url = ""
    if os.environ.get("GITHUB_RUN_ID"):
        run_url = "%s/%s/actions/runs/%s" % (os.environ.get("GITHUB_SERVER_URL", "https://github.com"),
                                             os.environ.get("GITHUB_REPOSITORY", ""),
                                             os.environ["GITHUB_RUN_ID"])
    lines = []
    out = lines.append
    out("# Site drift report: github.com and the hosts attached to it")
    out("")
    out("Generated by `scripts/site-drift.py` in GitHub Actions. Signed out, read only. "
        "**Do not edit by hand** - rerun the *Site drift* workflow with *refresh* instead.")
    out("")
    out("| | |")
    out("|---|---|")
    out("| Observed | %s |" % now())
    if run_url:
        out("| Workflow run | [%s](%s) |" % (os.environ["GITHUB_RUN_ID"], run_url))
    if os.environ.get("GITHUB_SHA"):
        out("| Allow-list read from commit | `%s` |" % os.environ["GITHUB_SHA"])
    out("| Previous snapshot | %s |" % (baseline_date or "none"))
    out("| App allow-list | %d exact hosts + %d host pattern |" % (len(entries), len(patterns)))
    out("")
    out("## Verdict")
    out("")
    if unreviewed:
        out("**ACTION NEEDED: %d host(s) are neither allowed nor a recorded refusal.** "
            "Decide for each one: add it to `GitHubAllowlist.java` (with its test and `DOMAINS.md` row) "
            "or record the refusal in `docs/site-reviewed-exclusions.json`." % len(unreviewed))
        out("")
        for host, where in unreviewed:
            out("* `%s` - %s" % (host, where))
    else:
        out("**No allow-list change is needed.** Every host github.com names or requested is either "
            "already allowed by the app or a recorded, deliberate refusal.")
    out("")
    if not baseline_pages:
        out("* There was no previous snapshot to compare with.")
    elif not changes:
        out("* Compared with the previous snapshot (%s): **no change** on %s."
            % (baseline_date, ", ".join(compared) or "any page recorded then"))
    else:
        out("* Compared with the previous snapshot (%s) on %s: **%d host(s) added, %d removed.**"
            % (baseline_date, ", ".join(compared), len(added), len(removed)))
    if first_seen:
        out("* Recorded for the first time (no earlier snapshot of these pages, so nothing to compare): "
            + ", ".join(first_seen) + ".")
    if out_of_scope:
        out("* %d request(s) by the desktop browser went to an allowed host **outside its allowed "
            "paths**; the app would refuse them. See below." % len(out_of_scope))
    if doc_problems:
        out("* **`docs/DOMAINS.md` does not match the code** (%d difference(s)); see below."
            % len(doc_problems))
    out("* Hosts seen: " + ", ".join("%d %s" % (counts[key], key) for key in
                                     ("ALLOWED", "PATTERN", "EXCLUDED", "UNREVIEWED") if key in counts) + ".")
    out("")

    out("## What changed since the previous snapshot")
    out("")
    if not changes:
        out("Nothing." if baseline_pages else "No previous snapshot.")
    else:
        out("| Change | Host | Where | What the app does |")
        out("|---|---|---|---|")
        for (sign, host), where in sorted(changed_hosts.items()):
            status, note = policy.classify(host)
            out("| %s | `%s` | %s | **%s** - %s |" % (
                "added" if sign == "+" else "removed", cell(host), cell("; ".join(sorted(where))),
                status, cell(note)))
        if removed_still_allowed:
            out("")
            out("Allowed by the app but no longer named by github.com (candidates for removal after a "
                "second confirming run): " + ", ".join("`%s`" % host for host in removed_still_allowed) + ".")
    out("")

    if out_of_scope:
        out("## Requests outside an allowed host's path scope")
        out("")
        out("Seen in the signed-out **desktop** browser, which is not the Android WebView. "
            "The app refuses these; whether that matters is decided by the emulator tests.")
        out("")
        out("| Host | Path | Page |")
        out("|---|---|---|")
        for host, path, pages in out_of_scope[:40]:
            out("| `%s` | `%s` | %s |" % (cell(host), cell(path[:90]), cell(pages)))
        out("")

    if doc_problems:
        out("## Documentation out of sync")
        out("")
        for problem in doc_problems:
            out("* " + problem)
        out("")

    out("## Every host seen, and what the app does with it")
    out("")
    out("| Host | Status | Seen in | Note |")
    out("|---|---|---|---|")
    order = {"UNREVIEWED": 0, "ALLOWED": 1, "PATTERN": 2, "EXCLUDED": 3}
    for host, status, where, note, new in sorted(rows, key=lambda row: (order[row[1]], row[0])):
        out("| `%s`%s | %s | %s | %s |" % (cell(host), " (%s)" % new if new else "", status,
                                         cell(where), cell(note)))
    out("")
    out("## Allow-listed hosts not seen in this run")
    out("")
    out("Informational only. Sign-in, CAPTCHA, upload, download and signed-in features are not "
        "exercised by a signed-out page load, and GitHub covers several of these with a wildcard "
        "(`*.githubusercontent.com`) instead of naming them. Absence here is not evidence that a host is unused.")
    out("")
    out(", ".join("`%s`" % host for host in not_seen) or "None.")
    out("")
    out("## Pages observed")
    out("")
    out("| Page | URL | HTTP | CSP |")
    out("|---|---|---|---|")
    for record in fetched:
        out("| %s | %s | %s | %s |" % (record["name"], record["url"], record.get("status"),
                                      "yes" if record.get("csp") else "none " + record.get("error", "")))
    for page in browser_pages:
        out("| browser: %s | %s | %s | %d requests%s |" % (
            page["name"], page["url"], page["status"], page["requests"],
            " (" + page["error"] + ")" if page["error"] else ""))
    out("")
    out("## Method and limits")
    out("")
    out("* Signed out. A signed-in session, a private repository, a running job's live log and an "
        "account challenge can use hosts that a signed-out load never touches.")
    out("* The CSP lists what GitHub *permits*, which is wider than what a page *uses*. It is evidence "
        "for choosing hosts, never a list to copy. Wildcards are never adopted.")
    out("* The browser part is desktop Chrome on a GitHub runner, not the Android WebView. What the "
        "app itself was refused is reported by the emulator job of *Android CI* "
        "(\"Refused sub-resources on the device\").")
    out("* Script hashes and nonces are ignored when comparing; they change with every GitHub deploy.")
    report = "\n".join(lines) + "\n"

    # ---- Snapshot (replaces docs/observed-github-csp.txt when the workflow is run with refresh) ---
    snapshot = ["Signed-out Content-Security-Policy snapshot of github.com.",
                "Generated by scripts/site-drift.py in GitHub Actions" + (" (" + run_url + ")" if run_url else "")
                + ". Do not edit by hand.",
                "No cookies or query strings saved.",
                "This is evidence of site service names, NOT the app allowlist.", ""]
    for record in with_csp:
        snapshot += ["Source: " + record["url"], "Observed: " + record["observed"],
                     "HTTP status: " + str(record.get("status")), "", record["csp"].strip(), ""]

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "SITE-DRIFT.md"), "w", encoding="utf-8") as handle:
        handle.write(report)
    with open(os.path.join(args.out, "observed-github-csp.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(snapshot))
    with open(os.path.join(args.out, "drift.json"), "w", encoding="utf-8") as handle:
        json.dump({"observed": now(), "baseline": baseline_date, "added": added, "removed": removed,
                   "unreviewed": [host for host, _where in unreviewed],
                   "outOfScope": out_of_scope, "docProblems": doc_problems,
                   "hosts": [{"host": h, "status": s, "seen": w, "note": n, "new": new}
                             for h, s, w, n, new in rows]}, handle, indent=2)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(report)

    # ---- Annotations: readable from the run page and the Checks API without any download ----------
    verdict = ("%d UNREVIEWED host(s)" % len(unreviewed)) if unreviewed else "no allow-list change needed"
    annotate("notice", "Site drift verdict",
             "%s. Since the previous snapshot (%s): %d host(s) added, %d removed on %s. "
             "Hosts seen: %s. Allow-list: %d exact hosts + %d pattern." % (
                 verdict, baseline_date or "none", len(added), len(removed),
                 ", ".join(compared) or "no comparable page",
                 ", ".join("%d %s" % (counts[key], key) for key in sorted(counts)),
                 len(entries), len(patterns)))
    if changes:
        annotate("warning" if added_needing_change else "notice", "Site drift: changed since the snapshot",
                 "\n".join("%s %s (%s) -> %s" % (sign, host, "; ".join(sorted(where)),
                                                  policy.classify(host)[0])
                           for (sign, host), where in sorted(changed_hosts.items()))[:3500])
    for host, where in unreviewed[:8]:
        annotate("error", "Site drift: unreviewed host " + host,
                 "%s is %s. Add it to the allow-list or record the refusal in "
                 "docs/site-reviewed-exclusions.json." % (host, where))
    if out_of_scope:
        # A notice, not a warning: this is the desktop browser, and the app refusing these is by design.
        annotate("notice", "Site drift: desktop browser requests outside allowed paths",
                 "\n".join("%s%s (page: %s)" % (host, path[:80], pages)
                           for host, path, pages in out_of_scope[:25]))
    for problem in doc_problems[:8]:
        annotate("error", "Site drift: docs out of sync", problem)
    if args.observations and not browser_pages:
        annotate("warning", "Site drift: no browser observations",
                 "No signed-out browser observation was found in %s; this report rests on the "
                 "Content-Security-Policy alone." % args.observations)
    failed_pages = [record for record in fetched if not record.get("csp")]
    if failed_pages:
        annotate("warning", "Site drift: page not observed",
                 "; ".join("%s -> HTTP %s %s" % (r["name"], r.get("status"), r.get("error", ""))
                           for r in failed_pages))
    print(report)
    return 1 if (unreviewed or doc_problems) else 0


if __name__ == "__main__":
    sys.exit(main())
