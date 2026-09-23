# tickguard-kotlin

Subscribes to Toss Securities Open API realtime quotes, evaluates compound rules,
and sends alerts. **Read-only — this project never places orders.**

A port of the TypeScript original to Kotlin and Spring Boot. The original is the
reference for behaviour: parity comes first, proven by replaying recorded ticks through
both rule engines, and behaviour changes come after it, each in its own pull request.

## Never do this

- **Never call `POST /api/v1/orders`, `/orders/{id}/modify`, `/orders/{id}/cancel`,
  or any conditional-order endpoint.** These hit a live brokerage account with real money.
  This repo only *reads*: quotes, orderbook, holdings, and order events.
- Never print `.env` values to logs, commits, or conversation.
- Never widen the account scope. `X-Tossinvest-Account` is passed through, never guessed.
- Never run this alongside the TypeScript original against the same client.
  They share one token and two sockets (see below) and will revoke each other.

## Hard constraints of the Toss Securities API

These are the reasons behind most non-obvious code in this repo. None of them are
visible from the code alone, and getting them wrong fails silently at runtime.

| Constraint | Consequence |
|---|---|
| **Max 2 concurrent WebSocket connections per account** | Opening a 3rd is accepted and the server **kills the oldest connection**. Deploys need an explicit handover, not a restart. |
| **Only one access token is valid per client** | If two processes each issue a token, they invalidate each other (`401 token-revoked`). There must be exactly one issuer; everyone else reads the shared cache. |
| **Subscriptions are `full-replace`** | The single JSON array you send *is* the entire subscription set. There is no partial add/remove. Omitted topics are silently unsubscribed; `[]` clears everything. |
| **Declaration rate 5/s, 100 subscriptions per connection** | Exceeding yields `rate-limit-exceeded` / `too-many-topics`. No `Retry-After` header on the socket — back off ~1s and re-declare. |
| **Closed after 180s without inbound data. Data the server sends does NOT reset this timer.** | A `PING` every 60s is mandatory even while ticks are flowing. This is the easiest thing to get wrong. |
| **Quotes (`trade`, `orderbook`) are LOSSY with no sequence number** | Dropped frames are undetectable by design. Periodic reconciliation against REST `/api/v1/prices` is the only way to verify. |
| **Order events (`personal:order`) are LOSSLESS, but the connection is closed if consumption stalls for 2s** | The receive path must only enqueue. All evaluation happens in workers. Backpressure is mandatory, not an optimization. |
| **Events during a disconnect are never redelivered** | After reconnecting, resynchronize with `GET /api/v1/orders`. |
| **`rejected[]` entries are rejected again on every re-declaration** | Persist the rejected set and exclude it, or every reconnect repeats the same failures. |
| **`403` on unregistered source IP** (REST and WebSocket alike) | When the deploy target changes, register the new IP under WTS → Settings → Open API. |
| **Auth happens once, at the handshake.** A token expiring mid-connection does not close the socket | The token only has to be valid at connect time, so it can be refreshed lazily on access rather than on a timer. |
| **`PING` is bare text, not JSON** | `{"type":"PING"}` gets no `pong` back and fails exactly like sending nothing — the socket dies at 180s. A WebSocket protocol-level ping frame is not a substitute either. |

`price` and `volume` arrive as **strings**. That is deliberate on the server's part —
never `toDouble()` them. Convert to `BigDecimal` at the boundary and keep it. Compare
with `<`, `>=` or `compareTo`, **never `==`**: `BigDecimal.equals` compares scale, so
`339.20 == 339.2` is false.

REST rate limits are per **client × API group** (`ACCOUNT` 1/s, `MARKET_DATA` 15/s,
`ORDER_INFO` drops 6→3 during 09:00–09:10 KST, …). Read `X-RateLimit-Remaining` and
slow down before hitting `429`.

## What the JVM changes

The original ran on one thread, and several of its invariants held only because of
that. Here the socket reader, schedulers and I/O run on different threads.

- **Domain state is touched from one place at a time.** A mutable map that was safe
  on an event loop is a data race here.
- **The socket callback never blocks or suspends.** A blocked reader thread is the
  2-second stall that closes the order channel.
- **Never swallow `CancellationException`.** Isolating failures with a broad `catch`
  is common in this codebase; each one must rethrow cancellation, or shutdown hangs.

## Comments

- **Do not comment "what".** The code says it.
- **Do comment "why".** Especially any constant or branch derived from the table above —
  `PING_INTERVAL = 20.seconds` is meaningless without the 180s rule next to it.
- No commented-out code. No `TODO`/`FIXME` without an issue link.

## Conventions

- Modules split domain from I/O: `core` (no Spring, OkHttp or JDBC on its classpath),
  `adapters`, `app` (the only Spring module), `tools` (operator entry points, may print).
- Inside a module, packages split by **feature**, not by layer.
- Tests live in each module's `src/test/kotlin`, mirroring the production package and
  file name: `Keepalive.kt` → `KeepaliveTest.kt`.
- The `architecture` module checks what review used to: core depends on no framework,
  features form no cycle, nothing under `tickguard.toss` except token issuance sends a
  request body, constants carry a KDoc, tests mirror their file. Each rule has a fixture
  that breaks it.
- Iteration order is behaviour (parity is checked byte for byte): no `HashMap`/`HashSet`.
- Durations are `kotlin.time.Duration` and instants `java.time.Instant`. Epoch
  milliseconds exist only at the storage boundary.
- Seoul time comes from `ZoneId.of("Asia/Seoul")`, never the host's default zone:
  containers run in UTC.
- Commits follow Conventional Commits. The body explains *why*, not *what*. A port of
  an original pull request names it (`TS #13`) and says what the JVM changed.

## Commands

```bash
./gradlew check          # compile, test, ktlint, detekt — what CI runs
./gradlew spotlessApply  # fix formatting

# once per clone: hooks guard main, format staged files, verify commit messages
brew install lefthook cocogitto ktlint && lefthook install
```

## References

- Integration guide: https://openapi.tossinvest.com/openapi-docs/overview.md
- AsyncAPI (WebSocket source of truth): https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json
- OpenAPI (REST source of truth): https://openapi.tossinvest.com/openapi-docs/latest/openapi.json
