# Brief — Série de blog posts « Redis Messaging Patterns » (post #1 : DLQ)

> Statut : brief validé (brainstorm du 2026-07-09).
> Prochaine étape : `/spec` sur le premier slice (post DLQ). **Ne rien rédiger/implémenter avant.**

## Problème

Le blog Redis manque de contenu montrant que Redis est un substrat sérieux pour les patterns de
messaging d'entreprise. Cette série (anglais d'abord, français ensuite) utilise la démo open-source
[RedisMessagingPatternsWithJedis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis)
comme fil rouge : chaque post explique **un** pattern, son mécanisme Redis, et renvoie au code du
repo — jamais de code dupliqué qui divergerait du projet.

## Utilisateurs & usage principal

- **Lecteur primaire** : dev backend qui connaît Redis en cache/KV, découvre Streams, consumer
  groups et le pattern DLQ.
- **Lecteur secondaire (prescripteur)** : architecte messaging — il doit trouver dans le post les
  garanties du pattern (atomicité, at-least-once, pas de message perdu) sans lire le code.
- **Usage** : lecture 6-7 min → comprendre le concept → le rejouer soi-même dans RedisInsight ou
  `redis-cli` (charger le Lua, faire les appels), **sans** toucher à la démo Spring/Angular.

## Goals

- Un dossier par post : `blog/<slug>/` avec `<slug>.md` (ou `index.md`) + `img/` pour les schémas.
- Par post : **1 schéma logique** (image statique exportée, style Excalidraw/brand Redis),
  **pseudo-code** de la logique, **courts extraits réels (5–15 lignes) + permaliens GitHub pinnés
  sur un tag**, ~**1200–1500 mots**.
- **Reproduction CLI-first** : le lecteur charge `lua/stream_utils.lua` (`FUNCTION LOAD`) et rejoue
  le pattern via `redis-cli`/RedisInsight ; la démo web n'est que l'illustration visuelle.
- Section multi-langage « appeler la fonction Lua » : **Java (Jedis, code du repo) + Python +
  Node.js + Go + C# + Rust**. Le CMS du blog ne gère pas les onglets de code → les samples sont des
  **mini-projets exécutables versionnés dans le repo** (`blog/<slug>/samples/<lang>/`, avec
  manifestes), le post les **référence par lien** (permalien pinné) au lieu de les inliner.
  Le test plan les exécute réellement (Go/.NET/Rust provisionnés dans la VM).
- Brève intro de série (1 paragraphe) dans le post #1, puis 100 % DLQ.
- **Post #1 = DLQ uniquement**, avec un encart pédagogique **Redis Functions (Lua)** : pourquoi
  c'est mieux que EVAL/EVALSHA/SCRIPT LOAD (fonctions nommées, bibliothèque persistée et répliquée,
  chargée une fois), et comment charger un fichier de fonctions.
- **Cohérence triple** : blog ↔ code de la démo ↔ explications des pages web ↔ `docs/specs/*.md`.
  Tout changement du code de la démo exige une justification puis la **validation explicite de
  l'auteur** avant modification.

## Non-goals

- Ne pas montrer les technologies annexes de la démo (WebSocket, Angular, SockJS, Spring) — elles
  sont du bruit par rapport au pattern.
- Pas de deep-dive prod (HA, cluster, sizing) dans ce format 1500 mots.
- Pas de tutoriel « reconstruire la démo from scratch » : on reproduit le **pattern**, pas l'appli.
- La version française : plus tard, une fois la version anglaise validée.
- Les 11 autres patterns : hors périmètre tant que le post DLQ n'est pas terminé.

## Contraintes clés

- Publication sur le **blog Redis officiel** → images statiques (PNG/SVG), markdown portable,
  contraintes CMS exactes encore inconnues.
- **Permaliens pinnés** → il faut créer un **tag git** (aucun n'existe aujourd'hui) ; le push du tag
  se fait côté host par l'auteur.
- **Redis 8.4+** requis pour `read_claim_or_dlq` (`XREADGROUP … CLAIM`) — caveat lecteur obligatoire.
- Les snippets Python/Node/Go/C# doivent être vérifiés contre les API clientes actuelles
  (Context7), pas de mémoire d'entraînement.

## Top risques & questions ouvertes

1. **Drift blog/code** : l'audit de cohérence (Lua ↔ page web DLQ ↔ `docs/specs/dlq.md`) peut
   révéler des écarts → chaque correctif de code passe par validation de l'auteur.
2. **5 langages × budget 1500 mots** : résolu — le CMS n'a pas d'onglets, donc les samples vivent
   dans `blog/<slug>/samples/` et le post les référence par lien ; seul le pseudo-code et 1–2
   extraits clés restent inline.
3. **Pas de tag git** : sans tag, les permaliens pointent sur `main` et peuvent diverger. Proposition :
   tag `blog-dlq-v1` (ou `v1.0.0`) au moment de la publication.
4. **CMS du blog Redis** : formats d'image, front-matter, coloration syntaxique Lua, onglets —
   à confirmer avant la mise en page finale.

## Premier slice (le plus petit truc qui vaut le coup)

Le **post DLQ complet en anglais** dans `blog/dlq-redis-streams/` :

1. Audit de cohérence : `lua/stream_utils.lua` (`read_claim_or_dlq`) ↔ page `/dlq` ↔
   `docs/specs/dlq.md` — lister les écarts, faire valider tout changement de code.
2. Schéma logique (producer → stream → consumer group → retry N → DLQ) exporté dans `img/`.
3. Rédaction : intro série (1 §) → le problème (poison message) → le pattern → pseudo-code →
   encart Redis Functions → reproduction `redis-cli`/RedisInsight → section FCALL 5 langages →
   lien démo + repo.
4. Vérification : rejouer soi-même chaque commande de la section reproduction contre Redis 8.4.

## Next step

Lancer `/spec` sur le premier slice → `docs/specs/blog-dlq-post.md`.
