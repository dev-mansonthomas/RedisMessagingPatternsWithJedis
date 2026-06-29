# Spec — Topic Routing (Pub/Sub pattern matching)

Route `/pubsub-topic-routing` · `PubSubTopicRoutingController` (`/api/pubsub-topic-routing`) ·
`PubSubTopicRoutingService`.

## Goal
Same routing idea as `topic-routing` but over **Redis Pub/Sub** `PSUBSCRIBE` patterns instead of
streams — non-durable, push-model, channel-glob matching.

## Redis
- Concrete channels `order.<region>.<event>` (e.g. `order.eu.created`, `order.us.created`).
- Pattern subscriptions: `order.eu.*` (EU Compliance), `order.*.created` (Order Audit),
  `order.us.*` (US Orders).

## REST
- `POST /publish` — `PUBLISH` to a routing-key channel.
- `GET /subscriptions`, `GET /routing-keys`, `GET /health`.

## Flow
3 named `PatternSubscriber`s `PSUBSCRIBE` their glob (cached thread pool, not virtual threads).
A publish to a concrete channel is delivered to every subscriber whose pattern matches, via
`onPMessage` → WebSocket `MESSAGE_RECEIVED`. Publish emits `MESSAGE_PUBLISHED`.

## Acceptance
- `order.eu.created` reaches EU Compliance **and** Order Audit (two patterns match).
- `order.us.updated` reaches only US Orders.
- No subscriber connected → message lost (fire-and-forget).

## Inferred — verify
Whether subscriber set is fixed (3) or user-editable.
