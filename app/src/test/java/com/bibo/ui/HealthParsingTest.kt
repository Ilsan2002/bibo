package com.bibo.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HealthParsingTest {
    @Test fun specificFoodDoesNotDoubleCountGenericWord() {
        val log = parseHealthDraft("had green tea")
        assertEquals(1, log.foods.size)
        assertEquals(30, log.foods.single().caffeineMg)
    }
    @Test fun quantitiesAndPluralsAreApplied() {
        val log = parseHealthDraft("had two coffees and three bananas")
        assertEquals(2, log.foods.size)
        assertEquals(190, log.foods.first().caffeineMg)
        assertEquals(315, log.foods[1].calories)
    }
    @Test fun decimalAndContainerQuantitiesStayInOneClause() {
        assertEquals(142, parseHealthDraft("had 1.5 coffees").foods.single().caffeineMg)
        assertEquals(60, parseHealthDraft("had two cups of green tea").foods.single().caffeineMg)
        assertEquals(60, parseHealthDraft("had two cups green tea").foods.single().caffeineMg)
        assertEquals(2, parseHealthDraft("had half a coffee").foods.single().calories)
        assertEquals(237, parseHealthDraft("had two and a half coffees").foods.single().caffeineMg)
        assertEquals(234, parseHealthDraft("had three eggs").foods.single().calories)
        assertTrue(parseHealthDraft("had -2 coffees").foods.isEmpty())
    }
    @Test fun unsupportedWeightNeedsManualValues() {
        val log = parseHealthDraft("had coffee and 100g chicken")
        assertEquals(1, log.foods.size)
        assertTrue(log.warnings.any { it.contains("manual nutrition") })
        assertTrue(parseHealthDraft("two large coffees").isEmpty)
        assertTrue(parseHealthDraft("two bottles of coke").isEmpty)
    }
    @Test fun explicitAndRelativeDatesUseCapturedReference() {
        val day = LocalDate.of(2026, 10, 10)
        assertEquals(day.minusDays(9).toEpochDay(), parseHealthDraft("on 2026-10-01 had coffee", day).explicitEpochDay)
        assertEquals(day.minusDays(1).toEpochDay(), parseHealthDraft("yesterday had coffee", day).explicitEpochDay)
        assertEquals(day.toEpochDay(), parseHealthDraft("today showered", day).explicitEpochDay)
        assertNull(parseHealthDraft("had coffee", day).explicitEpochDay)
    }
    @Test fun invalidAmbiguousAndFutureDatesNeedCorrection() {
        val day = LocalDate.of(2026, 10, 10)
        assertTrue(parseHealthDraft("on 2026-02-30 had coffee", day).isEmpty)
        assertTrue(parseHealthDraft("on 2026-10-11 had coffee", day).isEmpty)
        assertTrue(parseHealthDraft("Monday had coffee", day).isEmpty)
        assertTrue(parseHealthDraft("October 1 had coffee", day).isEmpty)
        assertTrue(parseHealthDraft("2026-10-01 coffee and 2026-10-02 banana", day).isEmpty)
        assertTrue(parseHealthDraft("yesterday 2026-10-01 coffee", day).isEmpty)
    }
    @Test fun clauseNegationDoesNotLeakAcrossHabits() {
        val log = parseHealthDraft("didn't drink coffee but showered and did not pray")
        assertTrue(log.foods.isEmpty())
        assertEquals(true, log.showered)
        assertEquals(false, log.prayed)
        assertEquals(false, parseHealthDraft("didn't work out").workedOut)
        assertEquals(false, parseHealthDraft("did not go to the gym").workedOut)
        assertEquals(false, parseHealthDraft("did not change my clothes").cleanClothes)
    }
    @Test fun negatedFoodIsNotRecorded() { assertTrue(parseHealthDraft("I did not eat pizza").foods.isEmpty()) }
    @Test fun plansAndUnrelatedWordsDoNotBecomeCompletedHabits() {
        assertNull(parseHealthDraft("I will go to the gym tomorrow").workedOut)
        assertTrue(parseHealthDraft("tomorrow coffee and prayed").isEmpty)
        assertTrue(parseHealthDraft("yesterday coffee and today a banana").isEmpty)
        assertNull(parseHealthDraft("training a model and I ran late for work").workedOut)
    }
    @Test fun unknownFoodsRemainAvailableForManualEntry() { assertTrue(parseHealthDraft("had an unfamiliar meal").isEmpty) }
    @Test fun partialDescriptionsIdentifyMissingItems() {
        val log = parseHealthDraft("coffee and an unfamiliar cake")
        assertEquals(1, log.foods.size)
        assertTrue(log.warnings.any { it.contains("unfamiliar cake") })
    }
    @Test fun impossibleNutritionCannotBeCommitted() {
        assertFalse(FoodItem("Meal", -1, 0.0, 0).valid)
        assertFalse(FoodItem("Meal", 1, Double.NaN, 0).valid)
        assertFalse(FoodItem("Meal", 1, Double.POSITIVE_INFINITY, 0).valid)
        assertTrue(FoodItem("Meal", 100, 2.5, 0, false).valid)
    }
}
