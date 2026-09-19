package com.bluefoxconsultant.sms.ui.mail

/**
 * A deliberately small rich-text layer: a markdown-lite body the user edits as
 * text, converted to HTML on send.
 *
 * A true WYSIWYG editor does not exist in Compose and writing one is a project
 * of its own. This gets bold, italic and lists — which is what business mail
 * actually uses — while the composer stays an ordinary text field, so
 * selection, autocorrect, dictation and paste all keep working. Those are
 * worth more on a phone than a document model.
 *
 * **Ce qu'on voit n'est plus le balisage (#25764).** Jusqu'à la 2.43, les
 * boutons Gras, Italique et Liste inséraient `**`, `*` et `- ` sous les yeux de
 * la personne, et le rendu n'existait que chez le destinataire. Le texte reste
 * le même balisage — c'est lui que gardent le brouillon, la file hors ligne et
 * la remontée au poste, rien de tout ça ne change — mais l'écran le montre
 * rendu : [analyser] dit quels caractères sont des marqueurs, l'affichage les
 * cache et met le contenu en gras ou en italique. Une seule analyse sert à
 * l'écran et à l'envoi, pour que ce qui s'affiche soit ce qui part.
 *
 * Cacher des caractères qu'on peut encore effacer demande des règles, que
 * [corrigerEdition] applique à chaque frappe : effacer « un marqueur » efface
 * en fait la lettre visible d'avant, un marqueur qu'une édition a dépareillé
 * disparaît au lieu de réapparaître en clair, et Entrée sur une puce en ouvre
 * une autre.
 *
 * The output is still sanitized server-side; nothing here is a security
 * boundary.
 */
object RichText {

    enum class Style(val marqueur: String) {
        GRAS("**"),
        ITALIQUE("*"),
    }

    /**
     * Le « mot vide » posé entre deux marqueurs quand on appuie sur Gras sans
     * rien sélectionner.
     *
     * Sans lui, un gras à venir s'écrirait `****`, que la syntaxe ne lit pas
     * comme du gras (il faut au moins un caractère), et un italique `**`, qui
     * se confond avec un demi-gras. Le JOINT DE MOT (U+2060) est invisible,
     * sans largeur, et fait de la paire une portée valide dès l'appui : ce
     * qu'on tape ensuite s'écrit dedans. Il est retiré à l'envoi par
     * [nettoyer].
     */
    const val VIDE = '\u2060'

    /** Une portée stylée, en indices du texte source. Bornes de fin exclues. */
    data class Portee(
        val style: Style,
        val debut: Int,
        val fin: Int,
    ) {
        val contenuDebut: Int get() = debut + style.marqueur.length
        val contenuFin: Int get() = fin - style.marqueur.length
    }

    /**
     * Ce que l'écran doit savoir du texte : les portées, les caractères à
     * cacher (marqueurs et [VIDE]), et les tirets de puce à dessiner en « • ».
     */
    class Analyse(
        val texte: String,
        val portees: List<Portee>,
        val cache: BooleanArray,
        val puces: Set<Int>,
    ) {
        fun visible(i: Int): Boolean = i in texte.indices && !cache[i]

        /** Nombre de caractères visibles avant l'indice source [i]. */
        val versAffiche: IntArray by lazy {
            val out = IntArray(texte.length + 1)
            for (i in texte.indices) out[i + 1] = out[i] + if (cache[i]) 0 else 1
            out
        }

        /**
         * L'indice source du `j`-ième caractère visible ; la longueur du texte
         * au-delà. Au bord d'une portée, le curseur tombe donc AVANT le
         * marqueur d'ouverture qui précède un mot en gras seulement s'il n'y a
         * rien de visible entre les deux — il atterrit sur la lettre suivante,
         * c'est-à-dire DANS la portée à son début, et HORS de la portée à sa
         * fin. Écrire au début d'un mot gras écrit en gras ; écrire après,
         * en romain.
         */
        val versSource: IntArray by lazy {
            val visibles = texte.indices.filter { !cache[it] }
            val out = IntArray(visibles.size + 1)
            visibles.forEachIndexed { j, i -> out[j] = i }
            out[visibles.size] = texte.length
            out
        }

        val affiche: String by lazy {
            val sb = StringBuilder()
            for (i in texte.indices) {
                if (cache[i]) continue
                sb.append(if (i in puces) '•' else texte[i])
            }
            sb.toString()
        }
    }

    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    /**
     * ⚠️ Pas d'espace juste après l'astérisque ouvrante : « 2 * 3 * 4 » reste
     * un calcul et non un italique. La fermante, elle, accepte une espace
     * avant elle — sinon l'espace qu'on tape entre deux mots d'un italique en
     * cours ferait réapparaître ses astérisques le temps d'une lettre.
     */
    private val ITALIC = Regex("""(?<!\*)\*(?![\s*])(.+?)(?<!\*)\*(?!\*)""")

