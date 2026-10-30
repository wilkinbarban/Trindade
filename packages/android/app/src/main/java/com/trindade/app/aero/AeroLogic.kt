package com.trindade.app.aero

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/**
 * The company's Aero32 WebService, spoken to directly from the phone.
 *
 * This file is the pure half of that conversation: what a request looks like and what an answer means,
 * with no HTTP, no Android and no storage in it. The rules below are not invented here -- they are the
 * ones `packages/frontend/src/lib/aero-integration.ts` already follows on the web, ported name by name so
 * both clients write the same seat into the same Firebird table and read the same day back. Keeping the
 * port literal matters more than making it shorter: a payload that differs by one field name is a stock
 * entry the factory cannot reconcile, and a reader that accepts a half-understood seat reconciles against
 * the wrong launch.
 */

/** Where the plant's WebService listens. Locked to the owner account in the admin panel. */
const val AERO_DEFAULT_WS_URL = "http://192.168.25.250:1453/api"

/** The series the certified Delphi binary writes on every entry (`ENT_SER`). */
const val AERO_ENTRY_SERIES = "APL"

/** The tax situation the Firebird trigger demands on an entry item; 000 is "tributada integralmente". */
const val AERO_DEFAULT_CST = "000"

/** `ENT_USU` when the operator never set a numeric ERP code. Matches what base.apk sends. */
const val AERO_FALLBACK_USER_CODE = 1

/** The only account allowed to move the plant address. Nothing else about it is special. */
const val AERO_WS_URL_OWNER = "wilkin"

/**
 * Validates and normalizes a WebService base URL.
 *
 * Trims the input, requires http or https with a host, collapses trailing slashes and appends `/api` when
 * the URL carries no path. A null answer is the correct answer for anything else: the address decides where
 * every launch of the shift goes, so a half-valid one is worse than none.
 */
fun normalizeAeroWsUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null

    val host = uri.host
    if (host.isNullOrBlank()) return null

    var path = uri.path.orEmpty().trimEnd('/')
    if (path.isEmpty()) path = "/api"
    val port = if (uri.port > 0) ":${uri.port}" else ""

    return "$scheme://$host$port$path"
}

/** Joins a base URL and an endpoint with exactly one slash between them. */
fun aeroEndpoint(wsUrl: String, path: String): String =
    "${wsUrl.trimEnd('/')}/${path.trimStart('/')}"

/**
 * The Basic header for these credentials, UTF-8 encoded.
 *
 * Built here rather than by OkHttp's `Credentials.basic` so the encoding is the same one the web writes and
 * so the value can be asserted without a request.
 */
fun basicAuthHeader(username: String, password: String): String {
    val raw = "$username:$password".toByteArray(Charsets.UTF_8)
    // java.util.Base64 and not android.util.Base64: the same call has to run in a JVM unit test, and the
    // platform one is a stub there. minSdk 26 is what makes this possible.
    return "Basic " + java.util.Base64.getEncoder().encodeToString(raw)
}

/**
 * The `?_method=PATCH` the ERP's movement query needs.
 *
 * The WebService is a Delphi router that only routes verbs it knows; the movement read is a POST that
 * pretends to be a PATCH, and the query string is how the certified binary says so.
 */
fun aeroMovementsUrl(wsUrl: String): String = aeroEndpoint(wsUrl, "movimentoitem?_method=PATCH")

/**
 * Scales units for the ERP's stock entry.
 *
 * Firebird stores the quantity scaled by 100: ninety units travel as `9000`, fifteen as `1500`. Sending the
 * human number records 0.90 or 0.15 instead.
 */
fun formatAeroQuantity(quantity: Double?): Int {
    if (quantity == null || !quantity.isFinite()) return 0
    return Math.round(quantity * 100).toInt()
}

/** The ERP's six-digit product code, zero padded the way its Firebird triggers resolve it. */
fun formatAeroProductCode(code: String?): String {
    val clean = code?.trim().orEmpty()
    if (clean.isEmpty()) return ""
    return if (clean.length < 6) clean.padStart(6, '0') else clean
}

/** The CST of an item, defaulted when the caller has none. */
fun formatAeroCst(cst: String?): String =
    if (cst.isNullOrBlank()) AERO_DEFAULT_CST else cst.trim()

/** The date part of an ISO timestamp, which is what every ERP field expects. */
fun aeroDateOnly(value: String): String = value.trim().take(10)

