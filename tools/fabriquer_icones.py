#!/usr/bin/env python3
"""Fabrique les icônes du lanceur à partir de l'illustration d'origine.

    /home/livv/tentaclaude/.venv/bin/python tools/fabriquer_icones.py

Deux couches sortent de la même image, et elles ne servent pas à la même chose.

* `ic_launcher_foreground.png`, la couche AVANT de l'icône adaptative, sur une
  toile de 108 dp dont seuls les **66 dp du centre** sont sûrs. ⚠️ Le lanceur
  découpe le reste, et il le découpe différemment selon l'appareil : tout ce qui
  déborde de la zone sûre est un pari. Le cadrage reprend au dixième de pour
  cent celui de l'icône précédente (61,1 % de la toile, centrée), mesuré sur ses
  fichiers : les applications de la famille doivent se reconnaître dans un
  tiroir, et une illustration plus grosse que sa voisine se lit comme une
  erreur.

* `ic_launcher_monochrome.png`, la couche des icônes thématiques d'Android 13+.
  🔴 Le système la TEINTE lui-même : seule l'alpha compte, la couleur est
  jetée. Une illustration en dégradés n'y est pas réductible, il faut une
  silhouette. On prend donc la découpe de la mallette, dont on ÔTE tout ce qui
  n'est pas le bleu du boîtier — la marque et la ferrure — pour que le renard
  s'y lise en creux. Sans ce retrait, l'icône thématique serait une tache
  pleine.

⚠️ Le fond de l'icône adaptative n'est PAS ici : c'est `@color/ic_launcher_background`,
l'anthracite Blue Fox, et il reste tel quel. L'illustration est transparente sur
ses bords, donc c'est lui qu'on voit autour de la mallette.
"""

import pathlib

from PIL import Image

RACINE = pathlib.Path(__file__).resolve().parent.parent
SOURCE = RACINE / "tools" / "icone_source.png"
RES = RACINE / "app" / "src" / "main" / "res"

# 66 dp de zone sûre sur une toile de 108 dp. La valeur est écrite en clair
# plutôt que déduite : c'est une contrainte d'Android, pas un choix de goût.
PART_SURE = 66 / 108

# Les cinq densités, en pixels pour 108 dp.
DENSITES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# En dessous de ce seuil de SATURATION, un pixel n'appartient pas au bleu du
# boîtier : c'est la marque claire, ou la ferrure argentée.
#
# ⚠️ Le premier essai coupait sur la LUMINOSITÉ, et ne retirait que 4,5 % de
# l'image : le renard va du blanc au bleu pâle, donc l'essentiel restait
# au-dessus du seuil et la couche thématique sortait en tache pleine. La
# saturation sépare franchement les deux — mesurée sur l'illustration, le
# boîtier est à 0,86 de médiane, et 16 % des pixels tombent sous 0,55.
SEUIL_SATURATION = 0.55


def recadrer(source: Image.Image) -> Image.Image:
    """L'illustration, rognée sur son dessin et centrée dans sa zone sûre."""
    boite = source.split()[3].getbbox()
    if boite is None:
        raise SystemExit("l'illustration est entièrement transparente")
    return source.crop(boite)


def poser(dessin: Image.Image, cote: int) -> Image.Image:
    """Pose [dessin] au centre d'une toile de [cote], à l'échelle sûre."""
    large = int(round(cote * PART_SURE))
    facteur = large / dessin.width
    taille = (large, max(1, int(round(dessin.height * facteur))))
    reduit = dessin.resize(taille, Image.LANCZOS)
    toile = Image.new("RGBA", (cote, cote), (0, 0, 0, 0))
    toile.paste(
        reduit,
        ((cote - reduit.width) // 2, (cote - reduit.height) // 2),
        reduit,
    )
    return toile


def silhouette(couche: Image.Image) -> Image.Image:
    """La couche thématique : noir opaque, marque claire ôtée."""
    pixels = couche.load()
    sortie = Image.new("RGBA", couche.size, (0, 0, 0, 0))
    dessin = sortie.load()
    for y in range(couche.height):
        for x in range(couche.width):
            r, v, b, a = pixels[x, y]
            if a < 40:
                continue
            plus_haut = max(r, v, b)
            saturation = 0.0 if plus_haut == 0 else (plus_haut - min(r, v, b)) / plus_haut
            if saturation < SEUIL_SATURATION:
                continue
            dessin[x, y] = (0, 0, 0, a)
    return sortie


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")
    dessin = recadrer(source)
    for densite, cote in DENSITES.items():
        dossier = RES / f"mipmap-{densite}"
        dossier.mkdir(parents=True, exist_ok=True)
        avant = poser(dessin, cote)
        avant.save(dossier / "ic_launcher_foreground.png")
        silhouette(avant).save(dossier / "ic_launcher_monochrome.png")
        print(f"{densite:8s} {cote}px")


if __name__ == "__main__":
    main()