    /**
     * Analyse ligne par ligne, avec les mêmes expressions que [toHtml] et
     * dans le même ordre : le gras d'abord, puis l'italique sur la ligne où
     * les marqueurs du gras ont été neutralisés — exactement ce que voit
     * l'expression de l'italique dans [toHtml], où ces marqueurs sont déjà
     * devenus des balises.
     */
    fun analyser(texte: String): Analyse {
        val cache = BooleanArray(texte.length)
        val portees = mutableListOf<Portee>()
        val puces = mutableSetOf<Int>()
        var debutLigne = 0
        while (debutLigne <= texte.length) {
            val finLigne = texte.indexOf('\n', debutLigne).let { if (it < 0) texte.length else it }
            val ligne = texte.substring(debutLigne, finLigne)
            val masquee = StringBuilder(ligne)
            for (m in BOLD.findAll(ligne)) {
                val p = Portee(Style.GRAS, debutLigne + m.range.first, debutLigne + m.range.last + 1)
                portees += p
                for (k in m.range.first until m.range.first + 2) masquee.setCharAt(k, '\u0000')
                for (k in m.range.last - 1..m.range.last) masquee.setCharAt(k, '\u0000')
            }
            for (m in ITALIC.findAll(masquee)) {
                portees += Portee(Style.ITALIQUE, debutLigne + m.range.first, debutLigne + m.range.last + 1)
            }
            val retrait = ligne.length - ligne.trimStart().length
            if (ligne.trimStart().startsWith("- ")) puces += debutLigne + retrait
            debutLigne = finLigne + 1
        }
        for (p in portees) {
            for (k in p.debut until p.contenuDebut) cache[k] = true
            for (k in p.contenuFin until p.fin) cache[k] = true
        }
        for (i in texte.indices) if (texte[i] == VIDE) cache[i] = true
        return Analyse(texte, portees.sortedBy { it.debut }, cache, puces)
    }

    /**
     * Ce qui part : les paires restées vides retirées, les portées coupées
     * sans rien entre elles recollées, puis tout [VIDE].
     *
     * ⚠️ Recoller AVANT de retirer [VIDE]. Un italique coupé au curseur s'écrit
     * `*a*[VIDE]*b*` ; sans le mot vide entre les deux, `*a**b*` ne se relit plus
     * comme deux italiques et le destinataire recevait « a**b ». Relecture
     * adverse du 2026-09-16.
     */
    fun nettoyer(texte: String): String {
        if (texte.isEmpty()) return texte
        var t = texte
        for (style in listOf(Style.GRAS, Style.ITALIQUE)) {
            val m = style.marqueur
            t = t.replace("$m$VIDE$m", "")
        }
        val analyse = analyser(t)
        val sb = StringBuilder(t)
        analyse.portees
            .filter { p -> t.substring(p.contenuDebut, p.contenuFin).all { it == VIDE } }
            .sortedByDescending { it.debut }
            .forEach { sb.delete(it.debut, it.fin) }
        return sb.toString().replace(VIDE.toString(), "")
    }

    // ------------------------------------------------------------------
    // Barre d'outils
    // ------------------------------------------------------------------

    /** Le résultat d'un geste : le texte et la sélection, en indices source. */
    data class Edition(val texte: String, val selDebut: Int, val selFin: Int = selDebut)

