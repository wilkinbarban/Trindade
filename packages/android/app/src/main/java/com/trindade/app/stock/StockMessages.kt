package com.trindade.app.stock

import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockDayProductsInner
import java.net.URLEncoder

/**
 * What leaves the plant when a count is done: a message and an email.
 *
 * The arithmetic belongs to the server, but the text is a rule of its own and it is the same text the web
 * builds, character for character, because the plant reads both: each line carries the product and its
 * units and nothing else -- the boxes are how the count is taken, not what the plant orders from a chat --
 * and the email aligns its columns so a number can be read down the page. Only what was counted appears:
 * a line of zero boxes in a chat read at the loading dock is noise that hides the line that matters.
 */
private fun counted(day: StockDay): List<StockDayProductsInner> = day.products.filter { it.units > 0 }

fun stockMessage(day: StockDay): String {
    val products = counted(day)
    if (products.isEmpty()) {
        return "🗒️ *Contagem de estoque* — ${day.date}\n\nNenhuma caixa contada até agora."
    }
    val lines = mutableListOf("🗒️ *Contagem de estoque* — ${day.date}", "")
    for (category in products.map { it.category }.distinct()) {
        val emoji = if (category == "Massas") "🍝" else "📦"
        lines += "$emoji *$category*"
        products.filter { it.category == category }.forEach {
            lines += "  • ${it.name} — ${it.units} un"
        }
        lines += ""
    }
    lines += "📊 Total: ${day.totalBoxes} caixas · ${day.totalUnits} unidades"
    return lines.joinToString("\n")
}

/**
 * The same count as a table, measured from the data rather than fixed, because a product name is as long
 * as it is and a table that wraps inside a mail client is worse than one that is merely wide. The last
 * column is padded from the left so the quantities line up under each other.
 */
fun stockEmailBody(day: StockDay): String {
    val products = counted(day)
    if (products.isEmpty()) {
        return "Contagem de estoque — ${day.date}\n\nNenhuma caixa contada até agora."
    }
    val headers = listOf("Categoria", "Produto", "Unidades")
    val rows = products.map { listOf(it.category, it.name, it.units.toString()) }
    val totals = listOf("Total", "", day.totalUnits.toString())
    val widths = headers.indices.map { index ->
        maxOf(headers[index].length, rows.maxOf { it[index].length }, totals[index].length)
    }
    fun line(cells: List<String>): String = cells
        .mapIndexed { index, cell -> if (index == cells.lastIndex) cell.padStart(widths[index]) else cell.padEnd(widths[index]) }
        .joinToString(" | ")
    val separator = widths.joinToString("-+-") { "-".repeat(it) }
    return buildString {
        append("Contagem de estoque — ${day.date}\n\n")
        append(line(headers)).append('\n')
        append(separator).append('\n')
        rows.forEach { append(line(it)).append('\n') }
        append(separator).append('\n')
        append(line(totals))
    }
}

/** The mailto an application opens, with its subject and body encoded. */
fun mailtoLink(to: String, subject: String, body: String): String =
    "mailto:$to?subject=${URLEncoder.encode(subject, "UTF-8")}&body=${URLEncoder.encode(body, "UTF-8")}"
