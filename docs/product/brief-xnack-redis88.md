# Brief — Redis 8.8 + XNACK explicite sur la page /dlq (prérequis du blog post DLQ)

> Statut : brief validé (brainstorm du 2026-07-09, comportements vérifiés empiriquement sur
> `redis:8.8-alpine`). **Bloque** `docs/specs/blog-dlq-post.md` / son `.plan.md` — à amender ici.
> Prochaine étape : `/spec` sur le premier slice. **Ne pas commencer l'implémentation.**

## Problème

La démo tourne sur Redis 8.4 et ne montre que l'échec **implicite** (pas d'ACK → timeout d'idle).
Redis **8.8** introduit **`XNACK`** — le NACK explicite natif des Streams — qui change la
sémantique d'échec : re-livraison immédiate sans attendre `minIdle`, et pilotage du budget
d'échecs (`deliveries`). Le blog post DLQ publié sur le blog Redis ne peut pas ignorer la façon
moderne de NACKer ; il faut donc migrer la démo en 8.8 et exposer XNACK dans l'UI avant de rédiger.

## Faits vérifiés empiriquement (2026-07-09, redis:8.8-alpine 8.8.0)

- Syntaxe : `XNACK key group <SILENT|FAIL|FATAL> IDS n id... [RETRYCOUNT n] [FORCE]`.
- Effet : le message reste en PEL mais **sans propriétaire** (`consumer` vide, `idle = -1`) et
  redevient claimable **immédiatement** (court-circuite `minIdle`).
- Budget d'échecs (`deliveries`) : `SILENT` → **0** (livraison « annulée », rien n'a été tenté) ;
  `FAIL` → **conservé** (échec compté) ; `FATAL` → **`Long.MAX`** (poison assumé).
- `XREADGROUP ... >` ne redélivre PAS un message relâché — seul le chemin **claim**
  (`XREADGROUP ... CLAIM` / `XCLAIM` / `XAUTOCLAIM`) le récupère.
- **`read_claim_or_dlq` est compatible sans modification** : `FATAL` → balayé en DLQ au `FCALL`
  suivant *sans attendre `minIdle`* ; `FAIL` → re-délivré immédiatement, compteur incrémenté.

## Utilisateurs & usage

SA Redis en démo (page `/dlq`), lecteurs du blog post #1. Sémantique à raconter :
**ACK** = OK · **crash sans ACK** = échec implicite (attend `minIdle`, budget consommé) ·
**`XNACK FAIL`** = échec explicite (immédiat, budget consommé) · **`XNACK FATAL`** = poison
(immédiat → DLQ au prochain poll) · **`XNACK SILENT`** = rendu sans tentative (immédiat, budget
remboursé — cas type : shutdown propre d'un worker).

## Goals

- **Upgrade Redis 8.4 → 8.8 partout** : docker-compose/`launch-docker.sh`, image des tests
  d'intégration (`AbstractRedisIntegrationTest`), README, `CLAUDE.md`, ADR-0001/0004 (« 8.4+ » →
  « 8.8+ »), `lua/stream_utils.lua` en-tête. `mvn test` doit rester vert.
- **3 boutons sur `/dlq`** : « Process & explicit fail » (`FAIL`), « Process & poison » (`FATAL`),
  « Process & release (silent) » (`SILENT`) — chacun : lire un message puis XNACK au lieu d'ACK.
- **Backend** : endpoint REST (ex. `POST /api/dlq/nack` `{id, mode}`) appelant XNACK en **commande
  directe Jedis** — méthode typée si un Jedis récent l'offre, sinon `sendCommand` générique
  (**vérifier via Context7 au spec** ; Jedis 7.1.0 actuel probablement sans support typé). Pas de
  wrapper Lua : mono-commande O(1), aucune justification d'atomicité (ADR-0004).
- **UI XPENDING** : afficher proprement l'état « relâché » (consumer vide, `idle = -1`,
  `deliveries = Long.MAX` → badge « poison/∞ », pas le nombre brut).
- **Docs en phase** : `docs/specs/dlq.md`, `docs/diagrams/dlq.md`, README, page `/dlq` — nuancer la
  règle « chaque appel espacé de ≥ minIdleMs » : elle ne vaut que pour l'échec **implicite** ;
  XNACK court-circuite l'attente.
- **Amender le blog** : `docs/specs/blog-dlq-post.md` + `.plan.md` — section « explicit failure
  (XNACK) » dans le post #1, budget **1500–1800 mots**, walkthrough enrichi (FAIL/FATAL/SILENT),
  caveat lecteur « Redis 8.8+ », re-vérifier la séquence 3-FCALL sur 8.8.

## Non-goals

- XNACK sur les autres pages/patterns (work-queue, fan-out…) — plus tard si utile.
- Exposer `RETRYCOUNT`/`FORCE` dans l'UI (documentés seulement).
- Automatiser le scénario shutdown-propre (SILENT est déclenché par bouton, pas par un lifecycle).
- Toute refonte du flux DLQ existant : `read_claim_or_dlq` ne change pas (prouvé compatible).

## Contraintes clés

- Image `redis:8.8-alpine` (existe, testée). RedisInsight inchangé.
- Support Jedis XNACK inconnu → à vérifier (Context7 + Maven Central) avant d'écrire le spec.
- Les 40 tests backend existants doivent passer sur 8.8 sans modification de comportement.
- Gate habituel : tout changement au-delà de ce périmètre validé par l'auteur.

## Top risques & questions ouvertes

1. **Jedis** : pas de méthode typée → `sendCommand` avec `Protocol.Command` custom ou arg brut ;
   vérifier la sérialisation exacte (`IDS 1 <id>`). Si un Jedis plus récent gère XNACK, décider
   upgrade vs sendCommand au spec.
2. **Affichage `Long.MAX`** : le stream-viewer et les stats ne doivent pas afficher
   `9223372036854775807` brut ; risque de confusion en démo.
3. **Récit du blog** : 3 modes + timeout dans 1500–1800 mots — garder le schéma logique lisible
   (le sweep « next poll » reste la thèse ; XNACK est l'accélérateur).
4. Comportement `XNACK` sur message **non pending** / déjà ACKé (à caractériser au spec : erreur ?
   no-op ? rôle exact de `FORCE`).

## Premier slice

L'upgrade + le bouton le plus démonstratif, bout en bout :
1. Bump 8.4 → 8.8 partout + `mvn test` vert + smoke `launch-docker.sh`.
2. Endpoint `POST /api/dlq/nack {id, mode}` (Jedis direct, 3 modes acceptés d'emblée).
3. Les 3 boutons UI + affichage « released/poison » dans la vue pending.
4. Docs synchronisées (specs/diagrams/README/page) + amendement du spec/plan du blog post.

## Next step

`/spec` sur ce slice → `docs/specs/xnack-redis88.md` (contrat REST exact, states UI, plan de tests
incluant les 4 caractérisations XNACK restantes), puis ré-amender `blog-dlq-post.{md,plan.md}`.
