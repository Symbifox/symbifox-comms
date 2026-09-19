package com.bluefoxconsultant.sms.socle

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.R

/**
 * Le socle visuel de la famille Symbifox (BF #25765).
 *
 * ⚠️ **Ce fichier est IDENTIQUE dans les cinq dépôts d'application.** Seules la
 * ligne `package` et l'import de `R` changent. C'est le parti pris de #25719
 * pour `fabriquer_icones.py`, repris ici : pas de chaîne de publication à
 * tenir, au prix de cinq copies à resynchroniser quand le socle bouge. Une
 * modification faite dans un seul dépôt est un défaut, pas une variante.
 *
 * Ce qu'il tient, et pourquoi il existe : avant lui, les cinq applications
 * avaient chacune leur palette, leur typo et leur calcul de contraste. Sur le
 * locataire Blue Fox, dont l'accent est `#29ABE2`, Symbifox Pastilles écrivait
 * **blanc** sur ses boutons pendant que Tokens et Chronomètre y écrivaient
 * **encre** : deux applications sœurs, le même téléphone, la même instance,
 * deux réponses.
 */
object Symbifox {

    /** Le paquet de Symbifox Compte, l'application qui tient les comptes. */
    const val PAQUET_COMPTE = "com.bluefoxconsultant.compte"

    /** Les couleurs du produit, jusqu'à ce qu'une instance dise autre chose. */
    val BLEU = Color(0xFF176CF2)
    val BLEU_APPOINT = Color(0xFF0E3E8C)

    /**
     * Les neutres.
     *
     * ⚠️ Volontairement NEUTRES, et non l'anthracite `#2E3132` de Blue Fox :
     * ces applications s'habillent aux couleurs du locataire, et poser la
     * marque d'une maison sur le téléphone du client d'un autre serait
     * exactement ce que `bluefox_branding` sert à éviter. La maison se
     * reconnaît à l'accent que le serveur déclare, jamais au fond.
     */
    val ANTHRACITE = Color(0xFF1B1F24)
    val ENCRE = Color(0xFF12161A)

    /** Le fond commun des icônes du lanceur, arbitré en #25719. */
    val FOND_LANCEUR = Color(0xFFEAF6FC)

    /**
     * Ce qui s'écrit PAR-DESSUS une couleur, calculé depuis sa luminance.
     *
     * 🔴 **La formule est celle de la norme, pas la moyenne pondérée des
     * canaux bruts.** Les deux ne donnent pas la même réponse : `#29ABE2` pèse
     * 0,351 en luminance normalisée et 0,578 en moyenne brute. Tokens et
     * Chronomètre employaient la seconde au seuil 0,55 et écrivaient donc en
     * encre là où Pastilles écrivait en blanc.
     *
     * 🔴 **Le seuil est 0,45, et ce n'est PAS le point de bascule du meilleur
     * contraste** (qui tombe à 0,196). Blanc sur `#29ABE2` rend 2,62:1 contre
     * 6,94:1 pour l'encre, et c'est le blanc qui a été retenu le 2026-09-13,
     * en connaissance de cause, après rendu des quatre options : l'encre a été
     * écartée comme « weird with black text ». Un seuil calculé sur le meilleur
     * contraste défait cette décision. Il reste à 0,45, qui la préserve tout en
     * rattrapant les marques vraiment pâles.
     */
    fun surCouleur(fond: Color): Color {
        fun canal(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f
            else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        val luminance =
            0.2126f * canal(fond.red) + 0.7152f * canal(fond.green) + 0.0722f * canal(fond.blue)
        return if (luminance > 0.45f) ENCRE else Color.White
    }

    /**
     * Le schéma de couleurs, dérivé d'un accent.
     *
     * 🔴 **Tous les rôles de Material 3 sont nommés.** Ceux qu'on laisse au
     * défaut sont MAUVES — conteneurs secondaires, teintes d'élévation, feuille
     * du bas, menus — et le mauve ressort exactement là où personne n'a pensé à
     * regarder. Payé une fois dans Pastilles, à ne pas repayer.
     *
     * ⚠️ En sombre, le fond reste un anthracite NEUTRE simplement teinté de
     * quelques pour cent vers l'accent. Un fond pris dans la couleur de marque
     * donne un écran saturé sur lequel rien ne se lit.
     */
    fun schema(accent: Color, appoint: Color, sombre: Boolean): ColorScheme {
        val base = if (sombre) SOMBRE else CLAIR
        val fond = if (sombre) lerp(base.background, accent, 0.05f) else base.background
        val surface = if (sombre) lerp(base.surface, accent, 0.06f) else base.surface
        return base.copy(
            primary = accent,
            onPrimary = surCouleur(accent),
            primaryContainer = lerp(surface, accent, if (sombre) 0.28f else 0.14f),
            onPrimaryContainer = base.onSurface,
            secondary = appoint,
            onSecondary = surCouleur(appoint),
            // ⚠️ Neutres et non teintés : l'accent reste la SEULE couleur de
            // marque à l'écran. Le bouton Pause du Chronomètre était mauve ici.
            secondaryContainer = base.surfaceVariant,
            onSecondaryContainer = base.onSurface,
            tertiary = accent,
            onTertiary = surCouleur(accent),
            tertiaryContainer = lerp(surface, accent, if (sombre) 0.18f else 0.10f),
            onTertiaryContainer = base.onSurface,
            background = fond,
            onBackground = base.onBackground,
            surface = surface,
            onSurface = base.onSurface,
            surfaceTint = accent,
            surfaceContainerLowest = if (sombre) lerp(surface, Color.Black, 0.25f) else Color.White,
            surfaceContainerLow = if (sombre) lerp(surface, Color.Black, 0.10f) else Color(0xFFFBFCFD),
            surfaceContainer = surface,
            surfaceContainerHigh = if (sombre) lerp(surface, Color.White, 0.05f) else Color(0xFFF1F3F6),
            surfaceContainerHighest = if (sombre) lerp(surface, Color.White, 0.09f) else Color(0xFFE9ECF0),
            surfaceBright = if (sombre) lerp(surface, Color.White, 0.12f) else Color.White,
            surfaceDim = if (sombre) lerp(surface, Color.Black, 0.20f) else Color(0xFFE2E6EA),
            inverseSurface = if (sombre) Color(0xFFECEFF1) else ENCRE,
            inverseOnSurface = if (sombre) ENCRE else Color(0xFFECEFF1),
            inversePrimary = accent,
        )
    }

    private val CLAIR = lightColorScheme(
        background = Color(0xFFF6F7F9),
        onBackground = ENCRE,
        surface = Color.White,
        onSurface = ENCRE,
        surfaceVariant = Color(0xFFEDF0F3),
        onSurfaceVariant = Color(0xFF5A646E),
        outline = Color(0xFFD5DAE0),
        outlineVariant = Color(0xFFE3E7EB),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFFBE9E7),
        onErrorContainer = Color(0xFF5F1410),
    )

