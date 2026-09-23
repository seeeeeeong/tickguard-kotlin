# tickguard-kotlin

Subscribes to Toss Securities Open API realtime quotes, evaluates compound rules,
and sends alerts — rebuilt on Kotlin and Spring Boot.

**Read-only: this project never places orders.**

A port of a TypeScript service, done for behavioural parity first. The constraints of
the Toss API that shape most of the code are in [CLAUDE.md](CLAUDE.md).

## Development

JDK 21 (downloaded by Gradle if missing).

```bash
brew install lefthook cocogitto ktlint && lefthook install   # once per clone
./gradlew check                                              # what CI runs
```
