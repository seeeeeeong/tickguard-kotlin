package demo

/** The server closes a connection after 180s without a ping; a third of that leaves room for two misses. */
const val PING_INTERVAL_MS = 60_000
