# Dead Letter Queues sur Redis Streams — tentatives bornées, poison messages, et le nouveau XNACK

> Version française de [`index.md`](index.md). Les termes techniques restent en anglais
> (dead letter queue, stream, consumer group, poison message…) : c'est ce que le lecteur
> tapera dans un moteur de recherche pour trouver de la documentation.

## Une série sur les patterns de messagerie, construits sur Redis

Redis est habituellement présenté comme un cache, mais ses structures de données en font aussi un
véritable support de messagerie à part entière. Ce post ouvre une série qui implémente les patterns
de messagerie d'entreprise classiques — dead letter queues, request/reply, work queues, fan-out,
topic routing, et bien d'autres — directement sur Redis, avec du code exécutable que vous pouvez
cloner et dérouler pas à pas. Chaque pattern vit dans un unique projet compagnon, le
[dépôt Redis Messaging Patterns](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis),
où chacun dispose de sa propre page qui visualise le flux de données en temps réel. Nous commençons
par le pattern que tout consumer durable finit par adopter : la **dead letter queue (DLQ)**.

## Le problème : un seul poison message ne doit pas bloquer la file

Un consumer lit un message, tente de le traiter, et échoue. Peut-être que le payload est malformé,
qu'un service en aval est indisponible, ou que l'enregistrement déclenche un bug. Avec une livraison
at-least-once naïve, le message est simplement re-livré — et échoue encore, et encore. Un seul
**poison message** peut immobiliser une partition, consommer du CPU dans une boucle de retry sans
fin, et masquer le trafic sain accumulé derrière lui.

Le pattern dead letter queue borne ce phénomène. Donnez à chaque message un budget de tentatives
fini ; une fois ce budget épuisé, sortez le message du flux principal vers un stream *dead-letter*
séparé, où il pourra être inspecté, remonté en alerte, ou rejoué plus tard — sans jamais bloquer les
consumers actifs. Bien faite, une DLQ vous offre quatre propriétés qui méritent d'être énoncées
explicitement pour quiconque conçoit par-dessus :

- **Livraison at-least-once** — un message est ré-essayé jusqu'à réussir ou être explicitement envoyé
  en dead-letter ; il n'est jamais perdu silencieusement.
- **Tentatives bornées** — le nombre de tentatives est plafonné par un budget `maxDeliveries`, donc
  un poison message ne peut pas boucler indéfiniment.
- **Aucune perte de message** — le routage vers la DLQ est atomique avec l'acquittement de
  l'original, donc un message n'est jamais dans les deux streams à la fois, ni dans aucun.
- **Isolation** — les poison messages quittent le chemin de traitement, donc un mauvais
  enregistrement ne peut pas affamer les autres.

## Le pattern sur Redis Streams

Redis Streams fournit les primitives des quatre propriétés. Un **stream** est un journal en
append-only ; un **consumer group** suit, message par message, quel consumer le détient actuellement
et combien de fois il a été livré. Quand un consumer lit avec `XREADGROUP`, le message entre dans la
*Pending Entries List* (PEL) du groupe et son compteur de livraisons démarre à 1. Acquittez-le avec
`XACK` et il quitte la PEL ; plantez avant l'acquittement et il reste pending, réclamable par un
autre consumer une fois resté inactif assez longtemps.

![Flux logique du pattern DLQ sur Redis Streams : producteur, consumer group avec compteur de livraisons, balayage au poll suivant vers le stream DLQ](img/dlq-flow.png)

Ce compteur de livraisons *est* le budget de tentatives. À chaque poll, on demande à Redis les
messages pending restés inactifs au-delà d'un seuil `minIdle` (`XPENDING ... IDLE minIdle`), on lit
leur compteur de livraisons, et on balaye vers le stream DLQ ceux qui ont atteint `maxDeliveries`.
Tout le reste — les nouveaux messages plus les pending encore éligibles — est réclamé et rendu pour
traitement en une seule étape atomique. Parce que toute la décision s'exécute côté serveur, deux
consumers ne peuvent jamais être en désaccord sur le fait qu'un message est poison ni sur lequel
d'entre eux le détient. (`read_claim_or_dlq` requiert **Redis 8.4+** pour le claim atomique ; le
chemin d'échec explicite `XNACK` présenté plus loin requiert **Redis 8.8+**.)

