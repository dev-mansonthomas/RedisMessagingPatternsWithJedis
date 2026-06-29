# Spec — Topic Routing (Streams, dynamic rules)

Route `/topic-routing` · `TopicRoutingController` (`/api/topic-routing`) · `TopicRoutingService` ·
`RoutingRulesController`/`Service` (`/api/routing-rules`) · Lua `route_message`.

## Goal
Exchange-style routing: a message with a `routingKey` is fanned to one or more destination streams
based on **editable** Lua-pattern rules with priority and stop-on-match.

## Redis
- Exchange/audit stream `events.topic.v1`.
- Targets: `events.order.v1`, `events.order.v2`, `events.notification.vip`,
  `events.notification.gdpr`, `events.audit.cancelled`.
- Rules hash `routing:rules:events.topic.v1`, config hash `routing:config:events.topic.v1`
  (`maxRules`, `version`, `description`).

## REST
- `POST /topic-routing/route?routingKey=...` — Lua `route_message`.
- `GET /routing-keys`, `GET /streams`, `DELETE /clear`.
- Rule CRUD: `GET/POST/DELETE /routing-rules/{exchangeStream}/rules[/{id}]`,
  `GET/PUT /metadata`, `POST /reset`.

## Flow
`route_message` loads rules, sorts by priority, matches `routingKey` against each Lua pattern,
`XADD`s to every matched target (unless a stop-on-match rule fires). Returns matched targets +
counters. Default rules seeded at startup (`RoutingRulesService`).

## Default rules (priority → pattern → target)
`001 order%.cancelled%. → audit.cancelled (STOP)` · `010 %.v1$ → order.v1` · `011 %.v2$ → order.v2`
· `020 %.vip%. → notification.vip` · `021 %.eu%. → notification.gdpr`.

## Acceptance
- VIP+v1 key routes to both `order.v1` and `notification.vip`.
- A `cancelled` key routes only to `audit.cancelled` (stop-on-match).
- Editing a rule changes routing without a restart.