    /**
     * Ramener une sélection faite au doigt à l'intérieur des marqueurs.
     *
     * La sélection d'un mot passe par la correspondance d'affichage : sa fin
     * tombe DERRIÈRE le marqueur fermant (le caractère visible suivant). Sans
     * cet ajustement, « retirer le gras » d'un mot sélectionné ne voyait pas la
     * portée, la restylait, et il fallait toucher deux fois.
     */
    private fun ajusterSelection(portees: List<Portee>, a: Int, b: Int): Pair<Int, Int> {
        if (a == b) return a to b
        var debut = a
        var fin = b
        for (p in portees) {
            if (fin == p.fin && debut >= p.debut && debut < p.fin) fin = p.contenuFin
            if (debut == p.debut && fin > p.debut && fin <= p.fin) debut = p.contenuDebut
        }
        return debut to maxOf(debut, fin)
    }

    /** Les styles actifs là où se trouve le curseur ou la sélection. */
    fun stylesActifs(texte: String, selDebut: Int, selFin: Int): Set<Style> {
        val analyse = analyser(texte)
        val (a, b) = ajusterSelection(
            analyse.portees, selDebut.coerceIn(0, texte.length),
            selFin.coerceIn(selDebut.coerceIn(0, texte.length), texte.length),
        )
        return analyse.portees.filter { p ->
            if (a == b) a in p.contenuDebut..p.contenuFin
            else a >= p.contenuDebut && b <= p.contenuFin
        }.map { it.style }.toSet()
    }

    /** La ligne du curseur, et elle seule, est-elle une puce ? */
    fun estPuce(texte: String, curseur: Int): Boolean {
        val c = curseur.coerceIn(0, texte.length)
        val debut = debutDeLigne(texte, c)
        val fin = texte.indexOf('\n', c).let { if (it < 0) texte.length else it }
        return texte.substring(debut, fin).trimStart().startsWith("- ")
    }

    private fun debutDeLigne(texte: String, c: Int): Int =
        if (c == 0) 0 else texte.lastIndexOf('\n', c - 1) + 1

    /**
     * Gras ou italique, comme un traitement de texte.
     *
     * - Sans sélection, hors portée : une paire vide ([VIDE] au milieu), le
     *   curseur dedans. Ce qu'on tape ensuite est stylé.
     * - Sans sélection, collé à une portée du même style (juste avant son
     *   ouverture ou juste après sa fermeture) : on y rentre, au lieu de poser
     *   une paire collée à la sienne — `*a**[VIDE]*` ne se relit plus comme un
     *   italique et montrait « a** ».
     * - Sans sélection, dans une portée : on en sort. À la fin, le curseur
     *   passe derrière le marqueur ; au milieu, la portée est coupée en deux
     *   autour du curseur ; sur une paire encore vide, elle est retirée.
     * - Une sélection entièrement dans une portée : elle en sort (la portée
     *   est coupée autour d'elle, ou retirée si la sélection la couvre).
     * - Sinon : la sélection est stylée, après avoir absorbé les portées du
     *   même style qu'elle chevauche, pour ne pas imbriquer les marqueurs.
     */
    fun basculer(texte: String, selDebut: Int, selFin: Int, style: Style): Edition {
        val m = style.marqueur
        val portees = analyser(texte).portees.filter { it.style == style }
        val (a, b) = ajusterSelection(
            portees, selDebut.coerceIn(0, texte.length),
            selFin.coerceIn(selDebut.coerceIn(0, texte.length), texte.length),
        )

        if (a == b) {
            val dedans = portees.firstOrNull { a in it.contenuDebut..it.contenuFin }
            if (dedans == null) {
                portees.firstOrNull { it.fin == a }?.let { return Edition(texte, it.contenuFin) }
                portees.firstOrNull { it.debut == a }?.let { return Edition(texte, it.contenuDebut) }
                val insere = "$m$VIDE$m"
                return Edition(texte.substring(0, a) + insere + texte.substring(a), a + m.length + 1)
            }
            val contenu = texte.substring(dedans.contenuDebut, dedans.contenuFin)
            if (contenu.all { it == VIDE }) {
                return Edition(texte.removeRange(dedans.debut, dedans.fin), dedans.debut)
            }
            if (a == dedans.contenuFin) return Edition(texte, dedans.fin)
            if (a == dedans.contenuDebut) return Edition(texte, dedans.debut)
            // Au milieu : fermer ici et rouvrir aussitôt, avec un mot vide
            // entre les deux, où l'on écrit en romain. Rien tapé : [nettoyer]
            // recolle les deux moitiés.
            val coupe = "$m$VIDE$m"
            return Edition(texte.substring(0, a) + coupe + texte.substring(a), a + m.length + 1)
        }

        val englobante = portees.firstOrNull { a >= it.contenuDebut && b <= it.contenuFin }
        if (englobante != null) {
            val p = englobante
            val t = texte
            return when {
                // Toute la portée : on retire ses deux marqueurs.
                a == p.contenuDebut && b == p.contenuFin -> Edition(
                    t.substring(0, p.debut) + t.substring(p.contenuDebut, p.contenuFin) + t.substring(p.fin),
                    a - m.length, b - m.length,
                )
                // Le début de la portée : l'ouverture passe derrière la sélection.
                a == p.contenuDebut -> Edition(
                    t.substring(0, p.debut) + t.substring(p.contenuDebut, b) + m + t.substring(b),
                    a - m.length, b - m.length,
                )
                // La fin de la portée : la fermeture passe devant la sélection.
                b == p.contenuFin -> Edition(
                    t.substring(0, a) + m + t.substring(a, p.contenuFin) + t.substring(p.fin),
                    a + m.length, b + m.length,
                )
                // Le milieu : la portée est coupée autour de la sélection.
                else -> Edition(
                    t.substring(0, a) + m + t.substring(a, b) + m + t.substring(b),
                    a + m.length, b + m.length,
                )
            }
        }

        // Styler : absorber d'abord les portées du même style qui chevauchent.
        var debut = a
        var fin = b
        val touchees = portees.filter { it.debut < fin && it.fin > debut }
        touchees.forEach { debut = minOf(debut, it.debut); fin = maxOf(fin, it.fin) }
        val sb = StringBuilder(texte)
        var retire = 0
        touchees.sortedByDescending { it.debut }.forEach { p ->
            sb.delete(p.contenuFin, p.fin)
            sb.delete(p.debut, p.contenuDebut)
            retire += 2 * m.length
        }
        val finApres = fin - retire
        sb.insert(finApres, m)
        sb.insert(debut, m)
        return Edition(sb.toString(), debut + m.length, finApres + m.length)
    }

