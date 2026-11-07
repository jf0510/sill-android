// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill's PairingField grouping behavior; see NOTICE for provenance.

package io.github.jf0510.sill

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Keeps protocol digits separate from display spacing so editing works at group boundaries. */
internal object PairingCodeInput : VisualTransformation {
    fun digits(text: String): String = text.filter { it in '0'..'9' }.take(12)

    override fun filter(text: AnnotatedString): TransformedText {
        val grouped = text.text.chunked(4).joinToString(" ")
        val separators = ((text.length - 1) / 4).coerceAtLeast(0)
        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    offset + (offset / 4).coerceAtMost(separators)

                override fun transformedToOriginal(offset: Int): Int =
                    grouped.take(offset).count { it != ' ' }
            }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}
