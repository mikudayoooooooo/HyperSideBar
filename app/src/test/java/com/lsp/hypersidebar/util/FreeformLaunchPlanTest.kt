package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeformLaunchPlanTest {

    @Test
    fun hostFirstThenModuleWhenEligibilityUnknown() {
        assertEquals(
            listOf("host.pkg", "module.pkg"),
            FreeformLaunchPlanner.candidates("host.pkg", "module.pkg", null)
        )
    }

    @Test
    fun moduleIncludedWhenEligible() {
        assertEquals(
            listOf("host.pkg", "module.pkg"),
            FreeformLaunchPlanner.candidates("host.pkg", "module.pkg", true)
        )
    }

    @Test
    fun moduleSkippedWhenKnownIneligible() {
        assertEquals(
            listOf("host.pkg"),
            FreeformLaunchPlanner.candidates("host.pkg", "module.pkg", false)
        )
    }

    @Test
    fun samePackageIsDeduped() {
        assertEquals(
            listOf("same.pkg"),
            FreeformLaunchPlanner.candidates("same.pkg", "same.pkg", null)
        )
    }

    @Test
    fun blankHostIsIgnored() {
        assertEquals(
            listOf("module.pkg"),
            FreeformLaunchPlanner.candidates("", "module.pkg", null)
        )
    }

    @Test
    fun codecRoundTrip() {
        val entry = EligibilityEntry(123L, linkedSetOf("a.pkg", "b.pkg"))
        val decoded = FreeformEligibilityCodec.decode(FreeformEligibilityCodec.encode(entry))
        assertEquals(123L, decoded?.fetchedAt)
        assertEquals(setOf("a.pkg", "b.pkg"), decoded?.packages)
    }

    @Test
    fun codecRejectsMalformedOrMissing() {
        assertNull(FreeformEligibilityCodec.decode(null))
        assertNull(FreeformEligibilityCodec.decode(""))
        assertNull(FreeformEligibilityCodec.decode("{"))
        assertNull(FreeformEligibilityCodec.decode("{\"format\":1}"))
    }

    @Test
    fun codecRejectsWrongFormat() {
        assertNull(FreeformEligibilityCodec.decode("{\"format\":2,\"pkgs\":[\"a\"]}"))
    }

    @Test
    fun codecDedupesAndDropsBlankPackages() {
        val decoded = FreeformEligibilityCodec.decode(
            "{\"format\":1,\"ts\":7,\"pkgs\":[\"a\",\"a\",\"\",\"b\"]}"
        )
        assertEquals(setOf("a", "b"), decoded?.packages)
    }
}