    /** Wrap the current selection, or insert markers for the user to type into. */
    fun applyMarker(text: String, start: Int, end: Int, marker: String): Pair<String, Int> {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val selected = text.substring(safeStart, safeEnd)
        val wrapped = "$marker$selected$marker"
        val updated = text.replaceRange(safeStart, safeEnd, wrapped)
        // Caret between the markers when nothing was selected, after the
        // wrapped run otherwise.
        val caret = if (selected.isEmpty()) safeStart + marker.length else safeStart + wrapped.length
        return updated to caret
    }

    /**
     * Prefix the line the caret sits on — for bullets. The prefix is looked for
     * after the line's indentation, where [analyser] finds a bullet too : an
     * indented bullet is removed, not given a second dash.
     */
    fun applyLinePrefix(text: String, caret: Int, prefix: String): Pair<String, Int> {
        val safeCaret = caret.coerceIn(0, text.length)
        val lineStart = debutDeLigne(text, safeCaret)
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val ligne = text.substring(lineStart, lineEnd)
        val retrait = ligne.length - ligne.trimStart().length
        val at = lineStart + retrait
        if (text.startsWith(prefix, at)) {
            return text.removeRange(at, at + prefix.length) to
                (if (safeCaret >= at + prefix.length) safeCaret - prefix.length else minOf(safeCaret, at))
        }
        return text.replaceRange(at, at, prefix) to
            (if (safeCaret >= at) safeCaret + prefix.length else safeCaret)
    }

    // ------------------------------------------------------------------
    // Frappe
    // ------------------------------------------------------------------

