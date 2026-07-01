# Brief — Pattern #12 : LLM Chat avec Redis Streams

> Statut : brief validé (issu du brainstorm sur `docs/llm-chat-streams-spec.md`).
> Prochaine étape : `/spec` sur le premier slice. **Ne pas commencer l'implémentation.**
> Branche : `feat/llm-chat-streams`.

## Problème

Les démos SE sur les LLM montrent « un chatbot » mais rarement *où Redis apporte une valeur
structurelle*. Ce pattern démontre, sur un cas IA concret, **pourquoi Redis Streams** est le bon
substrat pour une conversation LLM là où Pub/Sub (volatile) et List (sans consumer groups ni replay)
échouent : log de conversation auditable et rejouable, reconstruction du contexte, fan-out
multi-consommateurs sans copie, et garantie de traitement même si un worker LLM tombe en pleine
génération. C'est le **pattern de synthèse** (#12) qui capitalise sur les 11 précédents.

## Utilisateurs & usage principal

- **Public** : SA Redis en démo client (banque/enterprise), keynote/salon, souvent hors-ligne.
- **Usage** : l'utilisateur discute avec un assistant ; la réponse s'affiche token par token ;
  un panneau *Redis internals* montre en live les entrées de `chat:{cid}` et l'état des consumer
  groups. La discipline narrative « ça reste une démo Redis, pas une démo chatbot » est centrale.
- **Contrainte forte** : la démo doit **toujours fonctionner** — reproductible, offline, sans clé
  API, sans coût, sans sortie réseau.

## Objectifs (v1)

- Un **stream par conversation** `chat:{cid}` comme source de vérité (ordonnée, immuable, rejouable).
- Reconstruction du contexte LLM via `XREVRANGE chat:{cid} COUNT N`, mémoire bornée (`MAXLEN ~`).
- **Streaming de tokens** via un **stream unique par conversation** `chat:{cid}:tok` (capé), chaque
  token portant `msgId` ; **un seul `TokenListener` à vie** par conversation, le front démultiplexe
  par `msgId` → WebSocket.
- **Fan-out** sur le même stream : `cg:responder`, `cg:moderation`, `cg:analytics` (le même message
  livré à 3 groupes sans copie). Modération = regex/mots-clés simple (illustratif).
- **Résilience** : ≥2 consumers `cg:responder` + boucle `XAUTOCLAIM` périodique + endpoint
  *kill-worker* pour démontrer `XPENDING → XAUTOCLAIM` ; échec répété → `chat:{cid}:dlq`
  (réutilise la logique DLQ existante).
- **LlmClient pluggable, `MockLlmClient` seul en v1** (canned/markov léger, délai réglable ~30–60 ms,
  déterministe). Interface prête pour Ollama en slice ultérieur.
- **Workers lazy par `cid`** : spawn des VT (responder×2, moderation, analytics, token-listener,
  sweeper) à la 1ʳᵉ requête sur un `cid` ; **reap après inactivité** (composant à part entière).
- **Panneau internals** (`XINFO GROUPS` : lag/pending/last-delivered + entrées live) — cœur de la
  valeur, pas une couche optionnelle.
- Réutilise `WebSocketEventService`, le pattern VT de `RedisStreamListenerService`, le `JedisPool`.

## Non-objectifs (v1)

- **Pas d'Ollama ni d'Anthropic** en v1 (Ollama = slice suivant ; Anthropic écarté : introduit une
  surface gestion-de-secret contraire à ADR-0008 / posture VM sans creds, gain démo faible).
- Pas de « vrai LLM » requis pour la démo — le mock pilote toute la mécanique Redis.
- Pas d'archivage long terme / RediSearch / RAG (hook narratif seulement, cf. open questions).
- Pas d'auth/TLS (aligné sur ADR-0008 — démo non déployable en l'état).
- Pas de production-grade (favorise clarté et observabilité).

## Contraintes clés

- **Stack** : identique au repo — Spring Boot 3.5.7, Jedis 7.1, Java 21 Virtual Threads,
  Angular 21, Redis 8.4-alpine, WebSocket/SockJS. Contexte path `/api`. Route front `/llm-chat`.
- **Données Redis** : `chat:{cid}` (turns), `chat:{cid}:tok` (tokens, capé + TTL après complétion),
  `chat:{cid}:dlq`, groupes `cg:responder|cg:moderation|cg:analytics`. Visualisation via `XREVRANGE`
  (lecture seule, jamais `XREADGROUP` pour l'affichage). Nommage colon-separated cohérent.
- **Perf** : ce n'est pas l'argument (résilience/opérabilité l'est). `MAXLEN ~` pour éviter le trim
  exact. Attention au nombre de VT par conversation (~6) et au reaping.
- **Sécurité** : mock par défaut = aucun secret, aucune sortie réseau. Modération = regex simple.

## Risques & questions ouvertes (top)

1. **Cycle de vie des workers lazy-per-cid** — spawn/reap fiable, idempotence de `ensureGroups`,
   éviter les VT orphelins. Risque n°1 de complexité.
2. **Crédibilité de la démo kill-worker** — nécessite ≥2 responders + sweeper `XAUTOCLAIM` actifs ;
   interruption coopérative propre du VT en cours de génération (pas juste un `catch`).
3. **Discipline narrative** — que Redis ne soit pas éclipsé par le chatbot ; le panneau internals
   doit être au premier plan.
4. Ouvert (défauts raisonnables, à confirmer en spec) : modération réelle vs simulée (reco : regex) ;
   persistance longue / hook RAG (reco : laisser expirer en v1) ; réutiliser `read_claim_or_dlq` tel
   quel ou variante LLM.

## Premier slice (le plus petit truc qui vaut la peine)

**Happy path bout-en-bout + les internals**, comme socle avant d'ajouter fan-out/résilience :

1. `POST /api/llm-chat/{cid}/message` → `XADD chat:{cid} role=user`.
2. `cg:responder` (1 consumer) : `XREVRANGE` contexte → `MockLlmClient.generate` → `XADD` tokens sur
   `chat:{cid}:tok` (msgId) → `XADD chat:{cid} role=assistant` → `XACK`.
3. `TokenListener` unique par cid : `XREAD BLOCK` sur `chat:{cid}:tok` → WebSocket (démux front msgId).
4. `GET /api/llm-chat/{cid}/history` (`XRANGE`) + panneau internals (`XINFO GROUPS`).
5. Front `/llm-chat` : bulles user/assistant, rendu token par token, panneau internals dépliable.

Puis, sur ce socle : (slice 2) fan-out `cg:moderation`/`cg:analytics` ; (slice 3) résilience
`XAUTOCLAIM` + kill-worker + DLQ ; (slice 4) `OllamaLlmClient`.

## Prochaine étape

Lancer **`/spec`** sur le premier slice (happy path + internals) pour produire `docs/specs/llm-chat.md`
avec inputs/outputs/edge-cases/critères d'acceptation, puis `/plan-feature` (TDD).
