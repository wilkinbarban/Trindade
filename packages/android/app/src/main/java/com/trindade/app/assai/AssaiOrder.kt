package com.trindade.app.assai

/**
 * The Assaí order, kept pure so the screen holds no arithmetic of its own.
 *
 * The catalogue describes the product -- a box holds whole units -- and the order is what the operator
 * typed. A quantity that does not close whole boxes is not an order: rounding it would produce a number
 * that does not match what arrives at the gate, and the plant counts boxes at the gate.
 *
 * This is the same rule the web module applies, unit for unit, because an order typed on a phone and an
 * order typed on a desk are the same order.
 */

/** A product of the order and how many units its box holds. */
data class AssaiProduct(val name: String, val unitsPerBox: Int)

/** The nine products, in the order the plant reads them. */
val ASSAI_PRODUCTS: List<AssaiProduct> = listOf(
    AssaiProduct("Nhoque 1kg", 15),
    AssaiProduct("Quadrada", 14),
    AssaiProduct("Disco 200g", 80),
    AssaiProduct("Disco 400g", 50),
    AssaiProduct("Disco 500g", 40),
    AssaiProduct("Lasanha", 45),
    AssaiProduct("Rolo 500g", 30),
    AssaiProduct("Rolo 1kg", 24),
    AssaiProduct("Rolo 2kg", 10),
)

/** The allowed weekdays for Assaí order reporting. */
val ASSAI_WEEKDAYS: List<String> = listOf(
    "Terça-feira",
    "Quinta-feira",
    "Sexta-feira",
    "Sábado",
)

/**
 * One line of the order, already judged.
 *
 * [valid] says the field holds a whole number (or nothing), [filled] that a positive quantity was typed
 * and [exact] that the quantity closes whole boxes. Keeping the three apart is what lets the screen stay
 * quiet while a number is half written and say only the truth once the field is left.
 */
data class AssaiLine(
    val product: String,
    val unitsPerBox: Int,
    val units: Int,
    val boxes: Int,
    val valid: Boolean,
    val filled: Boolean,
    val exact: Boolean,
)

/** Judges every product of the catalogue against what was typed in its field. */
fun assaiLines(inputs: Map<String, String>): List<AssaiLine> = ASSAI_PRODUCTS.map { product ->
    val raw = inputs[product.name]?.trim() ?: ""
    val parsed = if (raw.isEmpty()) 0 else raw.toIntOrNull() ?: -1
    val valid = raw.isEmpty() || parsed >= 0
    val filled = valid && parsed > 0
    val exact = valid && (!filled || parsed % product.unitsPerBox == 0)
    AssaiLine(
        product = product.name,
        unitsPerBox = product.unitsPerBox,
        units = if (valid) parsed else 0,
        boxes = if (valid) parsed / product.unitsPerBox else 0,
        valid = valid,
        filled = filled,
        exact = exact,
    )
}

/** True when any line stops the order, which is what disables the export. */
fun assaiHasError(lines: List<AssaiLine>): Boolean = lines.any { !it.exact }

/** The boxes of the whole order: only what was typed and only what can be served. */
fun assaiTotalBoxes(lines: List<AssaiLine>): Int = lines.filter { it.filled && it.exact }.sumOf { it.boxes }

/**
 * The products whose error should be shown right now.
 *
 * Whether a quantity can be ordered is a fact about the number; whether to say so is a fact about the
 * moment. While the operator is typing, a quantity is allowed to be half written, and coming back into a
 * field clears the warning it left behind.
 */
fun assaiVisibleErrors(lines: List<AssaiLine>, blurred: Set<String>, focused: String?): List<String> =
    lines
        .filter { it.filled && !it.exact && blurred.contains(it.product) && it.product != focused }
        .map { it.product }

/** The message the order travels with: one line per product, and the total at the end. */
fun assaiMessage(lines: List<AssaiLine>, day: String? = null): String {
    val trimmedDay = day?.trim().orEmpty()
    val header = if (trimmedDay.isNotEmpty()) "🛒 Pedido Assaí $trimmedDay" else "🛒 Pedido Assaí"
    val ordered = lines.filter { it.filled && it.exact }
    if (ordered.isEmpty()) {
        return "$header\nNenhum produto com quantidade informada."
    }
    val rows = ordered.joinToString("\n") { "${it.product}: ${it.boxes} caixas" }
    return "$header\n\n$rows\n\nTotal: ${assaiTotalBoxes(lines)} caixas"
}