/**
 * The operator an entry carries in `ENT_USU`.
 *
 * A numeric code travels as a number and anything else travels uppercase, exactly as base.apk passes
 * `txtUsuario.Text` through. The fallback keeps an unset code from turning into an empty operator.
 */
fun aeroUserCode(raw: String?): JsonElement {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return JsonPrimitive(AERO_FALLBACK_USER_CODE)

    val parsed = trimmed.toIntOrNull()
    return if (parsed != null && parsed > 0 && parsed.toString() == trimmed) {
        JsonPrimitive(parsed)
    } else {
        JsonPrimitive(trimmed.uppercase())
    }
}

/**
 * The origin sentence the ERP keeps for an entry launched here.
 *
 * The company's tablet writes its own seats and this app writes its own; when one box is doubted, the only
 * field both sides share is the observation. Naming the launches makes the pair findable from Delphi.
 */
fun trindadeObservation(launchIds: List<Int>): String {
    val ids = launchIds.filter { it > 0 }
    if (ids.isEmpty()) return "Lancamento de massas via Trindade"
    return "Trindade " + ids.joinToString(", ") { "#$it" }
}

/** POST /entradasitem's body, in the order the ERP's parser expects. */
@Serializable
data class AeroEntryPayload(
    @SerialName("PRO_COD") val productCode: String,
    @SerialName("PRO_DES") val productName: String,
    @SerialName("PRO_QTD") val quantity: Int,
    @SerialName("PRO_CST") val cst: String,
    @SerialName("ENT_DTA") val entryDate: String,
    @SerialName("ENT_USU") val userCode: JsonElement,
    @SerialName("ENT_SER") val series: String,
    @SerialName("ENT_DTANOT") val noteDate: String,
    @SerialName("ENT_DTMOVI") val movementDate: String,
    @SerialName("ENT_OBS") val observation: String,
    @SerialName("LOTES") val lots: List<String>,
)

/**
 * Builds the entry payload for one product.
 *
 * The observation is the caller's when it has one and the launch ids otherwise, so every seat this app
 * writes can be traced back to the row that caused it.
 */
fun buildEntradaItemPayload(
    productCode: String,
    productName: String,
    quantity: Double,
    entryDate: String,
    userCode: String?,
    observation: String? = null,
    launchIds: List<Int> = emptyList(),
    cst: String? = null,
    series: String? = null,
): AeroEntryPayload {
    val date = aeroDateOnly(entryDate)
    val instance = observation?.trim().orEmpty()
    return AeroEntryPayload(
        productCode = formatAeroProductCode(productCode),
        productName = productName,
        quantity = formatAeroQuantity(quantity),
        cst = formatAeroCst(cst),
        entryDate = date,
        userCode = aeroUserCode(userCode),
        series = series?.trim()?.takeIf { it.isNotEmpty() } ?: AERO_ENTRY_SERIES,
        noteDate = date,
        movementDate = date,
        observation = if (instance.isNotEmpty()) instance else trindadeObservation(launchIds),
        lots = emptyList(),
    )
}

/**
 * The day's boxes as the units the ERP records.
 *
 * There is no per-box launch to choose from: five boxes of ten units are fifty units of stock, and the
 * series is the one the binary already expects.
 */
fun buildAeroEntryPayload(
    productCode: String,
    productName: String,
    boxes: Int,
    unitsPerBox: Int,
    date: String,
    userCode: String?,
    observation: String? = null,
): AeroEntryPayload = buildEntradaItemPayload(
    productCode = productCode,
    productName = productName,
    quantity = (boxes * unitsPerBox).toDouble(),
    entryDate = date,
    userCode = userCode,
    observation = observation,
)

/** POST /movimentoitem's body, in the order the ERP's parser expects. */
@Serializable
data class AeroMovimentoPayload(
    @SerialName("CODFOR") val supplierCode: String?,
    @SerialName("NOMUSU") val userName: String?,
    @SerialName("CODPRO") val productCode: String?,
    @SerialName("DTINI") val startDate: String,
    @SerialName("DTFIM") val endDate: String,
    @SerialName("ENTSAI") val movementType: String?,
)

/**
 * Builds the movement query the mirror asks for.
 *
 * The filters it leaves open it leaves explicitly null rather than absent, because that null is the ERP's
 * own word for "everybody" -- a missing key is a different question to its parser.
 */
