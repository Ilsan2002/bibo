package com.bibo.ui

import org.junit.Assert.*
import org.junit.Test

class HealthDraftPersistenceTest {
    @Test fun roundTripRetainsIdentityDateCorrectionsAndEstimateMeaning() {
        val draft = HealthDraft(20, HealthLog(listOf(FoodItem("Corrected meal", 123, 1.5, 0, false, "Manual nutrition")),
            showered = true, prayed = false, warnings = listOf("Check missing items"), dayOffset = -1, explicitEpochDay = 20), "same-operation")
        assertEquals(draft, HealthDraftPersistence.decode(HealthDraftPersistence.encode(draft)))
    }
    @Test fun malformedSavedStateCannotBecomeASaveableDraft() {
        assertNull(HealthDraftPersistence.decode(emptyList()))
        val fields = HealthDraftPersistence.encode(HealthDraft(20, HealthLog(listOf(FoodItem("Meal", 123, 1.5, 0))), "op"))
        fields[12] = "NaN"
        assertNull(HealthDraftPersistence.decode(fields))
    }
}
