-- Which sleeve an order was placed for. Written by the execution module for
-- what it sends, and by a person for a manual order on a symbol the account
-- also holds for itself, which attribution never guesses.
CREATE TABLE sleeve_orders (
  order_id text PRIMARY KEY,
  sleeve   text NOT NULL
);