## La logique centrale, en quatre étapes

Le dépôt empaquette cette décision sous la forme d'une Redis Function nommée `read_claim_or_dlq`.
Étant donné le stream principal et le stream DLQ comme keys, plus le group, le consumer, le seuil
d'inactivité, la taille de lot et le budget de tentatives comme arguments, elle fait exactement
quatre choses :

```text
read_claim_or_dlq(stream, dlq; group, consumer, minIdle, count, maxDeliver):
  1. pending ← XPENDING stream group IDLE minIdle - + count
     garder les entrées dont le compteur de livraisons ≥ maxDeliver
  2. pour chaque entrée de ce type :                      # hand-off atomique
        XCLAIM → XADD une copie dans dlq → XACK sur stream
  3. claimed ← XREADGROUP group consumer COUNT count
                 CLAIM minIdle STREAMS stream >            # pending + nouveaux, en un coup
  4. return [ messages_to_process, dlq_ids ]
```

Deux subtilités déterminent si votre modèle mental est correct. D'abord, la condition est
`deliveries >= maxDeliver`, et la vérification DLQ de l'étape 1 s'exécute *avant* la re-lecture de
l'étape 3 — elle ne voit donc jamais que les compteurs de livraisons des appels précédents. Ensuite,
`XREADGROUP ... CLAIM` incrémente le compteur de livraisons au moment où il re-livre. Mis bout à
bout : un poison message est livré `maxDeliver` fois, et c'est le poll *suivant* qui le balaye vers
la DLQ. Avec `maxDeliver = 2`, cela fait trois appels — livrer, re-livrer, balayer — espacés chacun
d'au moins `minIdle`. Ce n'est jamais « après N échecs il disparaît instantanément » ; il y a
toujours ce poll de balayage supplémentaire, et le walkthrough ci-dessous le rend visible.

## Pourquoi une Redis Function, et pas `EVAL`

On pourrait livrer ceci comme un script Lua exécuté avec `EVAL`, mais les **Redis Functions**
(Redis 7.0+) conviennent mieux à une logique qui fait véritablement partie de votre modèle de
données. Une function est enregistrée une fois au sein d'une **library** nommée, persistée dans le
RDB/AOF, et répliquée vers les replicas — elle survit donc aux redémarrages et aux failovers, et est
appelable par son nom avec `FCALL`, depuis n'importe quel client, sans upload de script à chaque
appel. À comparer avec `EVAL` / `EVALSHA` / `SCRIPT LOAD`, où le client possède le texte du script,
suit les digests SHA, et doit gérer `NOSCRIPT` en ré-uploadant après un failover ou un vidage du
cache de scripts. Les Functions déplacent toute cette gestion côté serveur. Charger la library
entière tient en une commande :

```bash
redis-cli -x FUNCTION LOAD REPLACE < lua/stream_utils.lua
```

`REPLACE` la rend idempotente — pousser une nouvelle version de la library n'échoue jamais sur un
« already exists » — ce qui est exactement ce que l'on veut dans une étape de déploiement.

## Reproduisez-le en 5 minutes

Tout ce qui suit tourne contre un **Redis 8.8+** vanilla, avec rien d'autre que `redis-cli` — aucun
code applicatif. Démarrez un serveur jetable et récupérez le dépôt de démo (on n'a besoin que d'un
seul fichier Lua) :

```bash
docker run -d --name dlq-demo -p 6379:6379 redis:8.8-alpine
git clone https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis.git
cd RedisMessagingPatternsWithJedis
```

