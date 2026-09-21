package com.trindade.app.auth

import com.trindade.app.auth.RolePolicy.EntryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table, asserted once per role.
 *
 * The assertions that matter are the ones about what a role does *not* get: this table exists because a
 * surface the server would refuse must not be offered, and the surface that matters is the administrator's.
 */
class RolePolicyTest {

    private val administratorOnly = setOf(
        EntryPoint.CATEGORIES,
        EntryPoint.VEHICLES,
        EntryPoint.TIME_SLOTS,
        EntryPoint.USERS,
        EntryPoint.AUDIT,
    )

    @Test
    fun `a worker gets the daily surfaces and both catalog tabs, and nothing administrator-only`() {
        assertEquals(
            setOf(
                EntryPoint.DASHBOARD,
                EntryPoint.REPORTS,
                EntryPoint.LOADING,
                EntryPoint.CATALOG_TASKS,
                EntryPoint.CATALOG_DRIVERS,
            ),
            RolePolicy.visibleEntryPoints(RolePolicy.WORKER),
        )
    }

    @Test
    fun `an administrator gets the worker's set plus every administrator-only entry point`() {
        assertEquals(
            RolePolicy.visibleEntryPoints(RolePolicy.WORKER) + administratorOnly,
            RolePolicy.visibleEntryPoints(RolePolicy.ADMIN),
        )
    }

    @Test
    fun `a role nobody recognises gets the worker's set, never the administrator's`() {
        val unknown = RolePolicy.visibleEntryPoints("Supervisor")

        assertEquals(RolePolicy.visibleEntryPoints(RolePolicy.WORKER), unknown)
        assertTrue((unknown intersect administratorOnly).isEmpty())
        assertEquals(RolePolicy.visibleEntryPoints(RolePolicy.WORKER), RolePolicy.visibleEntryPoints(null))
    }

    @Test
    fun `a row drawn for a worker leaves an administrator-only destination out of it`() {
        val destinations = listOf(EntryPoint.REPORTS to "reports", EntryPoint.AUDIT to "audit")

        assertEquals(listOf("reports"), RolePolicy.visibleDestinations(RolePolicy.WORKER, destinations))
        assertEquals(
            destinations.map { (_, destination) -> destination },
            RolePolicy.visibleDestinations(RolePolicy.ADMIN, destinations),
        )
    }

    @Test
    fun `the surfaces a signed-in operator works with are visible to every role`() {
        val shared = setOf(EntryPoint.DASHBOARD, EntryPoint.REPORTS, EntryPoint.LOADING)

        for (role in listOf(RolePolicy.ADMIN, RolePolicy.WORKER, "Supervisor", null)) {
            assertTrue(RolePolicy.visibleEntryPoints(role).containsAll(shared))
        }
    }
}