    /**
     * Corrige une édition faite sur un texte dont les marqueurs sont cachés.
     *
     * [avant] est le texte d'avant la frappe, [apres] et [curseur] ce que le
     * champ propose. Rend `null` quand il n'y a rien à corriger — le cas de
     * presque toutes les frappes, et le seul où le clavier garde sa saisie en
     * cours intacte : toute correction réécrit le texte sous lui.
     */
    fun corrigerEdition(avant: String, apres: String, curseur: Int): Edition? {
        if (avant == apres) return null
        var prefixe = 0
        val borne = minOf(avant.length, apres.length)
        while (prefixe < borne && avant[prefixe] == apres[prefixe]) prefixe++
        var suffixe = 0
        while (suffixe < borne - prefixe &&
            avant[avant.length - 1 - suffixe] == apres[apres.length - 1 - suffixe]) suffixe++
        var retireFin = avant.length - suffixe
        var insere = apres.substring(prefixe, apres.length - suffixe)

        // Une insertion pure se situe par le CURSEUR, pas par la comparaison :
        // taper Entrée au bout d'une ligne suivie d'une autre donne « \n » des
        // deux côtés, et la comparaison le plaçait sur la ligne d'après.
        val k = apres.length - avant.length
        val c = curseur.coerceIn(0, apres.length)
        if (k > 0 && c - k >= 0 && apres.substring(0, c - k) + apres.substring(c) == avant) {
            prefixe = c - k
            retireFin = prefixe
            insere = apres.substring(prefixe, c)
        }
        val analyseAvant = analyser(avant)

        // 1. Effacer un caractère caché, c'est effacer la lettre visible d'avant.
        if (insere.isEmpty() && retireFin > prefixe &&
            (prefixe until retireFin).all { analyseAvant.cache[it] }) {
            var j = prefixe - 1
            while (j >= 0 && analyseAvant.cache[j]) j--
            if (j < 0) return Edition(avant, prefixe.coerceAtMost(avant.length))
            return reparer(analyseAvant, avant.removeRange(j, j + 1), j,
                           prefixe = j, retireFin = j + 1, ajout = 0, force = true)
        }

        // 2. Un saut de ligne inséré : dans une portée, dans une puce.
        if (retireFin == prefixe && insere.contains('\n')) {
            sautDeLigne(analyseAvant, prefixe, insere)?.let { return it }
        }

        // 3. Une frappe qui n'efface rien ne dépareille aucun marqueur : un
        // astérisque tapé exprès reste là où on l'a mis.
        if (retireFin == prefixe) return null
        return reparer(analyseAvant, apres, curseur, prefixe, retireFin, insere.length)
    }

    /**
     * Entrée au milieu d'une portée ou d'une puce.
     *
     * La syntaxe ne traverse pas les lignes : `**Important\n**` n'est plus du
     * gras, et ses marqueurs réapparaissaient — ou disparaissaient avec le
     * style. La portée est donc FERMÉE avant le saut de ligne et ROUVERTE
     * après ; un côté resté vide porte un [VIDE], invisible, pour rester une
     * portée. Taper un mot en gras puis Entrée continue en gras, comme partout.
     * Sur une puce, la ligne suivante en reçoit une ; Entrée sur une puce vide
     * ferme la liste.
     */
    private fun sautDeLigne(analyse: Analyse, position: Int, insere: String): Edition? {
        val texte = analyse.texte
        val premier = insere.indexOf('\n')
        val dernier = insere.lastIndexOf('\n')
        val avantSaut = insere.substring(0, premier)
        val sauts = insere.substring(premier, dernier + 1)
        val apresSaut = insere.substring(dernier + 1)
        val gauche = texte.substring(0, position)
        val droite = texte.substring(position)

        val debutLigne = debutDeLigne(texte, position)
        val finLigne = texte.indexOf('\n', position).let { if (it < 0) texte.length else it }
        val ligneAvant = texte.substring(debutLigne, position)
        val retrait = ligneAvant.length - ligneAvant.trimStart().length
        val estPuce = texte.substring(debutLigne, finLigne).trimStart().startsWith("- ") &&
            position >= debutLigne + retrait + 2

        val portees = analyse.portees.filter { position in it.contenuDebut..it.contenuFin }
        if (estPuce && portees.isEmpty() && insere == "\n" &&
            ligneAvant.trimStart() == "- " && texte.substring(position, finLigne).isBlank()) {
            return Edition(texte.removeRange(debutLigne + retrait, debutLigne + retrait + 2), debutLigne + retrait)
        }
        if (portees.isEmpty() && !estPuce) return null

        val interieures = portees.sortedByDescending { it.contenuDebut }
        val plusProche = interieures.firstOrNull()
        val gaucheVide = plusProche != null &&
            (texte.substring(plusProche.contenuDebut, position) + avantSaut).all { it == VIDE }
        val droiteVide = plusProche != null &&
            (apresSaut + texte.substring(position, plusProche.contenuFin)).all { it == VIDE }
        val fermetures = (if (gaucheVide) VIDE.toString() else "") +
            interieures.joinToString("") { it.style.marqueur }
        val ouvertures = interieures.reversed().joinToString("") { it.style.marqueur } +
            (if (droiteVide) VIDE.toString() else "")
        val puce = if (estPuce && apresSaut.isEmpty()) " ".repeat(retrait) + "- " else ""

        val tete = gauche + avantSaut + fermetures + sauts + puce + ouvertures + apresSaut
        return Edition(tete + droite, tete.length)
    }