Chargez la library de functions et créez le consumer group (ou lancez
[`samples/setup.sh`](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/setup.sh),
qui fait exactement cela) :

<!-- verify:begin -->
```bash
./blog/dlq-redis-streams/samples/setup.sh
```

Produisez un message bien élevé et un poison message, puis pollez avec la function. Les deux
reviennent pour traitement — première livraison, `deliveries = 1` :

```bash
GOOD=$(redis-cli XADD test-stream '*' type order.created order_id 1001 amount 49.90)
POISON=$(redis-cli XADD test-stream '*' type order.poison order_id 666 amount 0.00)

redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
```

Acquittez le bon ; pour le poison, faites ce que fait un worker planté — rien :

```bash
redis-cli XACK test-stream test-group "$GOOD"
```

Maintenant, attendez au-delà de `minIdle` (100 ms ici) et pollez à nouveau. Le poison message est
re-livré — `deliveries` grimpe à 2, qui est notre budget `maxDeliveries` :

```bash
sleep 0.3
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XPENDING test-stream test-group
# → 1 entrée pending, compteur de livraisons : 2
```

Encore une période d'inactivité, encore un poll — et voici le balayage : la function ne renvoie
**aucun message à traiter**, juste la paire `[original_id, dlq_id]`. Le poison message vit désormais
dans le stream DLQ, et la liste pending est propre :

```bash
sleep 0.3
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XRANGE test-stream:dlq - +
redis-cli XPENDING test-stream test-group
# → total pending : 0
```

Notez le timing : le message a été **livré `maxDeliveries` (2) fois, et c'est le poll *suivant* qui
l'a balayé**. La vérification DLQ s'exécute avant la re-lecture, elle ne voit donc que les compteurs
de livraisons des appels précédents — `maxDeliveries + 1` polls au total, espacés chacun d'au moins
`minIdle`.
<!-- verify:end -->

## Échec explicite avec XNACK (Redis 8.8)

Jusqu'ici, un consumer signale un échec *implicitement* — en n'acquittant pas et en laissant le
message expirer. C'est robuste (un consumer planté ressemble exactement à un consumer lent) mais
lent : chaque retry doit attendre l'expiration de `minIdle`. Redis 8.8 ajoute `XNACK`, un « je te le
rends » explicite qu'un consumer actif peut envoyer dès qu'il connaît l'issue, en trois variantes qui
correspondent proprement à des modes d'échec réels :

- **`FAIL`** — « j'ai essayé et ça a échoué » : libère le message immédiatement, garde le compteur de
  livraisons. Il est ré-réclamable aussitôt, sans attente d'inactivité.
- **`FATAL`** — « c'est du poison » : force le compteur de livraisons à son maximum, pour que le tout
  prochain poll balaye le message directement vers la DLQ.
- **`SILENT`** — « je dois le rendre mais je n'ai jamais vraiment essayé » (un graceful shutdown, par
  exemple) : rembourse le budget en remettant le compteur à 0.

Le contraste avec le chemin par timeout est tout l'intérêt de cette section : un message libéré
**contourne entièrement l'attente `minIdle`**. Observez l'entrée pending juste après un `FAIL` — elle
reste dans la PEL *sans propriétaire* (aucun consumer, `idle = -1`), immédiatement disponible pour le
prochain claim.

<!-- verify:begin -->
```bash
MSG=$(redis-cli XADD test-stream '*' type order.created order_id 2002 amount 12.50)
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2

# « j'ai essayé et échoué » — on le libère MAINTENANT, on garde l'échec au compteur :
redis-cli XNACK test-stream test-group FAIL IDS 1 "$MSG"
redis-cli XPENDING test-stream test-group - + 10
# → l'entrée n'a AUCUN consumer, idle = -1, deliveries maintenu à 1
```

Pas de `sleep` cette fois — un message libéré est immédiatement ré-réclamable :

