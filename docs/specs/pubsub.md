# Spec — Publish/Subscribe (QoS 0, fire-and-forget)

Route `/pubsub` · `PubSubController` (`/api/pubsub`) · `PubSubService` · `RedisPubSubListener`.

## Goal
Show non-durable, push-model messaging: subscribers connected at publish time receive the message;
nothing is persisted or replayed.

## Redis
- Pub/Sub channel (default `fire-and-forget`). No streams, no persistence.

## REST
- `POST /publish` — `PUBLISH channel payload`; broadcasts `MESSAGE_PUBLISHED`.
- `POST /subscribe` — register interest (backend subscriber already running on a daemon thread).

## Flow
`RedisPubSubListener` holds a blocking `SUBSCRIBE`; on message it broadcasts `MESSAGE_RECEIVED`
over WebSocket. Messages published while no subscriber is connected are lost (by design).

## Edge cases / acceptance
- Publishing with a live subscriber → UI shows publish then receive.
- Publishing with no subscriber → message is **not** delivered later (contrast with Streams).

## Inferred — verify
Whether the UI lets the user choose the channel name or fixes it to `fire-and-forget`.
