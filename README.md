# tickguard-kotlin

Subscribes to Toss Securities Open API realtime quotes, evaluates compound rules,
and sends alerts — rebuilt on Kotlin and Spring Boot.

**Read-only: this project never places orders.**

A port of a TypeScript service, done for behavioural parity first
([ADR 0001](docs/adr/0001-port-for-parity.md)). The constraints of the Toss API that
shape most of the code are in [CLAUDE.md](CLAUDE.md); decisions are in [docs/adr](docs/adr/).
