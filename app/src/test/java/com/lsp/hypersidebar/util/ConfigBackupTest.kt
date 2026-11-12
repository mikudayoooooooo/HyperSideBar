package com.lsp.hypersidebar.util

import com.lsp.hypersidebar.prefs.PrefKeys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {

    private fun component(id: String = "s1") = ShortcutAction(
        id = id,
        kind = ShortcutKind.COMPONENT,
        label = "Test $id",
        packageName = "a.b",
        activityName = "a.b.C"
    )

    private fun json(settings: String, extra: String = ""): String =
        """{"schema":"${ConfigBackup.SCHEMA}","version":1,"settings":$settings$extra}"""

    @Test
    fun roundTripPreservesTypesAndValues() {
        val snapshot = ConfigBackup.Snapshot(
            settings = mapOf(
                PrefKeys.ICON_SIZE to 40f,
                PrefKeys.ENABLED to false,
                PrefKeys.THEME_MODE to "dark"
            ),
            customApps = setOf("a.b"),
            customAppsOrder = listOf("a.b"),
            shortcuts = listOf(component())
        )
        val decoded = ConfigBackup.decode(ConfigBackup.encode(snapshot))
        assertEquals(40f, decoded.settings[PrefKeys.ICON_SIZE])
        assertEquals(false, decoded.settings[PrefKeys.ENABLED])
        assertEquals("dark", decoded.settings[PrefKeys.THEME_MODE])
        assertEquals(setOf("a.b"), decoded.customApps)
        assertEquals(listOf("a.b"), decoded.customAppsOrder)
        assertEquals(1, decoded.shortcuts?.size)
        assertEquals("s1", decoded.shortcuts?.first()?.id)
    }

    @Test
    fun unknownKeysAndTokenAreIgnored() {
        val decoded = ConfigBackup.decode(
            json("""{"relayToken":"evil","evil":1,"iconSize":40}""")
        )
        assertEquals(1, decoded.settings.size)
        assertEquals(40f, decoded.settings[PrefKeys.ICON_SIZE])
        assertNull(decoded.settings[PrefKeys.RELAY_TOKEN])
    }

    @Test
    fun absentKeysAreNotInjected() {
        val decoded = ConfigBackup.decode(json("""{"iconSize":40}"""))
        assertEquals(1, decoded.settings.size)
        assertTrue(decoded.settings.containsKey(PrefKeys.ICON_SIZE))
    }

    @Test
    fun invalidTypeIsRejected() {
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode(json("""{"iconSize":"big"}"""))
        }
    }

    @Test
    fun outOfRangeIsRejected() {
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode(json("""{"iconSize":9999}"""))
        }
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode(json("""{"triggerDwellMs":10}"""))
        }
    }

    @Test
    fun invalidEnumIsRejected() {
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode(json("""{"fanBlurSource":"nope"}"""))
        }
    }

    @Test
    fun schemaOrVersionMismatchIsRejected() {
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode("""{"schema":"other","version":1}""")
        }
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode("""{"schema":"${ConfigBackup.SCHEMA}","version":2}""")
        }
    }

    @Test
    fun singleChildGroupDissolvesOnDecode() {
        val child = component("child")
        val group = ShortcutAction(
            id = "g1",
            kind = ShortcutKind.GROUP,
            label = "Group",
            children = listOf(child)
        )
        val shortcuts = JSONArray().put(group.toJson())
        val text = json("{}", ""","shortcuts":$shortcuts""")
        val decoded = ConfigBackup.decode(text)
        assertEquals(1, decoded.shortcuts?.size)
        assertEquals(ShortcutKind.COMPONENT, decoded.shortcuts?.first()?.kind)
    }

    @Test
    fun emptyShortcutArrayMeansExplicitlyEmpty() {
        val decoded = ConfigBackup.decode(json("{}", ""","shortcuts":[]"""))
        assertNotNull(decoded.shortcuts)
        assertTrue(decoded.shortcuts!!.isEmpty())
    }

    @Test
    fun badCustomAppPackageIsRejected() {
        val obj = JSONObject()
            .put("schema", ConfigBackup.SCHEMA)
            .put("version", 1)
            .put("customApps", JSONArray().put("has space"))
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode(obj.toString())
        }
    }

    @Test
    fun wrongTypedSectionsAreRejected() {
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode(
                """{"schema":"${ConfigBackup.SCHEMA}","version":1,"settings":[]}"""
            )
        }
        assertThrows(ConfigBackup.BackupFormatException::class.java) {
            ConfigBackup.decode(
                """{"schema":"${ConfigBackup.SCHEMA}","version":1,"shortcuts":{}}"""
            )
        }
    }
}
