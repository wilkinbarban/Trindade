package com.trindade.app.massas

import com.trindade.app.network.MassaIndividualLaunch
import com.trindade.app.network.MassaProductTotal
import com.trindade.app.network.MassasLaunchDayView

data class MassaCatalogItem(
    val code: String,
    val name: String,
    val unitsPerBox: Int,
)

/**
 * The 10 standard massa products according to the Trindade catalog.
 */
val STANDARD_MASSAS: List<MassaCatalogItem> = listOf(
    MassaCatalogItem("1045", "MASSA CASEIRA", 10),
    MassaCatalogItem("39", "MASSA DISCO 200G", 10),
    MassaCatalogItem("60", "MASSA DISCO 400G", 20),
    MassaCatalogItem("84", "MASSA DISCO 500G", 20),
    MassaCatalogItem("91", "MASSA LASANHA 500G", 20),
    MassaCatalogItem("1192", "MASSA QUADRADA", 15),
    MassaCatalogItem("5000", "MASSA ROLO 1KG", 10),
    MassaCatalogItem("1047", "MASSA ROLO 2KG", 6),
    MassaCatalogItem("22", "MASSA ROLO 500G", 15),
    MassaCatalogItem("1616", "MASSA TOKO", 10),
)

/**
 * Passive live-polling interval (15 seconds) for real-time collective sync across floor devices.
 */
const val POLL_INTERVAL_MS = 15000L

/**
 * Formats ISO YYYY-MM-DD into Brazilian DD/MM/YYYY.
 */
fun brazilianDate(date: String): String {
    val parts = date.split("-")
    return if (parts.size == 3 && parts[0].length == 4) {
        "${parts[2]}/${parts[1]}/${parts[0]}"
    } else {
        date
    }
}

/**
 * Formats WhatsApp message for massas launches with the collective summary of the day
 * plus a per-worker breakdown, with the redundant "MASSA" prefix removed from names.
 */
fun formatMassasWhatsAppMessage(view: MassasLaunchDayView): String {
    val lines = mutableListOf<String>()
    lines.add("🍝 *Lançamento de Massas — ${brazilianDate(view.date)}*")
    lines.add("")

    val launchedProducts = view.products.filter { it.totalUnits > 0 }
    if (launchedProducts.isEmpty()) {
        lines.add("Nenhum lançamento registrado até agora.")
        return lines.joinToString("\n")
    }

    lines.add("*Resumo Coletivo:*")
    for (product in launchedProducts) {
        lines.add(
            "• ${stripMassasPrefix(product.name)}: ${product.totalUnits} un (${product.totalBoxes} cx)"
        )
    }

    val workers = buildWorkerTotals(view)
    if (workers.isNotEmpty()) {
        lines.add("")
        lines.add("*Por Trabalhador:*")
        workers.forEachIndexed { index, worker ->
            if (index > 0) lines.add("")
            lines.add("👤 *${worker.name}:*")
            for (product in worker.products) {
                lines.add(
                    "• ${stripMassasPrefix(product.name)}: ${product.totalUnits} un (${product.totalBoxes} cx)"
                )
            }
        }
    }

    return lines.joinToString("\n")
}

private val MASSAS_PREFIX_REGEX = Regex("^MASSA\\s+(?:DE\\s+)?", RegexOption.IGNORE_CASE)

/**
 * Removes the leading "MASSA" (or "MASSA DE") prefix from a product name,
 * so the message reads "CASEIRA" instead of "MASSA CASEIRA".
 */
fun stripMassasPrefix(name: String): String = name.trim().replace(MASSAS_PREFIX_REGEX, "").trim()

data class MassaWorkerProductLine(
    val code: String,
    val name: String,
    val totalBoxes: Int,
    val totalUnits: Int,
)

data class MassaWorkerTotals(
    val userId: Int,
    val name: String,
    val products: List<MassaWorkerProductLine>,
)

private fun <T> sortByCatalogOrder(items: List<T>, catalog: List<MassaProductTotal>, code: (T) -> String, name: (T) -> String): List<T> {
    val order = catalog.mapIndexed { index, product -> product.code to index }.toMap()
    return items.sortedWith(
        compareBy(
            { order[code(it)] ?: Int.MAX_VALUE },
            { name(it).lowercase() },
        )
    )
}

/**
 * Consolidates launches per product, using the catalog only as the source of names,
 * units per box and display order. This makes the collective summary follow the
 * selected worker filter instead of the server-wide aggregate.
 */
fun groupProductsFromLaunches(
    launches: List<MassaIndividualLaunch>,
    catalog: List<MassaProductTotal>,
): List<MassaProductTotal> {
    val catalogByCode = catalog.associateBy { it.code }
    val totals = linkedMapOf<String, MassaProductTotal>()

    for (launch in launches) {
        val meta = catalogByCode[launch.productCode]
        val current = totals[launch.productCode]
        totals[launch.productCode] = MassaProductTotal(
            code = launch.productCode,
            name = meta?.name ?: launch.productName,
            unitsPerBox = meta?.unitsPerBox
                ?: if (launch.boxes > 0) Math.round(launch.units.toFloat() / launch.boxes) else 0,
            totalBoxes = (current?.totalBoxes ?: 0) + launch.boxes,
            totalUnits = (current?.totalUnits ?: 0) + launch.units,
        )
    }

    return sortByCatalogOrder(
        totals.values.filter { it.totalUnits > 0 },
        catalog,
        code = { it.code },
        name = { it.name },
    )
}

