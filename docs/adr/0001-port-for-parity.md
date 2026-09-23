# 0001 · Port for behavioural parity before improving anything

## Context

tickguard exists as a working TypeScript service. Its behaviour was shaped by live runs
against the Toss API. Most of that knowledge is in comments and pull request bodies,
not in a specification: the 180s idle close that incoming data does not reset, the
403 that has to be retried slowly rather than given up on, the calendar that needs the
previous business day because US hours cross midnight in Seoul.

A rewrite that changes behaviour at the same time as it changes language cannot tell
its own bugs apart from intended changes.

## Decision

The first milestone, `v0.1.0-parity`, reproduces the original's behaviour:

- the same alerts, word for word, from the same ticks;
- the same SQLite schema, so the running service can be cut over, and rolled back, on
  the same database file without re-firing cooldowns or re-learning rejected topics.

Parity is proven by replaying recorded ticks through both rule engines and comparing
every fire. Changes to behaviour come after the milestone, each in its own pull request.

The one class of change allowed before it is what the JVM forces: the original relied on
running on a single thread, and where that no longer holds, the design changes and a
record here says why.

## Consequences

- A known wart in the original is ported as is, with an issue to fix it after parity.
- Pull requests name the original change they port, so the reasoning can be traced.
- The two services must never run at the same time against one client: they would
  revoke each other's token. Cut-over is stop-then-start.
