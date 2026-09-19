#!/usr/bin/env python3
"""Fabrique les icônes de l'application à partir de l'illustration d'origine.

    python3 tools/fabriquer_icones.py [--play] [--fdroid]

Même script dans toutes les applications Symbifox (BF #25719) : seules les
constantes du haut changent d'un dépôt à l'autre. Les applications de la famille
doivent se reconnaître dans un tiroir, et une illustration cadrée autrement que
sa voisine se lit comme une erreur.

🔴 **Un fond transparent est impossible au lanceur.** `AdaptiveIconDrawable.draw()`
d'AOSP peint du NOIR avant de poser les couches : un `<background>` transparent
sort en disque noir. Et une icône NON adaptative se fait poser un disque blanc
par le lanceur, et rétrécir dedans. D'où un fond, choisi par Olivier le
2026-09-14 : le bleu très pâle `@color/fond_lanceur` (#EAF6FC), une teinte à
10 % du bleu Blue Fox. Vérifié sur le lanceur Pixel, thèmes clair et sombre.

Ce qui sort :

* `<COUCHE_AVANT>.png`, la couche avant de l'icône adaptative, aux cinq
  densités, sur une toile de 108 dp. Elle sert aussi de couche monochrome, sauf
  si `COUCHE_MONOCHROME` est nommée (Symbifox Mobile, voir `silhouette`).
  ⚠️ L'illustration est ajustée par son RAYON, pas par sa boîte : le pixel
  opaque le plus éloigné du centre tombe à 35 dp, juste en dedans du disque de
  36 dp que découpe le lanceur rond. Ajuster la boîte à 72 dp rogne les coins
  (la clé de Tokens, la carte de Compte, l'anneau de Pastilles) ; l'ajuster à la
  zone sûre de 66 dp laisse les illustrations carrées plus petites que leurs
  voisines.
* `ic_launcher.png` (et `ic_launcher_round.png` si le dépôt en porte une),
  l'icône héritée : le même disque pâle, pour les rares surfaces qui la lisent
  encore (minSdk 26, le lanceur prend l'adaptative).
* avec `--play`, l'icône 512 de Google Play dans `fastlane/` : carré opaque sur
  le même fond, Play découpant lui-même ses coins arrondis. ⚠️ Sur demande
  seulement : une fiche de boutique en préparation n'est pas forcément commitée.
* avec `--fdroid`, l'icône de la fiche F-Droid, **transparente et pleine
  taille** : le client F-Droid l'affiche telle quelle, rien n'y peint de fond.
  🔴 C'est ce fichier-là, dans `fdroid-symbifox/metadata/`, que l'index publie,
  PAS l'icône de l'APK.
"""

import os
import pathlib
import re
import sys

from PIL import Image, ImageDraw

RACINE = pathlib.Path(__file__).resolve().parent.parent
SOURCE = RACINE / "tools" / "icone_source.png"
RES = RACINE / "app" / "src" / "main" / "res"
FDROID = pathlib.Path(os.environ.get("FDROID_METADATA", "../fdroid-symbifox/metadata"))

# Le nom de la couche avant dans CE dépôt.
COUCHE_AVANT = "ic_launcher_foreground"
# La couche des icônes thématiques, quand elle n'est pas la couche avant.
COUCHE_MONOCHROME = "ic_launcher_monochrome"

# `@color/fond_lanceur`, recopié ici pour l'icône héritée et celle de Play.
FOND = (0xEA, 0xF6, 0xFC, 255)

# Rayon du dessin dans le disque du lanceur, en dp (le disque en fait 36).
RAYON_DP = 35
# Part de la toile occupée par le dessin, pour Play (coins arrondis à 20 %) et
# pour F-Droid (rien n'y découpe).
PART_PLAY = 0.84
PART_FDROID = 0.96

# Sous cette opacité, un pixel ne compte pas dans le cadrage : ombres douces et
# voiles presque invisibles décaleraient le dessin sans qu'on les voie.
SEUIL_ALPHA = 32

DENSITES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def charger() -> Image.Image:
    """L'illustration, débarrassée de son voile quasi transparent.

    ⚠️ L'illustration de SMS Relay traîne un carré d'opacité 6 sur 255 : invisible
    à l'œil, mais il fausse la boîte, et il sortirait en carré gris sur un fond
    clair une fois agrandi.
    """
    source = Image.open(SOURCE).convert("RGBA")
    alpha = source.getchannel("A").point(lambda a: 0 if a <= 10 else a)
    source.putalpha(alpha)
    return source


def mesurer(source: Image.Image):
    """Boîte du dessin visible, et rayon du pixel le plus éloigné de son centre."""
    masque = source.getchannel("A").point(lambda a: 255 if a >= SEUIL_ALPHA else 0)
    boite = masque.getbbox()
    if boite is None:
        raise SystemExit("l'illustration est entièrement transparente")
    x0, y0, x1, y1 = boite
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    donnees = masque.crop(boite).tobytes()
    largeur = x1 - x0
    rayon2 = 0.0
    for i, a in enumerate(donnees):
        if a:
            x = x0 + i % largeur + 0.5
            y = y0 + i // largeur + 0.5
            rayon2 = max(rayon2, (x - cx) ** 2 + (y - cy) ** 2)
    return boite, rayon2 ** 0.5


