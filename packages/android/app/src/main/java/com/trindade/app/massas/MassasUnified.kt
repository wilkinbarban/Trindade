package com.trindade.app.massas

import com.trindade.app.aero.AeroErpMovement
import com.trindade.app.network.MassaIndividualLaunch

/**
 * The plant's day as one table.
 *
 * Two screens record the same boxes, and the table cannot show both lists: a box launched here and recorded
 * on the tablet would appear twice and the operator would not know which line to trust. So the day is
 * reconciled once and each box is one row with its real state.
 *
 * This is a port of `packages/frontend/src/lib/massas-unified.ts`, rule by rule. The reconciliation and the
 * seat filters live here rather than in the screen for the same reason they live in a module on the web: a
 * state that is computed once cannot disagree with itself in three places.
 */

/** The three things a row can be, and the only three. */
enum class MassasRowState {
    /** Launched here and already in the ERP. */
    RECONCILED,

    /** Launched here and not in the ERP yet. The only state that asks for an action here. */
    PENDING_ERP,

    /** Recorded on the company tablet and never launched here; importable when it fits whole boxes. */
    ERP_ONLY,
}

/** One line of the plant's day, no matter which screen wrote it. */
data class MassasUnifiedRow(
    /** Stable across reloads: the launch id when there is one, the seat's `ENT_LAN` when there is not. */
    val key: String,
    val state: MassasRowState,
    val operatorName: String,
    /** Lowercased, so the worker filter and the grouping agree. */
    val operatorKey: String,
    val productCode: String,
    val productName: String,
    val boxes: Int,
    val units: Int,
    val launchIds: List<Int>,
    val entLans: List<Int>,
    /** True only for `ERP_ONLY` seats that fit whole boxes; the import action exists for these. */
    val importable: Boolean,
    /** True when the operator already said this pending launch is loaded on the tablet. */
    val tabletAcked: Boolean,
)

/**
 * The launches the ERP already has.
 *
 * A reconciled launch is settled: Delphi holds the seat, so editing its boxes or deleting it here would leave
 * the plant's day and the company's book telling different numbers. The screen asks this before drawing a
 * control, and the view model asks it again before acting.
 */
fun reconciledLaunchIds(rows: List<MassasUnifiedRow>?): Set<Int> {
    val ids = mutableSetOf<Int>()
    for (row in rows.orEmpty()) {
        if (row.state != MassasRowState.RECONCILED) continue
        ids.addAll(row.launchIds)
    }
    return ids
}

data class MassasUnifiedInput(
    val launches: List<MassaIndividualLaunch>,
    /** The ERP's day, exactly as the sync stored it. */
    val movements: List<AeroErpMovement>,
    /** The plant's catalogue, used both to filter supplies out and to know `unitsPerBox`. */
    val products: List<AeroImportProduct>,
    /** Launch ids already acknowledged on this device for this day. */
    val ackedLaunchIds: Set<Int> = emptySet(),
)

/** The catalogue entry a launch is recorded against, as the screen already holds it. */
data class AeroImportProduct(
    val code: String,
    val name: String,
    val unitsPerBox: Int,
)

/** What an import would send, before the screen posts it. */
data class AeroImportPlan(
    /** The plant's own code, which is the one a local launch records. */
    val productCode: String,
    val boxes: Int,
)

/** What importing a seat sends: the seat, its product, the whole boxes it fits and the day it belongs to. */
data class AeroImportRequest(
    val entLan: Int,
    val productCode: String,
    val boxes: Int,
    val date: String,
)

/** Both sides write the same code with different padding. */
private fun importCodeKey(code: String): String = code.trim().trimStart('0').ifEmpty { "0" }

/** The catalogue entry for a code, tolerating the padding both sides use differently. */
private fun productFor(products: List<AeroImportProduct>, code: String): AeroImportProduct? {
    val digits = importCodeKey(code)
    return products.firstOrNull { importCodeKey(it.code) == digits }
}

/**
 * The seats this table is about.
 *
 * The ERP is asked for the whole day, and the day carries more than what the plant produced: a box of
 * packaging, bags of flour and straws arrive through the same endpoint. Those are supplies, they belong to
 * Recebimento, and a screen that shows them without an import button is telling the operator about a problem
 * it cannot solve. The catalogue is the filter, not the direction.
 */
fun aeroMirrorSeats(
    movements: List<AeroErpMovement>,
    products: List<AeroImportProduct>,
): List<AeroErpMovement> {
    val codes = products.map { importCodeKey(it.code) }.toSet()
    if (codes.isEmpty()) return emptyList()
    return movements.filter { importCodeKey(it.productCode) in codes }
}

/**
 * Decides whether an ERP seat can become a Trindade launch, and in what boxes.
 *
 * "Import" always means a new local launch, never an edit of the company's record. Three things must hold:
 * the seat is an entry (a stock exit is not production), the plant has the product, and the units fit whole
 * boxes -- a launch here is counted in boxes, and inventing a fraction would record something the factory
 * never counted.
 */
fun aeroImportPlan(movement: AeroErpMovement, products: List<AeroImportProduct>): AeroImportPlan? {
    if (movement.movementType != "E") return null

    val product = products.firstOrNull { importCodeKey(it.code) == importCodeKey(movement.productCode) }
        ?: return null
    if (product.unitsPerBox <= 0) return null

    val quantity = movement.quantity
    if (quantity <= 0 || quantity % 1.0 != 0.0) return null
    if (quantity.toInt() % product.unitsPerBox != 0) return null

    return AeroImportPlan(productCode = product.code, boxes = quantity.toInt() / product.unitsPerBox)
}