fun buildMovimentoItemPayload(
    userName: String?,
    startDate: String,
    endDate: String? = null,
    productCode: String? = null,
    movementType: String? = null,
): AeroMovimentoPayload {
    val start = aeroDateOnly(startDate)
    val code = productCode?.trim().orEmpty()

    return AeroMovimentoPayload(
        supplierCode = null,
        userName = aeroMovementUserName(userName),
        productCode = if (code.isEmpty()) null else formatAeroProductCode(code),
        startDate = start,
        endDate = endDate?.let { aeroDateOnly(it) } ?: start,
        movementType = movementType,
    )
}

/**
 * The operator filter: the ERP asks in uppercase and answers everybody when the filter is null.
 *
 * A caller that wants the whole plant may say so with null, with "all", or by leaving it blank; anything
 * else is one operator's name.
 */
private fun aeroMovementUserName(userName: String?): String? {
    val name = userName?.trim().orEmpty()
    if (name.isEmpty() || name.equals("all", ignoreCase = true)) return null
    return name.uppercase()
}

/** One seat as the ERP answered it, read into the names this side uses. */
data class AeroErpMovement(
    val entLan: Int,
    val productCode: String,
    val productDesc: String,
    /** In units, human: the ERP answers `PRO_QTD` already unscaled. */
    val quantity: Double,
    /** The day the factory did it, `YYYY-MM-DD`. */
    val entryDate: String,
    /** `ENT_USU`, uppercase, as the ERP records the operator. */
    val operatorName: String,
    val movementType: String,
)

/** What came back from a movements query: the seats, or the reason there are none. */
sealed interface AeroMovementsResult {
    /**
     * `skipped` counts seats this client could not describe. They are dropped instead of throwing, because
     * one odd seat must not hide the rest of the day.
     */
    data class Ok(val movements: List<AeroErpMovement>, val skipped: Int) : AeroMovementsResult

    data class Failed(val message: String?) : AeroMovementsResult
}

/** The `/usuarios/` authentication answer, as the integration test needs it. */
sealed interface AeroTestResult {
    data class Ok(val userCode: Int, val userName: String) : AeroTestResult

    /** `message` is the ERP's sentence when it sent one; null when the client has to explain the failure. */
    data class Failed(val message: String?) : AeroTestResult
}

/** What the ERP said about one write: entries answer with the same `success` envelope as the reads. */
sealed interface AeroWriteResult {
    data class Ok(val message: String?) : AeroWriteResult

    /**
     * `message` is the ERP's own sentence when it sent one. A null one means the client has to say it its
     * own way, in the operator's language: no Portuguese copy is hardcoded in this file.
     */
    data class Failed(val message: String?) : AeroWriteResult
}

/**
 * Interprets the ERP's answer to a stock write.
 *
 * `success` is the only field that decides. A body without it is a failure rather than a silent success:
 * a launch marked reconciled on a guess is a box the factory believes is in its stock and is not.
 */
fun readAeroWriteResult(body: JsonElement?): AeroWriteResult {
    val record = body as? JsonObject ?: return AeroWriteResult.Failed(null)

    val message = record["message"].aeroText()?.trim()?.takeIf { it.isNotEmpty() }
    return when (record["success"].aeroBoolean()) {
        true -> AeroWriteResult.Ok(message)
        false -> AeroWriteResult.Failed(message)
        null -> AeroWriteResult.Failed(null)
    }
}

/**
 * Interprets the WebService's answer to the authentication probe.
 *
 * Never throws, whatever the body is: the probe exists to tell the operator why the plant is unreachable,
 * and a body this client cannot read is one of those answers rather than a crash.
 */
fun readAeroTestResult(body: JsonElement): AeroTestResult {
    val record = body as? JsonObject ?: return AeroTestResult.Failed(null)

    val success = record["success"].aeroBoolean()
    val message = record["message"].aeroText()?.trim().orEmpty()

    if (success == false) {
        return AeroTestResult.Failed(message.ifEmpty { null })
    }
    if (success != true) {
        return AeroTestResult.Failed(null)
    }

    val data = record["data"] as? JsonObject
    if (data == null) {
        return AeroTestResult.Ok(AERO_FALLBACK_USER_CODE, message.ifEmpty { "WILKIN" })
    }

    val rawCode = data["CODIGO"].aeroInt()
    val userCode = if (rawCode != null && rawCode > 0) rawCode else AERO_FALLBACK_USER_CODE
    val name = data["NOME"].aeroText().orEmpty().ifEmpty { message }

    return AeroTestResult.Ok(userCode, name.ifEmpty { "WILKIN" })
}