    private val SOMBRE = darkColorScheme(
        background = Color(0xFF121518),
        onBackground = Color(0xFFECEFF1),
        surface = ANTHRACITE,
        onSurface = Color(0xFFECEFF1),
        surfaceVariant = Color(0xFF252A30),
        onSurfaceVariant = Color(0xFFA9B2BB),
        outline = Color(0xFF3A4148),
        outlineVariant = Color(0xFF2C3238),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF4A1F1C),
        onErrorContainer = Color(0xFFF9DEDC),
    )

    /**
     * Lexend, la typo de la maison — celle des documents brandés et celle que
     * sert symbifox.com. Embarquée plutôt que téléchargée : elle doit rendre à
     * l'identique hors ligne et sur un appareil sans services Google.
     */
    private val LEXEND = FontFamily(
        Font(R.font.lexend_regular, FontWeight.Normal),
        Font(R.font.lexend_semibold, FontWeight.Medium),
        Font(R.font.lexend_semibold, FontWeight.SemiBold),
        Font(R.font.lexend_bold, FontWeight.Bold),
    )

    /**
     * Posée sur TOUTE l'échelle typographique, pas sur quelques titres : une
     * application à moitié brandée se remarque plus qu'une qui ne l'est pas.
     * Lexend court un peu large, d'où l'approche resserrée sur les styles de
     * liste denses.
     */
    val TYPOGRAPHIE: Typography = Typography().let { base ->
        fun TextStyle.lexend(approche: Float = 0f, graisse: FontWeight? = null) =
            copy(fontFamily = LEXEND, letterSpacing = approche.sp,
                 fontWeight = graisse ?: fontWeight)
        Typography(
            displayLarge = base.displayLarge.lexend(),
            displayMedium = base.displayMedium.lexend(),
            displaySmall = base.displaySmall.lexend().copy(fontFeatureSettings = "tnum"),
            headlineLarge = base.headlineLarge.lexend(),
            headlineMedium = base.headlineMedium.lexend(graisse = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.lexend(graisse = FontWeight.SemiBold),
            titleLarge = base.titleLarge.lexend(),
            titleMedium = base.titleMedium.lexend(graisse = FontWeight.Medium),
            titleSmall = base.titleSmall.lexend(),
            bodyLarge = base.bodyLarge.lexend(),
            bodyMedium = base.bodyMedium.lexend(),
            bodySmall = base.bodySmall.lexend(-0.1f),
            labelLarge = base.labelLarge.lexend(),
            labelMedium = base.labelMedium.lexend(-0.1f),
            labelSmall = base.labelSmall.lexend(-0.1f),
        )
    }

    /**
     * Les chiffres qui comptent : codes à usage unique, compteurs de chrono.
     *
     * ⚠️ Sans `tnum`, un « 1 » est plus étroit qu'un « 8 » : la ligne change de
     * largeur à chaque seconde, et l'œil qui recopie un code perd sa place.
     */
    val TABULAIRE = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)
}

