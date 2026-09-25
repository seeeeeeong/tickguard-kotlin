-- The SQLite schema's tables, in Postgres types. Instants are timestamptz and
-- prices numeric, so the ticks can be queried as time and money in SQL; the
-- store converts at the edge and callers see the same rows either way.

CREATE TABLE fires (
  key       text PRIMARY KEY,
  rule_id   text NOT NULL,
  code      text NOT NULL,
  fired_at  timestamptz NOT NULL,
  -- A fire counts as a cooldown only once its alert went out.
  delivered boolean NOT NULL DEFAULT true
);
CREATE INDEX fires_fired_at ON fires (fired_at);

CREATE TABLE rejections (
  target      text PRIMARY KEY,
  code        text NOT NULL,
  rejected_at timestamptz NOT NULL
);

-- Quotes carry no sequence number, and two identical trades in one second are
-- two trades: the identity is arrival order, what SQLite's rowid kept.
CREATE TABLE ticks (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type        text NOT NULL,
  code        text NOT NULL,
  -- numeric keeps the digits and the scale it was given: 339.20 stays 339.20.
  price       numeric NOT NULL,
  volume      numeric NOT NULL,
  currency    text NOT NULL,
  traded_at   timestamptz NOT NULL,
  received_at timestamptz NOT NULL
);
-- A replay reads one symbol over a range, in arrival order within a second.
CREATE INDEX ticks_code_traded_at ON ticks (code, traded_at, id);
-- Pruning cuts across every symbol by time. Rows arrive in time order, which
-- is what a BRIN index needs, at a fraction of a B-tree's size.
CREATE INDEX ticks_traded_at ON ticks USING brin (traded_at);

-- Keyed per symbol as well: one story about two held companies is news for both.
CREATE TABLE news (
  source       text NOT NULL,
  id           text NOT NULL,
  code         text NOT NULL,
  title        text NOT NULL,
  publisher    text NOT NULL,
  url          text NOT NULL,
  published_at timestamptz NOT NULL,
  seen_at      timestamptz NOT NULL,
  PRIMARY KEY (source, id, code)
);
CREATE INDEX news_code_published_at ON news (code, published_at);

-- One row per story. 'failed' with attempts below the limit is retried at
-- retry_at; 'judged' is final.
CREATE TABLE verdicts (
  source     text NOT NULL,
  id         text NOT NULL,
  code       text NOT NULL,
  status     text NOT NULL CHECK (status IN ('judged', 'failed')),
  relevant   boolean,
  direction  text,
  impact     double precision,
  summary    text,
  model      text,
  judged_at  timestamptz,
  attempts   integer NOT NULL DEFAULT 0,
  retry_at   timestamptz,
  last_error text,
  PRIMARY KEY (source, id, code)
);

CREATE TABLE model_calls (at timestamptz NOT NULL);
CREATE INDEX model_calls_at ON model_calls (at);