/**
 * Reads the ERP's answer to the movement query.
 *
 * The answer is `{ success, data: [seats] }` and `success` is what decides: a body that fails is an error
 * even when it carries seats, exactly like the stock writes. A `data` that is not a list is an error too,
 * but a list whose seats are partly unreadable is still the day.
 */
fun readAeroMovements(body: JsonElement): AeroMovementsResult {
    val record = body as? JsonObject ?: return AeroMovementsResult.Failed(null)
    val message = record["message"].aeroText()?.trim()?.takeIf { it.isNotEmpty() }

    if (record["success"].aeroBoolean() != true) {
        return AeroMovementsResult.Failed(message)
    }

    val data = record["data"] as? JsonArray ?: return AeroMovementsResult.Failed(message)

    val movements = mutableListOf<AeroErpMovement>()
    var skipped = 0
    for (raw in data) {
        val seat = readAeroMovementSeat(raw)
        if (seat != null) movements.add(seat) else skipped += 1
    }
    return AeroMovementsResult.Ok(movements, skipped)
}

/**
 * Reads one seat, or refuses it.
 *
 * Strict on purpose: a seat with a made-up quantity reconciles against the wrong launch, so a shape this
 * client half-understands is worse than an absent one.
 */
private fun readAeroMovementSeat(raw: JsonElement): AeroErpMovement? {
    val record = raw as? JsonObject ?: return null

    val entLan = record["ENT_LAN"].aeroInt() ?: return null
    if (entLan <= 0) return null

    val quantity = record["PRO_QTD"].aeroDouble() ?: return null
    if (!quantity.isFinite() || quantity < 0) return null

    val productCode = record["PRO_COD"].aeroText() ?: return null
    val productDesc = record["PRO_DES"].aeroText() ?: return null
    val entryDate = record["ENT_DTA"].aeroDateText() ?: return null
    val operatorName = record["ENT_USU"].aeroText() ?: return null
    val movementType = record["ENTSAI"].aeroText() ?: return null
    if (movementType != "E" && movementType != "S") return null

    return AeroErpMovement(
        entLan = entLan,
        productCode = productCode,
        productDesc = productDesc,
        quantity = quantity,
        entryDate = entryDate,
        operatorName = operatorName.uppercase(),
        movementType = movementType,
    )
}

/*
 * Every read below goes through `as? JsonPrimitive` rather than the `jsonPrimitive` extension, which throws
 * on a non-primitive: the ERP's answer is not something this client controls, and a mistyped field has to
 * be refused like any other unreadable one instead of escaping as an exception from inside a parser.
 */
