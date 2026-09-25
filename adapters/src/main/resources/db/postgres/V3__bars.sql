-- Daily bars for backtests, adjusted as the candles API adjusts them. Keyed
-- by symbol and day and written by upsert: a split or a dividend rewrites
-- adjusted history, and the later fetch is the truer one.
CREATE TABLE bars (
  code   text NOT NULL,
  day    date NOT NULL,
  open   numeric NOT NULL,
  high   numeric NOT NULL,
  low    numeric NOT NULL,
  close  numeric NOT NULL,
  volume numeric NOT NULL,
  PRIMARY KEY (code, day)
);
