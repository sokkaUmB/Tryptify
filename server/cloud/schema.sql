-- SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
-- Tryptify Cloud's D1 (SQLite) tables. Safe to run on every deploy.

create table if not exists cloud_accounts (
  user_id          text    primary key,
  plan             text    check (plan in ('lite', 'plus', 'max')),
  paid_until       text,                       -- ISO-8601 instant
  used_bytes       integer not null default 0 check (used_bytes >= 0),
  trial            integer not null default 0, -- 1 while paid_until is a trial's end
  provider         text,
  customer_id      text,
  subscription_id  text,
  version          integer not null default 0, -- bumped on every write; guards concurrent webhooks
  last_event       text,                       -- the billing event whose write this row last took
  updated_at       text
);
create index if not exists cloud_accounts_subscription on cloud_accounts (provider, subscription_id);

-- Each provider event is applied once, however often it is delivered.
create table if not exists cloud_billing_events (
  provider     text not null,
  event_id     text not null,
  user_id      text not null,
  kind         text not null,
  received_at  text not null,
  primary key (provider, event_id)
);
