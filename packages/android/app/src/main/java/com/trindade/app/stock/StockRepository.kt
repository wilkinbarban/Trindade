package com.trindade.app.stock

import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockHistoryDaysInner
import com.trindade.app.contract.models.StockProduct
import com.trindade.app.network.StockApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response

/**
 * What a counting day costs to reach.
 *
 * Two rules, both learned from the surfaces that came before. A failed read is nothing and not an empty
 * day, because an empty day and an unreachable server are different things and a counter that shows zero
 * boxes for a server it could not reach is lying about the shelf. And a write that is refused keeps its
 * refusal: the screen has to be able to tell "the server said no" from "the server was not there".
 *
 * Nothing is computed here. The boxes travel to the server and the totals come back from it, which is what
 * keeps two devices from disagreeing about the same shelf.
 */
@Singleton
class StockRepository @Inject constructor(private val api: StockApi) {

    sealed interface WriteResult {
        /** The product after the write, as the server answered it. */
        data class Saved(val product: StockProduct) : WriteResult

        /** The server answered and refused: an unknown product, or a count that adds nothing. */
        data object Refused : WriteResult

        /** The server could not be reached, which is not the same as a refusal. */
        data object Unreachable : WriteResult
    }

    suspend fun day(date: String): StockDay? =
        runCatchingCancellable { api.day(date) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    suspend fun history(): List<StockHistoryDaysInner>? =
        runCatchingCancellable { api.history() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.days

    suspend fun addEntry(date: String, productCode: String, boxesAdded: Int): WriteResult =
        write { api.addEntry(body(date, productCode, boxesAdded)) }

    suspend fun resetProduct(date: String, productCode: String): WriteResult =
        write { api.resetProduct(body(date, productCode, null)) }

    /** The keys the schema asks for; a reset carries no boxes at all. */
    private fun body(date: String, productCode: String, boxesAdded: Int?): JsonObject =
        JsonObject(
            buildMap {
                put("date", JsonPrimitive(date))
                put("product_code", JsonPrimitive(productCode))
                if (boxesAdded != null) put("boxes_added", JsonPrimitive(boxesAdded))
            }
        )

    private suspend fun write(call: suspend () -> Response<StockProduct>): WriteResult {
        val response = runCatchingCancellable { call() }.getOrNull() ?: return WriteResult.Unreachable
        val product = if (response.isSuccessful) response.body() else null
        return if (product == null) WriteResult.Refused else WriteResult.Saved(product)
    }
}
