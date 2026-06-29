# Spec — Content-Based Routing

Route `/content-routing` · `ContentBasedRoutingController` (`/api/content-routing`) ·
`ContentBasedRoutingService` · Lua `read_claim_or_dlq`.

## Goal
Route messages by **payload content** (here: payment `amount`) into tiered streams, with invalid
input going to DLQ.

## Redis
- Inbound `payments.incoming.v1`, group `payments-router-group`, DLQ `payments.incoming.v1:dlq`.
- Targets: `payments.standard.v1` (0 ≤ amount < 100), `payments.highRisk.v1` (100 ≤ amount < 10k),
  `payments.manualReview.v1` (amount ≥ 10k).

## REST
- `POST /content-routing/submit` — `XADD` a payment to inbound.
- `GET /rules` (thresholds), `GET /streams`, `DELETE /clear`.

## Flow
1 Virtual Thread router polls inbound every 500ms via `read_claim_or_dlq` (count 1, maxDeliver 2).
Parses `amount`, routes to the matching tier with metadata `_routedFrom`, `_routedAt`,
`_routing_reason`, then `XACK`. Negative/invalid amount → throws → not ACK'd → DLQ after 2 tries.
~2s artificial delay per message for UI visibility.

## Acceptance
- $50 → `standard`, $5,000 → `highRisk`, $50,000 → `manualReview`.
- Negative amount → `payments.incoming.v1:dlq` after retries.
