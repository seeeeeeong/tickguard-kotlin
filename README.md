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

After parity, the original's known defects were fixed one change each (#28–#38): cooldowns
that outlived an undelivered alert, SLA incidents lost behind an IP block or sent with their
own recovery, one dropped topic invisible to the fallback, stale REST prices, retries of
permanent refusals, and more.

Behaviour the original did not have comes after that. A drawdown now repeats every four
hours rather than every hour while it merely persists, and alerts at once each time it
falls another 2%p (`TICKGUARD_DRAWDOWN_ESCALATE`, default `0.02`): at -7%, -9%, -11%, not
again on a bounce back to a step already reported. The parity replay pins the original's
rule, which keeps a cooldown per symbol.

## Known limits

These are deliberate. None of them has a fix planned.

- **A hold restarts with the process.** The time a condition has held toward a rule's
  `holdFor` lives in memory. A drawdown that had held for 1m59s before a deploy needs its
  full two minutes again afterwards. Persisting it would mean a write per qualifying tick.
- **The clock is trusted to move forward.** Holds, cooldowns and windows compare wall-clock
  instants. A clock stepped backwards lengthens whatever was running, until time catches up.
  The keepalive reads the same clock on purpose: the server's 180s deadline is wall-clock
  time, and a monotonic clock stops while the host sleeps.
- **Open SLA incidents live in memory.** A restart between a silence page and the feed's
  return drops the recovery page. The next silence is paged normally.
- **Quotes are lossy by design.** The reconciler measures drift against REST. It cannot
  replay what the socket dropped, and nothing can.

## Deploying

CI publishes `ghcr.io/seeeeeeong/tickguard-kotlin` on every merge to main, tagged `main`
and with the commit. The server only pulls; it never builds.

```bash
# once: compose.yaml and .env in a directory, data/ owned by the image's user
mkdir data && chown 10001 data
echo "TICKGUARD_BIND=<tailnet address>" >> .env    # status page on the tailnet only

docker compose pull && docker compose up -d         # deploy
TICKGUARD_TAG=<commit> docker compose up -d         # roll back to a commit
```

- **One copy at a time.** Stop the server's copy (`docker compose stop`) before running
  one anywhere else, the laptop included. Two copies revoke each other's token.
- **The source IP must be registered** in WTS. A home connection's address can change;
  the service then alerts once and retries every minute until it is registered again.
- **Exit status 143 after a stop is normal.** It is the JVM's answer to SIGTERM, after the
  graceful shutdown has run; `restart: unless-stopped` does not treat it as a crash.

## Development

JDK 21 (downloaded by Gradle if missing).

```bash
brew install lefthook cocogitto ktlint && lefthook install   # once per clone
./gradlew check                                              # what CI runs
```