private fun JsonElement?.aeroText(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.aeroInt(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun JsonElement?.aeroDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull

private fun JsonElement?.aeroBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.aeroDateText(): String? {
    val text = aeroText() ?: return null
    val match = Regex("^(\\d{4}-\\d{2}-\\d{2})").find(text) ?: return null
    return match.groupValues[1]
}

/** The snapshot body the backend stores for one day, in the names its route validates. */
@Serializable
data class AeroMovementsSnapshot(
    val date: String,
    val movements: List<AeroMovementSnapshotSeat>,
)

@Serializable
data class AeroMovementSnapshotSeat(
    @SerialName("ent_lan") val entLan: Int,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_desc") val productDesc: String,
    val quantity: Double,
    @SerialName("entry_date") val entryDate: String,
    @SerialName("operator_name") val operatorName: String,
    @SerialName("movement_type") val movementType: String,
)

/** Builds the snapshot the backend's `POST /api/integration/aero/movements` validates. */
fun buildAeroMovementsSnapshot(date: String, movements: List<AeroErpMovement>): AeroMovementsSnapshot =
    AeroMovementsSnapshot(
        date = aeroDateOnly(date),
        movements = movements.map { movement ->
            AeroMovementSnapshotSeat(
                entLan = movement.entLan,
                productCode = movement.productCode,
                productDesc = movement.productDesc,
                quantity = movement.quantity,
                entryDate = movement.entryDate,
                operatorName = movement.operatorName,
                movementType = movement.movementType,
            )
        },
    )

/** The parts of a Trindade launch the reconciliation compares. */
data class AeroLocalLaunch(
    val id: Int,
    val launchDate: String,
    val productCode: String,
    /** The launch in units: the ERP records units, and boxes are only how the operator counted them. */
    val units: Double,
    /** Who launched it here; a seat whose operator differs is somebody else's boxes. */
    val username: String,
)

data class AeroMovementReconciliation(
    /** `ENT_LAN` of the seats Trindade also has. */
    val reconciled: List<Int>,
    /** Local launches already present in the ERP. */
    val matchedLaunchIds: List<Int>,
    /** Local launches the ERP did not report, so the screen can offer to resend them. */
    val unmatchedLaunchIds: List<Int>,
)

/**
 * Compares the ERP seats against the local launches of the day.
 *
 * The code is compared without its leading zeros (`001192` is `1192` here) and the quantity to three
 * decimals, because the two sides describe the same boxes with different padding and rounding. Each local
 * launch reconciles at most one seat: two identical seats mean the operator launched twice, and consuming
 * the launches in order keeps a duplicate from being reported as reconciled twice over.
 *
 * The operator is part of the identity of a launch, not a detail: two operators can record the same boxes on
 * the same day. A seat whose operator is unknown reconciles with nothing, because that would be a guess
 * dressed as a confirmation. A stock exit is never reconciled: Trindade records entries only.
 */
fun reconcileAeroMovements(
    movements: List<AeroErpMovement>,
    launches: List<AeroLocalLaunch>,
): AeroMovementReconciliation {
    val byKey = mutableMapOf<String, MutableList<Int>>()
    for (launch in launches) {
        val key = aeroReconciledKey(
            aeroMovementKey(launch.launchDate, launch.productCode, launch.units),
            launch.username,
        )
        byKey.getOrPut(key) { mutableListOf() }.add(launch.id)
    }

    val reconciled = mutableListOf<Int>()
    val matchedLaunchIds = mutableListOf<Int>()
    for (movement in movements) {
        if (movement.movementType != "E") continue
        if (aeroOperatorKey(movement.operatorName).isEmpty()) continue

        val key = aeroReconciledKey(
            aeroMovementKey(movement.entryDate, movement.productCode, movement.quantity),
            movement.operatorName,
        )
        val ids = byKey[key]
        if (ids.isNullOrEmpty()) continue

        reconciled.add(movement.entLan)
        matchedLaunchIds.add(ids.removeAt(0))
    }

    val matched = matchedLaunchIds.toSet()
    return AeroMovementReconciliation(
        reconciled = reconciled,
        matchedLaunchIds = matchedLaunchIds,
        unmatchedLaunchIds = launches.filter { it.id !in matched }.map { it.id },
    )
}

private fun aeroMovementKey(date: String, code: String, quantity: Double): String {
    val digits = code.trim().trimStart('0').ifEmpty { "0" }
    val rounded = Math.round(quantity * 1000) / 1000.0
    return "${aeroDateOnly(date)}|$digits|$rounded"
}

/** The operator as both sides write it: padding and case are noise, a different name is not. */
private fun aeroOperatorKey(value: String?): String =
    value?.trim().orEmpty().uppercase().replace(Regex("\\s+"), " ")

private fun aeroReconciledKey(movementKey: String, operator: String?): String =
    "$movementKey|${aeroOperatorKey(operator)}"

/** Which controls of the integration screen a given operator may actually edit. */
data class AeroSettingsPermissions(
    /** The address decides where every launch of the shift goes, so it stays with the owner. */
    val canEditWsUrl: Boolean,
    /** Credentials belong to whoever is holding the phone and are typed by them. */
    val canEditCredentials: Boolean,
)

fun aeroSettingsEditable(username: String?): AeroSettingsPermissions {
    val clean = username?.trim().orEmpty().lowercase()
    return AeroSettingsPermissions(
        canEditWsUrl = clean == AERO_WS_URL_OWNER,
        canEditCredentials = clean.isNotEmpty(),
    )
}

/** The host and port a WebService address resolves to, with the scheme's default when none is written. */
data class AeroHostPort(val host: String, val port: Int)

fun aeroHostPort(wsUrl: String?): AeroHostPort? {
    val normalized = normalizeAeroWsUrl(wsUrl) ?: return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    val port = when {
        uri.port > 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
    return AeroHostPort(host, port)
}

/** Whether the three values a dispatch needs are present. */
fun aeroSettingsReady(wsUrl: String?, username: String?, password: String?): Boolean {
    if (normalizeAeroWsUrl(wsUrl) == null) return false
    if (username.isNullOrBlank()) return false
    if (password.isNullOrEmpty()) return false
    return true
}
