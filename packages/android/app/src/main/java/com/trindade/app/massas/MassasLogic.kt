package com.trindade.app.massas

import com.trindade.app.network.MassaIndividualLaunch
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
 * Formats WhatsApp message for massas launches showing strictly total units
 * per launched product and the list of unique operators (without Total Geral).
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

    for (product in launchedProducts) {
        lines.add("• ${product.name}: ${product.totalUnits} un")
    }

    val uniqueOperators = view.launches
        .mapNotNull { launch ->
            launch.displayName.trim().takeIf { it.isNotEmpty() }
                ?: launch.username.trim().takeIf { it.isNotEmpty() }
        }
        .distinct()

    if (uniqueOperators.isNotEmpty()) {
        lines.add("")
        lines.add("*Lançado por:* ${uniqueOperators.joinToString(", ")}")
    }

    return lines.joinToString("\n")
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
 */
fun canEditOrDeleteLaunch(
    launchUserId: Int,
    currentUserId: Int?,
    isAdmin: Boolean,
    isToday: Boolean = true,
): Boolean {
    if (!isToday) return false
    if (isAdmin) return true
    if (currentUserId == null) return false
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
        .sortedBy { it.name }
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

