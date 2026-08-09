#!/usr/bin/env python3
"""Regression test: a colliding -d session-directory set fails FAST, and does
not take the rest of the server down with it.

See ROOTS_ANALYSIS.md section 2 and plans/session_dirs_errors. Isabelle's
Sessions.load_structure builds a SINGLE global session graph over every -d
directory and errors on the first duplicate session name
(Pure/Build/sessions.scala:871, the info_graph fold). Originally that error
propagated out of MCP_Session.build unattributed, so MCP_Server.run published
Failed(...) and the server stayed up serving nothing -- including catalog-only
tools like list_sessions that need no prover at all. That was FINDING 1.

DECIDED POLICY (2026-08-07): a -d configuration error is not degraded around
and not served as a Failed status. MCP_Config.check runs synchronously in
MCP_Server.run before anything else -- before the build, before serve() reads
a byte of stdin -- and on any issue the process calls error(...), which the
isabelle launcher (Command_Line.tool) prints to STDERR prefixed with "***" and
exits nonzero. No JSON-RPC reply is ever served for a bad config; there is
nothing to poll for readiness.

  proj_alpha/ROOT   defines sessions  Alpha    and  Scratch
  proj_beta/ROOT    defines sessions  Beta     and  Scratch
                                                    ^^^^^^^ the only collision

Alpha and Beta are unrelated, validly-named, independently-developed projects
-- the point is not "duplicate names are an error" (that is correct), it is
that a mistake in proj_beta must not make proj_alpha unreachable. Before the
fix it did (FINDING 1); after the fix the whole process refuses to start, so
there is no serving of errors and no collateral damage to reason about.

Runs two cases for contrast:

  CONTROL  -d proj_alpha              -> reaches ready, list_sessions shows Alpha
  REPRO    -d proj_alpha -d proj_beta -> exits nonzero before any reply,
                                          actionable message on stderr,
                                          nothing at all on stdout

Exit code 0 iff both hold: the regression test is GREEN. A nonzero exit means
either the fail-fast behavior regressed (REPRO case) or valid multi-root
configs stopped working (CONTROL case) -- either way, something to fix.

Environment:
  ISABELLE   command to run isabelle, shell-split (default: the flatpak, per
             the project's "always go through the flatpak" rule -- the bundled
             Isabelle2025-2_linux tree shares $ISABELLE_HOME_USER/heaps and
             alternating between installs forces a full Pure rebuild)
  MCP_SESSION  -s SESSION to use (default: MCP-Tools, the cheap Pure-based
             registry -- the failure is in ROOT parsing, which happens before
             anything is built, so the base session is irrelevant and the
             cheapest one keeps this fast)
  REPRO_TIMEOUT  seconds to wait for a terminal readiness state (default 180).
             NOTE: the CONTROL case waits on a live session build, not just
             ROOT parsing -- a cold MCP-Tools heap (e.g. right after
             `scala_build` invalidated it, or on a machine that has never
             built it) can take well past 180s. If CONTROL times out with
             "not ready ..." rather than failing on content, that is very
             likely a cold heap, not a regression: rerun once (warm) before
             concluding anything, or set REPRO_TIMEOUT=600 up front.
"""

import json
import os
import queue
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

ISABELLE = shlex.split(os.environ.get(
    "ISABELLE", "flatpak run --command=isabelle de.tum.in.isabelle.Isabelle"))
SESSION = os.environ.get("MCP_SESSION", "MCP-Tools")
TIMEOUT = float(os.environ.get("REPRO_TIMEOUT", "180"))


# ---------------------------------------------------------------- fixture

THEORY = 'theory %s imports Main begin\ndefinition %s :: nat where "%s = 1"\nend\n'

# Each project declares its own real session plus a scratch session. Only the
# scratch names collide; Alpha and Beta are unique and innocent.
PROJECTS = {
    "proj_alpha": [("Alpha", "Alpha_Defs"), ("Scratch", "Alpha_Scratch")],
    "proj_beta": [("Beta", "Beta_Defs"), ("Scratch", "Beta_Scratch")],
}


def make_fixture(base):
    """Two project dirs whose ROOTs collide on exactly one session name."""
    for proj, sessions in PROJECTS.items():
        # sessions cannot share a directory (Sessions "Duplicate use of
        # directory"), so each session gets its own subdir under the project
        root_lines = []
        for session, thy in sessions:
            d = os.path.join(base, proj, session)
            os.makedirs(d)
            with open(os.path.join(d, thy + ".thy"), "w") as f:
                f.write(THEORY % (thy, thy.lower(), thy.lower()))
            root_lines.append(
                'session "%s" in "%s" = HOL +\n'
                '  description "%s"\n'
                '  theories\n'
                '    %s\n' % (session, session, proj + "/" + session, thy))
        with open(os.path.join(base, proj, "ROOT"), "w") as f:
            f.write("\n".join(root_lines))


