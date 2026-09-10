#!/usr/bin/env python3
"""Regression: a tool declared in a temporary theory calls the Scala bridge."""
from mcp.test import test_mcp as T


def main():
    source = r'''
mcp_tool scala_echo = \<open>fn s => Scala.function1 "echo" s\<close>
  (description \<open>Scala round trip\<close>)
'''
    with T.fixture_session(source) as (path, session, theory):
        with T.Client(T.ISABELLE + ["mcp_server", "-d", str(path), "-s", session, "-T", theory]) as client:
            T.initialize(client)
            reply = T.call(client, "scala_echo", input="hi-from-ml")
            T.verdict("ML tool body calls Scala.function1", not T.is_err(reply)
                      and T.text_of(reply) == "hi-from-ml", reply)
    return int(bool(T.failures))


if __name__ == "__main__":
    raise SystemExit(main())
