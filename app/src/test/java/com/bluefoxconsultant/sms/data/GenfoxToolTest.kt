package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le libellé de repli d'une étape de Gen, tel que gen_wait.js le choisit.
 *
 * Comparé par ids de ressource : la phrase elle-même vit dans les deux
 * `strings.xml`, recopiée du panneau web dans chaque langue.
 */
class GenfoxToolTest {

    @Test
    fun `un outil nomme a son libelle, prefixe mcp ou pas`() {
        assertEquals(uiText(R.string.gen_tool_running_check), GenfoxTool(name = "Bash").libelle)
        assertEquals(
            uiText(R.string.gen_tool_reading_task),
            GenfoxTool(name = "mcp__tentaclaude-bf__odoo_get_task").libelle,
        )
    }

    @Test
    fun `les familles se reconnaissent a leur verbe`() {
        assertEquals(uiText(R.string.gen_tool_writing_nextcloud), GenfoxTool(name = "nc_write_file").libelle)
        assertEquals(uiText(R.string.gen_tool_looking_nextcloud), GenfoxTool(name = "nc_list_files").libelle)
        assertEquals(uiText(R.string.gen_tool_searching_odoo), GenfoxTool(name = "odoo_list_projects").libelle)
        assertEquals(uiText(R.string.gen_tool_reading_odoo), GenfoxTool(name = "zoho_get_deal").libelle)
        assertEquals(uiText(R.string.gen_tool_writing_odoo), GenfoxTool(name = "odoo_update_task").libelle)
    }

    @Test
    fun `un outil inconnu garde son nom technique, sans traduction`() {
        assertEquals(UiText.Raw("sync foo bar"), GenfoxTool(name = "sync_foo_bar").libelle)
    }

    @Test
    fun `la description ecrite par Gen passe avant le libelle, telle quelle`() {
        val etape = GenfoxTool(name = "Bash", detail = "Lecture des tâches du projet")
        assertEquals(UiText.Raw("Lecture des tâches du projet"), etape.texte)
        assertEquals(uiText(R.string.gen_tool_running_check), GenfoxTool(name = "Bash").texte)
    }
}
