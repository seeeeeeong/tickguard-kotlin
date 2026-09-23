# 0002 · Modules split domain from I/O; packages split by feature

## Context

The original keeps *domain logic must not import adapters* as a convention, enforced by
review. In a single Gradle module the same rule would be enforced the same way, and a
convention only a reviewer enforces does not survive a busy week.

## Decision

Four modules, split on the one boundary worth enforcing mechanically:

| Module | Holds | Depends on |
|---|---|---|
| `core` | rules, price windows, subscription set, pipeline | nothing |
| `adapters` | Toss socket and REST, database, Slack, model, news sources | `core` |
| `app` | the Spring Boot process | `core`, `adapters` |
| `tools` | probe, backtest, verdict scripts | `core`, `adapters` |

Inside each module, packages split by feature (`gateway`, `rules`, `subscribe`, …), as
in the original.

## Consequences

- A Spring, OkHttp or JDBC import in `core` does not compile: none of them is on its classpath.
- Rules the compiler cannot see — no cycles between features, no write call to Toss —
  are checked by architecture tests.
- A feature can live in two modules, its logic in `core` and its I/O in `adapters`.
  That split is deliberate, not duplication.
