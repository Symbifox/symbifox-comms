# Politique de confidentialité : Symbifox Mobile

**Dernière mise à jour : 3 septembre 2026**
**Responsable : Blue Fox Inc.**, <bonjour@symbifox.com>

Symbifox Mobile est un client pour **votre propre** instance Odoo. Elle n'a ni
compte, ni serveur, ni service à elle.

## En une phrase

L'application ne collecte rien, ne transmet rien à Blue Fox ni à aucun tiers, et
ne parle qu'à l'instance Odoo que vous lui désignez vous-même.

## Ce à quoi l'application accède

- **Votre instance Odoo**, celle dont vous entrez l'adresse au premier
  lancement. Elle y lit et y écrit ce que votre compte Odoo vous permet déjà :
  vos courriels, les textos des lignes d'affaires, votre agenda, vos tâches.
- **Le microphone**, uniquement pendant un appel ou une dictée, et seulement
  après que vous l'ayez autorisé. Il n'est jamais ouvert en dehors de ces deux
  gestes.
- **Le nom et les couleurs de marque de votre société**, lus sur la même
  instance, pour adapter l'apparence.

## 🔴 Ce à quoi elle n'accède pas

L'application ne demande **aucune** des permissions qui donneraient accès au
contenu de votre téléphone :

- pas de lecture des **SMS ou MMS** de votre carte SIM ;
- pas de lecture du **journal d'appels** ;
- pas de lecture de vos **contacts** ;
- pas de **localisation**, pas de caméra, pas de stockage partagé.

Les textos qu'elle affiche sont ceux des lignes d'affaires de votre instance
Odoo, pas ceux de votre téléphone. Une autre application, **BF SMS Relay**,
fait ce travail-là ; elle est distincte précisément pour que l'installation de
Symbifox Mobile n'oblige personne à donner accès à ses textos personnels.

Les permissions déclarées sont vérifiables : Internet, notifications,
microphone, service au premier plan, maintien en éveil, et l'affichage d'un
appel entrant sur l'écran verrouillé.

## Ce que l'application conserve, et où

Tout reste **sur votre appareil**, dans son stockage privé, et nulle part
ailleurs :

| Ce qui est conservé | Comment |
|---|---|
| Le jeton d'accès à votre instance | chiffré (Android Keystore) |
| Un cache de votre boîte : première page de chaque dossier et conversations récemment ouvertes, **corps des messages compris** | fichiers privés à l'application |
| L'adresse de l'instance, son nom et ses couleurs | fichiers privés à l'application |
| Vos préférences : thème, gestes, tri | fichiers privés à l'application |

Le cache existe pour que l'application s'ouvre sur votre boîte plutôt que sur un
message d'erreur quand le réseau manque. Il n'est pas chiffré séparément : il
est protégé par le cloisonnement d'Android, comme les données de toute
application, et par le verrouillage de votre appareil.

⚠️ Si le magasin de clés du téléphone se dérobe, le jeton retombe dans le
stockage privé ordinaire de l'application plutôt que d'être chiffré. Il reste
inaccessible aux autres applications, mais nous préférons le dire.

**La sauvegarde automatique d'Android est désactivée** : rien ne part chez
Google. Les échanges avec votre instance se font en `https` uniquement, le
trafic en clair étant refusé par l'application.

## Les notifications

Les notifications passent par **UnifiedPush**, et non par les services de
Google. Ni Google Play Services, ni Firebase.

Concrètement : vous installez sur votre téléphone une application dite
*distributeur*, par exemple ntfy. Elle vous donne une adresse de réception, que
Symbifox Mobile transmet à votre instance Odoo pour que celle-ci sache où
pousser.

⚠️ **Ce qu'il faut savoir** : une notification poussée porte un titre et un
aperçu, par exemple l'expéditeur et le début d'un message. Ce contenu **transite
par le serveur du distributeur que vous avez choisi**. Si ce serveur est celui
d'un service public, c'est un tiers dans le chemin. Un distributeur pointant sur
un serveur que vous ou votre organisation hébergez évite ce tiers.

Vous pouvez ne pas installer de distributeur : l'application fonctionne, elle
relit simplement ses données à l'ouverture au lieu d'être prévenue.

## Ce que l'application transmet, et à qui

Uniquement à **l'instance Odoo que vous avez désignée**, et, pour les appels, au
serveur téléphonique que cette instance vous indique.

L'application ne contacte **aucun autre serveur**. Il n'y a ni mesure
d'audience, ni télémétrie, ni rapport d'erreur, ni publicité, ni code chargé à
distance. Blue Fox Inc. ne reçoit aucune donnée de votre usage.

Aucun mot de passe ne transite par l'application : la connexion se fait dans la
page web de votre instance, qui rend un jeton à usage unique.

## Ce que l'application ne fait pas

- Elle ne vend, ne loue ni ne partage aucune donnée.
- Elle ne se connecte à aucun service de Blue Fox.
- Elle n'observe rien de ce que vous faites ailleurs sur le téléphone.

## Conservation et suppression

Vos données vivent sur votre instance Odoo, et leur conservation y est réglée
par votre organisation. Sur l'appareil :

- **désinstaller** l'application efface tout ce qu'elle gardait ;
- **se déconnecter** dans l'application retire le jeton et le cache ;
- depuis votre instance, la page **« Mes appareils »** (`/my/appareils`) liste
  les appareils appariés et permet d'en retirer un, ou tous.

⚠️ Retirer un appareil ne l'efface pas : il garde son jeton, celui-ci n'ouvre
simplement plus rien.

## Vos droits

Comme aucune donnée personnelle n'est collectée par Blue Fox Inc., il n'y a rien
à consulter, corriger ou supprimer de notre côté. Vos données restent sous votre
contrôle, sur votre appareil et sur votre instance Odoo. Pour toute question :
<bonjour@symbifox.com>.

## Modifications

Toute modification de cette politique sera publiée à cette adresse, avec sa
date.
