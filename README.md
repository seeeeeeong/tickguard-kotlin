# tickguard-kotlin

Subscribes to Toss Securities Open API realtime quotes, evaluates compound rules,
and sends alerts — rebuilt on Kotlin and Spring Boot.

**Read-only: this project never places orders.**

A port of a TypeScript service, done for behavioural parity first. The constraints of
the Toss API that shape most of the code are in [CLAUDE.md](CLAUDE.md).

## Layout

| Module | Holds | May depend on |
|---|---|---|
| `core` | Domain logic, split by feature: `gateway`, `subscribe`, `stream`, `pipeline`, `rules`, `notify`, `rest`, `holdings`, `sla`, `reconcile`, `fallback`, `store`, `news`, `verdict`, `backtest` | Kotlin, coroutines, kotlinx-serialization — no framework |
| `adapters` | The far end of each port: OkHttp socket and REST, SQLite, Slack, Google News, SEC, DeepSeek | `core` |
| `app` | The composition root, scheduler, status page and `/metrics`, on Spring Boot | `core`, `adapters` |
| `tools` | Operator entry points: probe, backtest, parity, verdict export/score | `core`, `adapters` |
| `architecture` | ArchUnit and Konsist rules that keep the above true | — |

## Running

Configuration is read from the environment, or a `.env` file at the repository root, under the
original's names (`TOSS_CLIENT_ID`, `TOSS_CLIENT_SECRET`, `TOSS_ACCOUNT_SEQ`, `TICKGUARD_*`;
see [`Config.kt`](app/src/main/kotlin/tickguard/runner/Config.kt)).

```bash
./gradlew :app:bootRun                    # the service; status page and /metrics on 127.0.0.1:9464
./gradlew :tools:probe --args="us AAPL"   # is the API reachable and behaving?
./gradlew :tools:backtest --args="--list" # replay recorded ticks through a rule
```

Only one access token is valid per client. **Never run the service or the probe beside
another instance** — each one's token revokes the other's.

## Parity

The port is checked against the original, not against a description of it.

- **Replay:** [`tools/parity/replay-golden.ts`](tools/parity/replay-golden.ts) runs the
  original's backtest replay and writes every fire as a line. The Kotlin replay must write
  the same bytes, with decimals to the twentieth place. CI checks this on committed
  synthetic ticks (311 fires). Locally it was also checked on a snapshot of a real
  recording: 250,866 ticks, 160 fires, identical. A drawdown hold made one millisecond
  longer breaks the match, which shows the comparison can fail.
- **Storage:** a database written by the original's store is read in CI, and one written
  by this port was read back by the original's store.
- **Text:** alert text, headline ids and number formatting follow JavaScript's rules where
  Java's differ (`trim`, `\s`, `toFixed`), so stored stories and alerts read the same after
  cut-over.
- **Tests:** every test in the original has a counterpart here.

Known defects of the original are ported as they are, and fixed after parity, one change
each.

## Development

JDK 21 (downloaded by Gradle if missing).

```bash
brew install lefthook cocogitto ktlint && lefthook install   # once per clone
./gradlew check                                              # what CI runs
```