```bash
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
# → re-livré instantanément (deliveries : 2)
```

Ou sautez complètement les retries. `FATAL` brûle tout le budget d'échec (le compteur bondit au
maximum), donc le tout prochain poll balaye le message vers la DLQ — là encore sans attente :

```bash
redis-cli XNACK test-stream test-group FATAL IDS 1 "$MSG"
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XRANGE test-stream:dlq - +
# → deux entrées maintenant : le poison balayé par timeout + celui-ci
```

Enfin `SILENT`, pour le consumer qui doit rendre du travail *sans* avoir essayé — un graceful
shutdown, par exemple. Le compteur de livraisons est remis à 0 : le budget d'échec est remboursé :

```bash
MSG2=$(redis-cli XADD test-stream '*' type order.created order_id 3003 amount 5.00)
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XNACK test-stream test-group SILENT IDS 1 "$MSG2"
redis-cli XPENDING test-stream test-group - + 10
# → deliveries : 0 — comme si la livraison n'avait jamais eu lieu

redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XACK test-stream test-group "$MSG2"
```
<!-- verify:end -->

## Appelez-la depuis votre langage

`FCALL` n'est qu'une commande Redis, donc tout client courant peut invoquer `read_claim_or_dlq`. La
réponse est toujours la même paire de tableaux imbriqués `[messages_to_process, dlq_ids]` — que
chaque sample parse défensivement, puisque les deux tableaux peuvent être vides. Le dépôt fournit un
sample minimal et exécutable par langage, chacun d'une quarantaine de lignes, chacun lisant la cible
depuis `REDIS_URL` et affichant les messages à traiter ainsi que les éventuelles paires de routage
`[original_id, dlq_id]` :

- [Java — Jedis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/java/src/main/java/DlqExample.java)
- [Python — redis-py](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/python/dlq_example.py)
- [Node.js — node-redis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/node/dlq-example.mjs)
- [Go — go-redis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/go/main.go)
- [.NET — NRedisStack](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/csharp/Program.cs)
- [Rust — redis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/rust/src/main.rs)

Voici l'appel lui-même, extrait du `DLQMessagingService` de la démo — deux keys, cinq arguments, un
aller-retour :

```java
Object result = jedis.fcall(
    FUNCTION_NAME,
    Arrays.asList(params.getStreamName(), params.getDlqStreamName()),
    Arrays.asList(
        params.getConsumerGroup(),
        params.getConsumerName(),
        String.valueOf(params.getMinIdleMs()),
        String.valueOf(params.getCount()),
        String.valueOf(params.getMaxDeliveries())
    )
);
```

Le parsing du résultat qui le suit est le seul vrai travail, et il a la même forme dans chaque
sample. Voir la
[méthode complète](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/src/main/java/com/redis/patterns/service/DLQMessagingService.java#L153-L170)
pour la façon dont les deux tableaux sont dépaquetés.

<!-- forbidden-exempt:begin -->
## Voyez-le tourner, et la suite

Le walkthrough ci-dessus n'est qu'une page d'une démo plus large. Clonez le
[dépôt](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis) et lancez
`./launch-docker.sh --build` ; la page `/dlq` vous laisse ajouter des messages bons et poison, poller
la function, et regarder les entrées défiler à travers le compteur de livraisons jusque dans la DLQ
en temps réel — le même `read_claim_or_dlq` que vous venez d'appeler à la main. La démo se trouve
être un backend Spring Boot avec un frontend Angular qui streame les mises à jour via WebSocket, mais
rien de tout cela n'est porteur pour le pattern : toute la DLQ tient dans la function Lua et les deux
streams, ce qui est précisément la raison pour laquelle elle se porte en six langages en quarante
lignes chacun. Prochain épisode de la série : **request/reply** avec des correlation IDs et des
timeouts via keyspace-expiry. Mêmes streams, une garantie très différente.
<!-- forbidden-exempt:end -->