/**
 * La marque Symbifox, en vecteur, telle que la sert symbifox.com.
 *
 * ⚠️ Le dessin déborde volontairement d'un carré : il est plus haut que large
 * (964 × 1253). Lui donner une boîte carrée le rétrécirait. C'est la HAUTEUR
 * qui se demande ici, et la largeur suit.
 */
@Composable
fun LogoSymbifox(hauteur: Dp = 96.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_symbifox),
        contentDescription = "Symbifox",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(hauteur),
    )
}

/**
 * Le bloc de marque de l'écran déconnecté : le logo officiel, le mot
 * « Symbifox », puis ce que cette application-ci fait.
 *
 * 🔴 **Avant la connexion, l'application ne sait PAS chez qui elle va.** Elle
 * porte donc Symbifox, et rien d'autre : afficher un nom d'entreprise à ce
 * moment serait une promesse qu'elle ne peut pas tenir.
 *
 * @param application le qualificatif SEUL — « Pastilles », « Tokens ». Un
 *   appelant qui passe le nom complet ne double pas le mot pour autant.
 */
@Composable
fun MarqueSymbifox(
    application: String,
    sousTitre: String? = null,
    hauteurLogo: Dp = 88.dp,
    modifier: Modifier = Modifier,
) {
    val qualificatif = application.removePrefix("Symbifox").trim().ifBlank { application }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoSymbifox(hauteurLogo)
        Spacer(Modifier.height(18.dp))
        Text(
            "Symbifox",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            qualificatif,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sousTitre?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * L'en-tête d'un écran connecté : le logo de l'organisation, son nom, ce que
 * l'écran montre.
 *
 * ⚠️ **Tuile CLAIRE sous le logo, même en mode sombre.** La plupart des logos
 * sont du texte foncé sur fond transparent, invisibles sur l'anthracite.
 * `Fit` et non `Crop` : un logo n'est presque jamais carré, et le rogner coupe
 * le nom.
 *
 * ⚠️ Sans logo connu, c'est la marque Symbifox qui tient la place — pas
 * l'icône de l'application. Une instance sans `bluefox_branding` est une
 * instance normale, et le produit reste identifiable.
 */
@Composable
fun EnTeteSymbifox(
    titre: String,
    sousTitre: String,
    logo: ImageBitmap? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (logo != null) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.White).padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = logo, contentDescription = titre, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                LogoSymbifox(40.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                titre, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                sousTitre, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

/**
 * L'écran de connexion de la famille : la marque, puis ce que l'appelant pose
 * dessous (le compte Symbifox, la saisie d'adresse, un encart).
 *
 * ⚠️ Un seul gabarit pour les cinq, sinon « uniformiser » ne tient que le jour
 * de la livraison.
 */
@Composable
fun EcranDeconnecte(
    application: String,
    promesse: String,
    hauteurLogo: Dp = 88.dp,
    contenu: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            // 🔴 Le fond du THÈME, explicitement. Sans lui, un écran sans
            // `Scaffold` laisse voir le fond de la FENÊTRE, qui ne porte pas la
            // teinte de l'accent : mesuré à #121518 chez Comms contre #131921
            // chez ses trois sœurs, sur le même téléphone en mode sombre.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MarqueSymbifox(application, promesse, hauteurLogo)
        Spacer(Modifier.height(32.dp))
        contenu()
    }
}

/**
 * La tuile de la famille : une carte cliquable, une icône dans son disque
 * teinté, un titre, une ligne de précision, un chevron.
 *
 * ⚠️ C'est le « style de menus visuels commun » du napkin (BF #25765) : le
 * compte Symbifox proposé à la connexion, les entrées d'un accueil, les choix
 * d'une feuille du bas se dessinent tous ainsi, dans les cinq applications.
 * Chronomètre dessinait la sienne sans icône, Pastilles avec, et Tokens et
 * Comms n'en avaient pas du tout.
 */
@Composable
fun TuileSymbifox(
    titre: String,
    sousTitre: String? = null,
    @DrawableRes icone: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icone?.let {
                PastilleSymbifox(it)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    titre, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                sousTitre?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Une icône posée dans un disque teinté de l'accent du locataire.
 *
 * ⚠️ Le disque prend l'accent à 16 % d'opacité, jamais l'accent plein : c'est
 * un repère, pas un bouton, et un aplat plein volerait l'œil aux vrais boutons
 * de l'écran.
 */
@Composable
fun PastilleSymbifox(
    @DrawableRes icone: Int,
    taille: Dp = 42.dp,
    teinte: Color? = null,
) {
    val fond = teinte ?: MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(taille).clip(CircleShape).background(fond.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icone), contentDescription = null, tint = fond,
            modifier = Modifier.size(taille * 0.52f),
        )
    }
}
