package com.youngmin.pobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNumberTest {
    @Test fun rangeIncludesTheUpperShelfsClassificationAndCopySuffixes() {
        val upper = requireNotNull(CallNumber.parse("TA357.5.D37"))
        val exact = requireNotNull(CallNumber.parse("TA357.50.D37 .김82 1998 v.2"))
        assertTrue(upper < exact)
        assertTrue(upper.prefixOf(exact))
        assertFalse(upper.prefixOf(requireNotNull(CallNumber.parse("TA357.5.D371"))))
    }

    @Test fun comparesTheKoreanAndLatinCuttersInLibraryOrder() {
        val korean = requireNotNull(CallNumber.parse("B5254.전66"))
        val latin = requireNotNull(CallNumber.parse("B5254.A1"))
        assertTrue(korean < latin)
        assertEquals(listOf("전66"), korean.cutters)
        assertNull(CallNumber.parse("분류되지 않은 청구기호"))
    }
}
