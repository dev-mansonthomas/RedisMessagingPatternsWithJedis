# Spec — Request/Reply

Route `/request-reply` · `RequestReplyController` (`/api/request-reply`) · `RequestReplyService` ·
Lua `request`/`response` · `KeyspaceNotificationListener`.

## Goal
Synchronous-style RPC over streams with correlation IDs and a server-enforced timeout.

## Redis
- Request stream `order.holdInventory.v1`, response stream `order.holdInventory.response.v1`.
- Timeout key `order.holdInventory.request.timeout.v1:{correlationId}` (EX = timeout).
- Shadow key `...timeout.shadow.v1:{correlationId}` (holds `businessId`, `streamResponseName`; EX = timeout+10s).
- Keyspace event pattern `__keyevent@0__:expired`.

## REST
- `POST /request` — Lua `request` (set timeout + shadow, `XADD` request). Returns message id.
- `POST /response` — Lua `response` (`DEL` timeout key, `XADD` response).

## Flow
Requester sends request with `correlationId`. A responder posts a response → timeout key deleted →
no timeout. If no response before expiry, the keyspace listener reads the shadow key and emits a
**TIMEOUT** response via Lua `response`, then deletes the shadow key.

## Edge cases / acceptance
- Response before timeout → correlated reply, no timeout event.
- No response → TIMEOUT reply on the response stream after `timeout` seconds.
- Multiple workers don't double-process (consumer group).

## Inferred — verify
Exact timeout default exposed in the UI and the `businessId` semantics.