    /**
     * Retire les marqueurs qu'une édition a dépareillés.
     *
     * La règle : un caractère qui était CACHÉ avant l'édition, qui a survécu à
     * l'édition, et qui ne l'est plus après, est un marqueur orphelin — son
     * partenaire vient d'être effacé. Sans cette règle il réapparaîtrait en
     * clair, `**` au milieu d'une phrase, exactement ce que ce lot retire.
     */
    private fun reparer(
        analyseAvant: Analyse,
        apres: String,
        curseur: Int,
        prefixe: Int,
        retireFin: Int,
        ajout: Int,
        force: Boolean = false,
    ): Edition? {
        val delta = ajout - (retireFin - prefixe)
        val analyseApres = analyser(apres)
        val orphelins = mutableListOf<Int>()
        for (i in analyseAvant.texte.indices) {
            if (!analyseAvant.cache[i] || analyseAvant.texte[i] == VIDE) continue
            val j = when {
                i < prefixe -> i
                i >= retireFin -> i + delta
                else -> continue
            }
            if (j in apres.indices && !analyseApres.cache[j] && apres[j] == analyseAvant.texte[i]) {
                orphelins += j
            }
        }
        if (orphelins.isEmpty()) return if (force) Edition(apres, curseur) else null
        val sb = StringBuilder(apres)
        var c = curseur
        orphelins.sortedDescending().forEach { j ->
            sb.deleteCharAt(j)
            if (j < c) c--
        }
        return Edition(sb.toString(), c)
    }

    // ------------------------------------------------------------------
    // Envoi
    // ------------------------------------------------------------------

    /**
     * markdown-lite → HTML. Escapes first, so anything the user typed that
     * looks like markup stays text; only our own markers become tags.
     */
    fun toHtml(source: String): String {
        val propre = nettoyer(source)
        if (propre.isBlank()) return ""
        val escaped = propre
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        val blocks = StringBuilder()
        var bulletsOpen = false
        for (rawLine in escaped.split("\n")) {
            val line = inline(rawLine.trim())
            val isBullet = rawLine.trimStart().startsWith("- ")
            if (isBullet) {
                if (!bulletsOpen) { blocks.append("<ul>"); bulletsOpen = true }
                blocks.append("<li>").append(inline(rawLine.trim().removePrefix("- "))).append("</li>")
                continue
            }
            if (bulletsOpen) { blocks.append("</ul>"); bulletsOpen = false }
            if (line.isBlank()) continue
            blocks.append("<p style=\"margin:0 0 12px 0;\">").append(line).append("</p>")
        }
        if (bulletsOpen) blocks.append("</ul>")
        return blocks.toString()
    }

    private fun inline(text: String): String =
        ITALIC.replace(BOLD.replace(text) { "<strong>${it.groupValues[1]}</strong>" }) {
            "<em>${it.groupValues[1]}</em>"
        }

    /** True when the body carries formatting worth sending as HTML. */
    fun hasFormatting(source: String): Boolean {
        val propre = nettoyer(source)
        return BOLD.containsMatchIn(propre) || ITALIC.containsMatchIn(propre) ||
            propre.lineSequence().any { it.trimStart().startsWith("- ") }
    }
}
