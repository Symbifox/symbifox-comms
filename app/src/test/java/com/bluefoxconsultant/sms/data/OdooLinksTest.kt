package com.bluefoxconsultant.sms.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'adresse d'une fiche Odoo.
 *
 * Une erreur ici ne se voit PAS comme une erreur : le routeur d'Odoo 18 est
 * côté client, donc une adresse fautive rend un 200 puis échoue une fois la
 * page chargée. Le test tient donc la forme exacte de `/odoo/<modèle>/<id>`.
 */
class OdooLinksTest {

    @Test
    fun `record url follows the odoo 18 form route`() {
        assertEquals(
            "https://exemple.test/odoo/project.task/42",
            OdooLinks.recordUrl("https://exemple.test", "project.task", 42),
        )
    }

    @Test
    fun `a trailing slash on the instance does not double up`() {
        assertEquals(
            "https://exemple.test/odoo/res.partner/12",
            OdooLinks.recordUrl("https://exemple.test/", "res.partner", 12),
        )
    }
}
