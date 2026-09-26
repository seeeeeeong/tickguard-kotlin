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

docker compose pull && docker compose up -d         # deploy, by hand on the server
```

From a laptop, `scripts/deploy.sh [commit]` does the same and waits for the stream to
connect, not just for `/health`: a process can be healthy while Toss refuses everything it
sends. If the stream is not connected within two minutes it puts the previous image back.
The tag it deployed is written to `.env`, so a later `docker compose up -d` keeps it.

### Backups

The host takes a consistent copy every night (`sqlite3 .backup`, 04:15, one per weekday)
into `/opt/tickguard/backup`. To restore one:

```bash
docker compose stop
mv data/tickguard.db data/tickguard.db.broken && rm -f data/tickguard.db-wal data/tickguard.db-shm
cp backup/tickguard-<weekday>.db data/tickguard.db && chown 10001 data/tickguard.db
sqlite3 data/tickguard.db "PRAGMA integrity_check"    # ok
docker compose start
```

The copies sit on the same disk as the database, so they cover a bad write or a bad
migration, not a failed disk.

- **One copy at a time.** Stop the server's copy (`docker compose stop`) before running
  one anywhere else, the laptop included. Two copies revoke each other's token.
- **The source IP must be registered** in WTS. A home connection's address can change;
  the service then alerts once and retries every minute until it is registered again.
- **Exit status 143 after a stop is normal.** It is the JVM's answer to SIGTERM, after the
  graceful shutdown has run; `restart: unless-stopped` does not treat it as a crash.

## Automated orders (the three-month sleeve test)

Off unless turned on, sleeve by sleeve, in the server's `.env`:

```bash
TICKGUARD_TRADING=on            # the kill switch; anything but "on" is off
TICKGUARD_SLEEVE_A=LIVE         # OFF (default) · DRY_RUN (lists orders, sends none) · LIVE
TICKGUARD_SLEEVE_B=DRY_RUN
TICKGUARD_SLEEVE_C=OFF
```

On the first weekday of a month the service proposes each sleeve's rebalance at 09:00 KST
(Discord), and places the LIVE sleeves' orders once, in the next US order window: ten
minutes after the regular open to an hour before the close. Buys go by dollar amount, sells
by fractional quantity, all at market, each with a client order id so a retry cannot double
an order. The first order whose outcome is unknown halts everything until a restart; the
status page's `trading` line says why. The hard limits live in code, not configuration.
`TICKGUARD_TRADING=off` and `docker compose up -d` stops all orders.

### The dip sleeve (D)

`TICKGUARD_SLEEVE_D` runs a dip strategy every weekday on $733.98: a large cap other than the user's own more than 10% under its
20-day high, above its 200-day average and with RSI(14) under 30 is bought with a sixth of the capital,
once more 8% lower, and sold at +8% or −20% from its average cost; money not in a dip waits in SPY.
Three positions at most, never the user's own symbols (AMZN, GOOGL, SPCX); in turn, **none of its
symbols may be bought by hand**: before it trades, the account's shares of every one of them must
match the ledgers, or it halts. It proposes at 09:00 KST from the last close (bars are
refreshed first; a failed refresh retries in 15 minutes rather than pricing on an old close) and
places in that evening's order window.

Every order is journalled before it is sent, as a file named by its client order id under
`data/placements/`, and crossed out once refused or accepted and tagged with its sleeve. Toss's
order records carry no client order id, so a file left there (a crash, a failed tag write, an
unknown outcome) is the only trace of an order its sleeve does not know about, and the service
starts halted while one is. To recover: find the order in the Toss app, record its sleeve
(`insert into tickguard.sleeve_orders …`) if it was placed, delete the file, restart.

### The control page

`http://<tailnet address>:9464/control` is laid out like a brokerage's order screen: the
amount to buy in won, each sleeve's stocks under it, and one button at the bottom. The dry
run stays out of sight; the button says when buying opens until the dry run has listed the
orders in the window, then offers to buy them.

| Button | Does | Lasts |
|---|---|---|
| 오늘 주문 목록 불러오기 | Proposes now; places if trading is on and the window open | — |
| N원 구매하기 → 구매하기 | Runs the `DRY_RUN` sleeves as `LIVE`, then proposes and places at once. An `OFF` sleeve stays off | Until this window closes, or a restart |
| 매일 자동 주문 켜기 → 켜기 | Runs the dip sleeve, configured `DRY_RUN`, live every weekday without a press. Announced in Discord | Until switched off on the page, or by the stop button; kept across restarts in `data/daily-live` |
| 중지 (top right) → 멈추기 | No order of any kind, and switches daily orders off; orders already sent are not cancelled | Until a restart |

Only the daily switch survives a restart, kept in `data/daily-live`, shown at the top of the page
and announced in Discord whenever it changes; LIVE for one window and the stop are forgotten on
restart (the stop also clears the daily switch). `.env` stays the ceiling: an `OFF` sleeve or
trading off cannot be switched on from the page. Each button sends an `X-Tickguard-Control: 1`
header, which a form on another site cannot, so from a shell:

```bash
curl -X POST -H 'X-Tickguard-Control: 1' http://<tailnet address>:9464/sleeves/rebalance
```

## Cutting over from the original

The two services share a database schema, so the history moves with the service: recorded
ticks for backtests, cooldowns, stories and verdicts. They share a client too, so they
never run at the same time.

1. **Stop the original** and confirm nothing else holds the client (the laptop's `pc`
   entry, a probe). A second copy revokes the first one's token within a minute.
2. **Snapshot its database** with `sqlite3 tickguard.db ".backup snap.db"`, not `cp`: it
   runs in WAL mode and a plain copy of a live file can be torn.
3. **Put it in place** as `data/tickguard.db`, owned by uid 10001. The first start adds
   the columns it needs; nothing is rewritten.
4. **Register the server's address** in WTS (Settings → Open API → allowed IPs) and check
   it digit by digit. A typo reads exactly like an unregistered address.
5. **Start and verify**: `docker compose up -d`, then the status page: `startup ready`,
   `stream connected …`, `subscribed` equal to the holdings, `ticks` rising.

### Rolling back

- **To an earlier image:** `TICKGUARD_TAG=<commit> docker compose up -d`.
- **To the original:** `docker compose stop`, snapshot `data/tickguard.db` the same way,
  put it where the original reads it, and start it from a registered address. It reads
  this port's database: it names its columns, and the added `delivered` column defaults to
  delivered. Two differences remain. An alert that was never delivered holds its
  cooldown there, as it always did in the original. A drawdown keyed by depth
  (`AMZN:2`) is not a key the original knows, so a drawdown still in progress may alert
  once more after the switch.

## Development

JDK 21 (downloaded by Gradle if missing).

```bash
brew install lefthook cocogitto ktlint && lefthook install   # once per clone
./gradlew check                                              # what CI runs
```