# ---------------------------------------------------------------- mcp client

class Client:
    def __init__(self, label, dirs, base):
        argv = list(ISABELLE) + ["mcp_server", "-s", SESSION]
        for d in dirs:
            argv += ["-d", os.path.join(base, d)]
        self.argv = argv
        # per-label log file: CONTROL and REPRO share `base`, and "w+" would
        # otherwise truncate one case's log out from under the other
        self.stderr = open(os.path.join(base, "stderr-%s.log" % label), "w+")
        self.proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=self.stderr, text=True, bufsize=1)
        self.replies = queue.Queue()
        self.next_id = 0
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            line = line.strip()
            if line:
                self.replies.put(line)

    def call(self, method, params=None, timeout=60):
        self.next_id += 1
        msg = {"jsonrpc": "2.0", "method": method, "id": self.next_id}
        if params is not None:
            msg["params"] = params
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                reply = json.loads(self.replies.get(timeout=deadline - time.time()))
            except (queue.Empty, ValueError):
                continue
            if reply.get("id") == msg["id"]:
                return reply
        raise RuntimeError("no reply to %s within %ss" % (method, timeout))

    def session_status(self):
        """Readiness per isabelle://session: 'not ready ...' / 'failed ...' / 'ready'.

        Only the pre-readiness placeholder (MCP_Server.Handler.session_state_text)
        emits a 'status:' line. Once Ready the live backend answers with the
        session/dirs/theory/theories overview and no status line at all -- so
        "has a theories: line, has no status: line" IS the ready signal.
        """
        reply = self.call("resources/read", {"uri": "isabelle://session"})
        if "error" in reply:
            return "rpc-error: " + reply["error"].get("message", "")
        text = reply["result"]["contents"][0]["text"]
        for line in text.splitlines():
            if line.startswith("status:"):
                return line.split(":", 1)[1].strip()
        if any(l.startswith("theories:") for l in text.splitlines()):
            return "ready"
        return text.strip()

    def await_terminal(self, timeout):
        """Poll until the server leaves the transient 'not ready' state."""
        deadline = time.time() + timeout
        status = self.session_status()
        while time.time() < deadline and status.startswith("not ready"):
            time.sleep(2)
            status = self.session_status()
        return status

    def close(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.stderr.seek(0)
        return self.stderr.read()


# ---------------------------------------------------------------- cases

def markup_chars(s):
    """Control chars that are NOT ordinary whitespace -- i.e. leaked YXML."""
    return {hex(ord(c)) for c in s if ord(c) < 32 and c not in "\n\r\t"}


def scrub(s):
    """Replace YXML control chars with visible markers, for printing."""
    return s.replace("\x05", "<X>").replace("\x06", "<Y>")


def tool_text(reply):
    result = reply.get("result", {})
    text = " ".join(c.get("text", "") for c in result.get("content", []))
    return result.get("isError", False), text


def run_case(label, dirs, base):
    """CONTROL shape: a valid multi-root config that must still come up and
    serve. Drives initialize/resources/tools_call over JSON-RPC like a real
    client would."""
    print("\n" + "=" * 72)
    print("%s: -d %s" % (label, "  -d ".join(dirs)))
    print("=" * 72)
    client = Client(label, dirs, base)
    try:
        client.call("initialize", {"protocolVersion": "2024-11-05"}, timeout=120)
        print("  initialize ....... ok (the handshake never waits on the prover)")

        status = client.await_terminal(TIMEOUT)
        print("  isabelle://session status: %s" % scrub(status))

        # list_sessions needs the catalog only -- no prover round trip
        is_error, text = tool_text(
            client.call("tools/call", {"name": "list_sessions", "arguments": {}}, timeout=120))
        first = text.strip().splitlines()[:1]
        print("  list_sessions .... %s" % ("isError" if is_error else "ok"))
        for line in (text.strip().splitlines() if is_error else first):
            print("      | %s" % scrub(line))
        if not is_error:
            for line in text.splitlines():
                if line.strip().startswith(("Alpha", "Beta", "Scratch")):
                    print("      | %s" % line)
        return status, is_error, text
    finally:
        err = client.close()
        for line in err.splitlines():
            if "Duplicate" in line or "*** " in line:
                print("      stderr: %s" % line)


def run_repro(label, dirs, base):
    """REPRO shape: after the fix, a colliding -d set must fail BEFORE the
    json-rpc loop ever starts. There is nothing to poll for readiness and no
    request to send -- the process is expected to be gone (nonzero exit,
    message on stderr) before a client could complete a handshake. No bytes
    are ever written to stdin: sending a request here would either race a
    process that is already gone (BrokenPipeError) or, if the fail-fast
    behavior regressed, block for the full timeout waiting on a reply that
    was never going to come -- either way that is not what this case tests.
    """
    print("\n" + "=" * 72)
    print("%s: -d %s" % (label, "  -d ".join(dirs)))
    print("=" * 72)
    argv = list(ISABELLE) + ["mcp_server", "-s", SESSION]
    for d in dirs:
        argv += ["-d", os.path.join(base, d)]
    proc = subprocess.Popen(
        argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True)
    timed_out = False
    try:
        out, err = proc.communicate(timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        timed_out = True
        proc.kill()
        out, err = proc.communicate()
        print("  TIMED OUT waiting for the process to exit -- had to kill it")
        print("  (a negative/killed returncode below does NOT mean the fix")
        print("   works -- it means the process hung, which is its own failure)")
    print("  exit code ........ %s" % proc.returncode)
    print("  stdout ........... %s" % ("(empty)" if not out.strip() else repr(out)))
    for line in err.splitlines():
        if "duplicate session name" in line or "***" in line:
            print("      stderr: %s" % scrub(line))
    return proc.returncode, out, err, timed_out


def has_exact_session(text, name):
    """True if some list_sessions row's session-name COLUMN is exactly
    `name` -- not merely a substring match, which "Alpha" in text would also
    satisfy off the unrelated AFP session Alpha_Beta_Pruning (visible in the
    baseline whenever AFP is registered)."""
    return any(line.split()[:1] == [name] for line in text.splitlines() if line.strip())


def main():
    base = tempfile.mkdtemp(prefix="repro_dup_", dir=PROJECT_ROOT)
    print("fixture: %s" % base)
    print("isabelle: %s" % " ".join(ISABELLE))
    print("session:  %s" % SESSION)
    try:
        make_fixture(base)
        print("\n  proj_alpha/ROOT -> sessions Alpha, Scratch")
        print("  proj_beta/ROOT  -> sessions Beta,  Scratch   <- collides")

        c_status, c_err, c_text = run_case("CONTROL", ["proj_alpha"], base)
        r_code, r_out, r_err, r_timed_out = run_repro(
            "REPRO", ["proj_alpha", "proj_beta"], base)

        alpha_root = os.path.join(base, "proj_alpha", "ROOT")
        beta_root = os.path.join(base, "proj_beta", "ROOT")

        print("\n" + "=" * 72)
        print("VERDICT")
        print("=" * 72)

        # "Alpha" in c_text would also pass off the unrelated AFP session
        # Alpha_Beta_Pruning in the baseline -- has_exact_session requires a
        # session-name COLUMN of exactly "Alpha", the specific claim this
        # case makes (list_sessions shows Alpha), so a broken -d passthrough
        # can actually fail this check rather than false-passing on AFP.
        control_ok = (not c_err) and has_exact_session(c_text, "Alpha") and c_status == "ready"
        exits_nonzero = (not r_timed_out) and r_code != 0
        message_present = 'duplicate session name "Scratch"' in r_err
        cites_both_roots = (alpha_root in r_err) and (beta_root in r_err)
        stdout_silent = r_out.strip() == ""
        no_markup_leak = not markup_chars(r_err)

        print("\nCONTROL -- a valid multi-root config still comes up and serves")
        print("  reaches ready and list_sessions shows Alpha ...... %s" % ok(control_ok))

        print("\nREPRO -- a colliding -d set fails fast, cleanly, at startup")
        print("  process exits nonzero ............................ %s" % ok(exits_nonzero))
        print("  actionable message on stderr ...................... %s" % ok(message_present))
        print("  message cites BOTH colliding ROOT paths .......... %s" % ok(cites_both_roots))
        print("  stdout (the json-rpc channel) stayed silent ....... %s" % ok(stdout_silent))
        print("  no raw YXML control chars in the message .......... %s" % ok(no_markup_leak))
        if not no_markup_leak:
            print("      formerly FINDING 2: MCP_Server.run stored Exn.message(exn)")
            print("      verbatim into Failed(...), leaking \\x05/\\x06 position markup.")
            print("      That path is gone for THIS error (MCP_Config builds the message")
            print("      itself from Position.Line/File.get, never from Exn.message), but")
            print("      Bad_Dir/Root_Error details still travel Exn.message ->")
            print("      MCP_Server.decode_message, so this remains a live guard on the")
            print("      config-error stderr path as a whole, not a vestige of the old bug.")

        if control_ok and exits_nonzero and message_present and cites_both_roots and stdout_silent:
            print("\nPASS: valid multi-root configs still work, and a colliding -d set")
            print("now fails fast with an actionable message instead of coming up in a")
            print("silently-broken state.")
            return 0
        print("\nFAIL -- see the case output above.")
        return 1
    finally:
        shutil.rmtree(base, ignore_errors=True)


def ok(b):
    return "yes" if b else "NO"


if __name__ == "__main__":
    sys.exit(main())
