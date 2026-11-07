// SPDX-License-Identifier: Apache-2.0

package io.github.jf0510.sill

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class PairingCodeInputTest {
    @Test
    fun pastedCodesKeepOnlyTwelveProtocolDigits() {
        assertEquals("123456789018", PairingCodeInput.digits("1234 5678-9018"))
        assertEquals("123456789018", PairingCodeInput.digits("123456789018999"))
        assertEquals("1234", PairingCodeInput.digits("12a34"))
    }

    @Test
    fun groupsAppearOnlyWhenTheNextDigitExists() {
        for ((raw, visible) in
            listOf(
                "" to "",
                "1234" to "1234",
                "12345" to "1234 5",
                "12345678" to "1234 5678",
                "123456789018" to "1234 5678 9018",
            )) {
            assertEquals(visible, PairingCodeInput.filter(AnnotatedString(raw)).text.text)
        }
    }

    @Test
    fun cursorMappingsRemainValidForPartialCodesAndBothSidesOfSpaces() {
        val digits = "123456789018"
        for (length in 0..12) {
            val result = PairingCodeInput.filter(AnnotatedString(digits.take(length)))
            val mapping = result.offsetMapping
            val displayOffsets = (0..length).map(mapping::originalToTransformed)
            assertEquals(displayOffsets.sorted(), displayOffsets)
            for (offset in 0..length) {
                val display = mapping.originalToTransformed(offset)
                assertTrue(display in 0..result.text.length)
                assertEquals(offset, mapping.transformedToOriginal(display))
            }
            for (offset in 0..result.text.length) {
                assertTrue(mapping.transformedToOriginal(offset) in 0..length)
            }
        }
        val mapping = PairingCodeInput.filter(AnnotatedString(digits)).offsetMapping
        assertEquals(4, mapping.transformedToOriginal(4))
        assertEquals(4, mapping.transformedToOriginal(5))
        assertEquals(8, mapping.transformedToOriginal(9))
        assertEquals(8, mapping.transformedToOriginal(10))
    }
}
