package com.ped.mobile.inspection

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VehicleCatalogueTest {
    @Test fun offlineSearchResumesAndAppliesDeactivationWithoutCrossingAccounts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = UUID.randomUUID().toString()
        val store = VehicleCatalogueStore(context)
        fun page(identity: String, revision: Long, active: Boolean, model: String = "Corolla") = JSONObject()
            .put("catalogue_id", identity).put("next_cursor", revision).put("has_more", false)
            .put("data", JSONArray().put(JSONObject().put("id", 1).put("make", "Toyota").put("model", model).put("active", active).put("revision", revision)))
        store.apply(owner, page("first", 1, true), 0)
        assertEquals(listOf("Toyota Corolla"), store.search(owner,"toyota cor"))
        assertTrue(store.search("different-owner", "Toyota").isEmpty())
        store.close()
        VehicleCatalogueStore(context).use { reopened ->
            assertEquals(1L, reopened.state(owner).second)
            assertEquals(1, reopened.count(owner))
            reopened.apply(owner, page("first", 2, false), 1)
            assertTrue(reopened.search(owner, "Toyota").isEmpty())
            // A malformed page must roll back both rows and cursor.
            try { reopened.apply(owner, page("first", 2, true), 2); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
            assertEquals(2L, reopened.state(owner).second)
            assertEquals(0, reopened.count(owner))
            // A replaced server dataset starts at cursor zero and removes stale rows.
            reopened.apply(owner, page("replacement", 1, true,"Camry"), 0)
            assertEquals(listOf("Toyota Camry"), reopened.search(owner, "Toyota"))
            assertEquals("replacement", reopened.state(owner).first)
        }
    }
}