/**
 * Lists the distinct workers who launched a given product, sorted by name.
 */
fun productWorkerNames(
    launches: List<MassaIndividualLaunch>,
    productCode: String,
): List<String> {
    return launches
        .filter { it.productCode == productCode }
        .mapNotNull { launch ->
            launch.displayName.trim().takeIf { it.isNotEmpty() }
                ?: launch.username.trim().takeIf { it.isNotEmpty() }
        }
        .distinct()
        .sortedBy { it.lowercase() }
}

/**
 * Builds the per-worker breakdown of the day, with products in catalog order.
 */
fun buildWorkerTotals(view: MassasLaunchDayView): List<MassaWorkerTotals> {
    data class Accumulator(
        var name: String,
        val products: LinkedHashMap<String, MassaWorkerProductLine>,
    )

    val byUser = linkedMapOf<Int, Accumulator>()

    for (launch in view.launches) {
        val name = launch.displayName.trim().takeIf { it.isNotEmpty() }
            ?: launch.username.trim().takeIf { it.isNotEmpty() }
            ?: "Operador ${launch.userId}"
        val accumulator = byUser.getOrPut(launch.userId) { Accumulator(name, linkedMapOf()) }
        if (name.isNotEmpty()) accumulator.name = name

        val current = accumulator.products[launch.productCode]
        accumulator.products[launch.productCode] = MassaWorkerProductLine(
            code = launch.productCode,
            name = launch.productName,
            totalBoxes = (current?.totalBoxes ?: 0) + launch.boxes,
            totalUnits = (current?.totalUnits ?: 0) + launch.units,
        )
    }

    return byUser.map { (userId, accumulator) ->
        MassaWorkerTotals(
            userId = userId,
            name = accumulator.name,
            products = sortByCatalogOrder(
                accumulator.products.values.filter { it.totalUnits > 0 },
                view.products,
                code = { it.code },
                name = { it.name },
            ),
        )
    }
        .filter { it.products.isNotEmpty() }
        .sortedBy { it.name.lowercase() }
}

/**
 * Guard predicate determining if silent background polling should run.
 * Suspends when screen is inactive/hidden, when operator is actively editing or submitting,
 * or when viewing a past date (read-only historical view).
 */
fun shouldPollMassas(
    isScreenActive: Boolean,
    isEditing: Boolean,
    isSubmitting: Boolean,
    isToday: Boolean = true,
): Boolean {
    if (!isToday) return false
    if (!isScreenActive) return false
    if (isEditing) return false
    if (isSubmitting) return false
    return true
}

/**
 * Guard predicate determining if the user has permission to edit or delete an individual launch.
 * Restricted to the author of the launch or users with the admin role, and only permitted
 * on the current day (past dates are strictly read-only).
 *
 * A reconciled launch is settled for everybody: the seat already exists in the ERP, so the rule outranks the
 * role and the authorship instead of adding to them.
 */
fun canEditOrDeleteLaunch(
    launchUserId: Int,
    currentUserId: Int?,
    isToday: Boolean = true,
    isReconciled: Boolean = false,
): Boolean {
    if (!isToday) return false
    if (currentUserId == null) return false
    // Un lanzamiento conciliado ya está en el ERP: editarlo o borrarlo acá rompería la conciliación del día.
    if (isReconciled) return false
    return launchUserId == currentUserId
}

data class MassaOperator(
    val id: Int,
    val name: String,
)

/**
 * Extracts unique operators who registered launches, sorted alphabetically by name.
 */
fun extractOperators(launches: List<MassaIndividualLaunch>): List<MassaOperator> {
    return launches
        .map { launch ->
            val name = launch.displayName.trim().takeIf { it.isNotEmpty() }
                ?: launch.username.trim().takeIf { it.isNotEmpty() }
                ?: "Operador ${launch.userId}"
            MassaOperator(launch.userId, name)
        }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
}

/**
 * Filters individual launches by operator ID, or returns all launches when operatorId is null.
 */
fun filterLaunchesByOperator(
    launches: List<MassaIndividualLaunch>,
    operatorId: Int?
): List<MassaIndividualLaunch> {
    if (operatorId == null) return launches
    return launches.filter { it.userId == operatorId }
}

/**
 * Calculates total boxes and units for a selected operator, or null when operatorId is null.
 */
fun calculateOperatorSubtotal(
    launches: List<MassaIndividualLaunch>,
    operatorId: Int?
): Pair<Int, Int>? {
    if (operatorId == null) return null
    val filtered = filterLaunchesByOperator(launches, operatorId)
    val boxes = filtered.sumOf { it.boxes }
    val units = filtered.sumOf { it.units }
    return Pair(boxes, units)
}

