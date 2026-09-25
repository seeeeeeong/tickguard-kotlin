-- The order ledger: each order's latest state, and every change seen to it.
-- An order's state only moves forward; the change log keeps what arrived late.

CREATE TABLE orders (
  order_id             text PRIMARY KEY,
  symbol               text NOT NULL,
  side                 text NOT NULL,
  order_type           text NOT NULL,
  time_in_force        text NOT NULL,
  status               text NOT NULL,
  price                numeric,
  quantity             numeric NOT NULL,
  order_amount         numeric,
  currency             text NOT NULL,
  ordered_at           timestamptz NOT NULL,
  canceled_at          timestamptz,
  filled_quantity      numeric NOT NULL,
  average_filled_price numeric,
  filled_amount        numeric,
  commission           numeric,
  tax                  numeric,
  settlement_date      date,
  updated_at           timestamptz NOT NULL
);
-- A resync reads the orders still working.
CREATE INDEX orders_status ON orders (status);

-- The same change arrives over the stream and again from a resync: keyed by
-- what changed, so the second is a no-op. numeric compares by value, so 10
-- and 10.0 are one key.
CREATE TABLE order_events (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id        text NOT NULL,
  event           text,
  status          text NOT NULL,
  filled_quantity numeric NOT NULL,
  source          text NOT NULL CHECK (source IN ('stream', 'resync')),
  seen_at         timestamptz NOT NULL,
  UNIQUE (order_id, status, filled_quantity)
);