def poser(dessin: Image.Image, facteur: float, toile: int, fond) -> Image.Image:
    """Pose [dessin] réduit de [facteur], centré sur une toile carrée."""
    taille = (max(1, round(dessin.width * facteur)), max(1, round(dessin.height * facteur)))
    reduit = dessin.resize(taille, Image.LANCZOS)
    sortie = Image.new("RGBA", (toile, toile), fond)
    sortie.alpha_composite(reduit, ((toile - taille[0]) // 2, (toile - taille[1]) // 2))
    return sortie


# En dessous de ce seuil de SATURATION, un pixel de la couche monochrome est ôté.
SEUIL_SATURATION = 0.55


def silhouette(couche: Image.Image) -> Image.Image:
    """La couche thématique d'Android 13+ : noir opaque, parties claires ôtées.

    🔴 Le système TEINTE cette couche lui-même : seule l'alpha compte. Une
    illustration en dégradés n'y est pas réductible, il faut une silhouette. Sur
    la mallette de Symbifox Mobile, on ôte ce qui n'est pas le bleu du boîtier
    (la marque, la ferrure) pour que le renard s'y lise en creux. ⚠️ Couper sur
    la luminosité ne retirait que 4,5 % de l'image : le renard va du blanc au
    bleu pâle. La saturation sépare franchement les deux.
    """
    pixels = couche.load()
    sortie = Image.new("RGBA", couche.size, (0, 0, 0, 0))
    dessin = sortie.load()
    for y in range(couche.height):
        for x in range(couche.width):
            r, v, b, a = pixels[x, y]
            if a < 40:
                continue
            haut = max(r, v, b)
            if haut == 0 or (haut - min(r, v, b)) / haut < SEUIL_SATURATION:
                continue
            dessin[x, y] = (0, 0, 0, a)
    return sortie


def disque(toile: int) -> Image.Image:
    """Le disque pâle de l'icône héritée, lissé en le traçant quatre fois plus grand."""
    grand = Image.new("L", (toile * 4, toile * 4), 0)
    ImageDraw.Draw(grand).ellipse((0, 0, toile * 4 - 1, toile * 4 - 1), fill=255)
    fond = Image.new("RGBA", (toile, toile), FOND)
    fond.putalpha(grand.resize((toile, toile), Image.LANCZOS))
    return fond


def paquet() -> str:
    gradle = (RACINE / "app" / "build.gradle.kts").read_text()
    trouve = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)
    if not trouve:
        raise SystemExit("applicationId introuvable dans app/build.gradle.kts")
    return trouve.group(1)


def main() -> None:
    source = charger()
    boite, rayon = mesurer(source)
    dessin = source.crop(boite)
    cote = max(dessin.width, dessin.height)

    for nom, echelle in DENSITES.items():
        dossier = RES / f"mipmap-{nom}"
        dossier.mkdir(parents=True, exist_ok=True)

        dp = echelle  # pixels par dp à cette densité (mdpi = 1)
        avant = poser(dessin, RAYON_DP * dp / rayon, round(108 * dp), (0, 0, 0, 0))
        avant.save(dossier / f"{COUCHE_AVANT}.png", optimize=True)
        if COUCHE_MONOCHROME:
            silhouette(avant).save(dossier / f"{COUCHE_MONOCHROME}.png", optimize=True)

        toile = round(48 * dp)
        heritee = disque(toile)
        heritee.alpha_composite(poser(dessin, RAYON_DP / 36 * (toile / 2) / rayon, toile, (0, 0, 0, 0)))
        heritee.save(dossier / "ic_launcher.png", optimize=True)
        if (dossier / "ic_launcher_round.png").exists():
            heritee.save(dossier / "ic_launcher_round.png", optimize=True)
        print(f"mipmap-{nom} : avant {avant.width} px, héritée {toile} px")

    fastlane = RACINE / "fastlane" / "metadata" / "android"
    if "--play" in sys.argv:
        if not fastlane.is_dir():
            raise SystemExit(f"aucune fiche Google Play : {fastlane}")
        play = poser(dessin, 512 * PART_PLAY / cote, 512, FOND).convert("RGB")
        for langue in sorted(p.name for p in fastlane.iterdir() if p.is_dir()):
            cible = fastlane / langue / "images" / "icon.png"
            cible.parent.mkdir(parents=True, exist_ok=True)
            play.save(cible, optimize=True)
            print(f"Play {langue} : {cible}")

    if "--fdroid" in sys.argv:
        cible = FDROID / paquet() / "en-US" / "icon.png"
        if not cible.parent.is_dir():
            raise SystemExit(f"aucune fiche F-Droid pour ce paquet : {cible.parent}")
        poser(dessin, 512 * PART_FDROID / cote, 512, (0, 0, 0, 0)).save(cible, optimize=True)
        print(f"F-Droid : {cible}")


if __name__ == "__main__":
    main()