/**
 * The plant's day as one table, reconciled.
 *
 * `state` for each row is decided once, here, so the badges, the filter and the balance cannot disagree:
 *
 * - `RECONCILED`: launched here and already in the ERP.
 * - `PENDING_ERP`: launched here and not in the ERP yet.
 * - `ERP_ONLY`: recorded on the company tablet and never launched here; importable when it fits whole boxes.
 *
 * A stock exit never becomes a row: it is product leaving the plant, not production.
 *
 * The operator filter is universal -- it looks at every name the operator uses, on both sides of the day --
 * because a filter that only hides half the rows is a filter that lies about the total.
 */
fun massasUnifiedRows(input: MassasUnifiedInput, operatorFilter: String = ""): List<MassasUnifiedRow> {
    val seats = aeroMirrorSeats(input.movements, input.products)
    val reconciliation = com.trindade.app.aero.reconcileAeroMovements(
        movements = seats,
        launches = input.launches.map { launch ->
            com.trindade.app.aero.AeroLocalLaunch(
                id = launch.id,
                launchDate = launch.launchDate.ifEmpty { launch.date },
                productCode = launch.productCode,
                units = launch.units.toDouble(),
                username = launch.username,
                entLan = launch.entLan,
            )
        },
    )
    val matchedLaunchIds = reconciliation.matchedLaunchIds.toSet()
    val reconciledEntLans = reconciliation.reconciled.toSet()
    val filter = operatorFilter.trim().lowercase()

    val rows = mutableListOf<MassasUnifiedRow>()

    for (launch in input.launches) {
        if (launch.boxes <= 0) continue
        val names = operatorNames(launch)
        // The filter is universal: it looks at every name the operator uses, on both sides of the day.
        if (filter.isNotEmpty() && names.none { it.contains(filter) }) continue

        val reconciled = launch.id in matchedLaunchIds
        rows.add(
            MassasUnifiedRow(
                key = "launch-${launch.id}",
                state = if (reconciled) MassasRowState.RECONCILED else MassasRowState.PENDING_ERP,
                operatorName = operatorName(launch),
                operatorKey = operatorKey(launch),
                productCode = launch.productCode,
                productName = launch.productName,
                boxes = launch.boxes,
                units = launch.units,
                launchIds = listOf(launch.id),
                // The reconciliation matches counts, not pairs: two identical seats are interchangeable, so
                // naming one as this launch's seat would be a guess. The row says it is paired, and leaves
                // the id to the ERP side.
                entLans = emptyList(),
                importable = false,
                tabletAcked = !reconciled && launch.id in input.ackedLaunchIds,
            ),
        )
    }

    for (seat in seats) {
        if (seat.entLan in reconciledEntLans) continue
        if (seat.movementType != "E") continue
        val product = productFor(input.products, seat.productCode) ?: continue
        val operator = seat.operatorName.trim()
        if (filter.isNotEmpty() && !operator.lowercase().contains(filter)) continue

        val plan = aeroImportPlan(seat, input.products)
        rows.add(
            MassasUnifiedRow(
                key = "seat-${seat.entLan}",
                state = MassasRowState.ERP_ONLY,
                operatorName = operator,
                operatorKey = operator.lowercase(),
                productCode = product.code,
                productName = product.name,
                boxes = if (product.unitsPerBox > 0) (seat.quantity / product.unitsPerBox).toInt() else 0,
                units = seat.quantity.toInt(),
                launchIds = emptyList(),
                entLans = listOf(seat.entLan),
                importable = plan != null,
                tabletAcked = false,
            ),
        )
    }

    return rows.sortedWith(stateOrder)
}

/** Pending first: it is the only state that asks for an action here. */
private val stateOrder: Comparator<MassasUnifiedRow> =
    compareBy<MassasUnifiedRow> {
        when (it.state) {
            MassasRowState.PENDING_ERP -> 0
            MassasRowState.ERP_ONLY -> 1
            MassasRowState.RECONCILED -> 2
        }
    }
        .thenBy { it.operatorName.lowercase() }
        .thenBy { it.productName.lowercase() }

private fun operatorNames(launch: MassaIndividualLaunch): List<String> =
    listOf(launch.displayName, launch.username)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

private fun operatorName(launch: MassaIndividualLaunch): String =
    launch.displayName.ifBlank { launch.username }.ifBlank { "Operador" }.trim()

private fun operatorKey(launch: MassaIndividualLaunch): String = operatorName(launch).lowercase()

/** The day in two numbers, for the bar at the top. */
data class MassasBalance(
    /** Every box somebody counted, whichever screen counted it. */
    val produced: Int,
    /** What the company already has: a reconciled launch and its seat count once. */
    val registered: Int,
    /** Negative only when something was produced that the ERP does not know yet. */
    val difference: Int,
)

/**
 * The balance of the visible day.
 *
 * Produced is every box in the rows. Registered is what the company already has, so a reconciled launch and
 * its seat count once: the seat is the registration and the launch is the work, never two boxes. The
 * difference therefore reads as "what the company does not know yet".
 */
fun massasBalance(rows: List<MassasUnifiedRow>): MassasBalance {
    var produced = 0
    var registered = 0
    for (row in rows) {
        produced += row.units
        if (row.state != MassasRowState.PENDING_ERP) registered += row.units
    }
    return MassasBalance(produced = produced, registered = registered, difference = registered - produced)
}
