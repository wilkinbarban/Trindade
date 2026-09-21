package com.trindade.app.auth

/**
 * What a role may open, in one place.
 *
 * The server is the authority and this is its table copied once, on purpose.
 * `modules/admin/admin.routes.ts` guards every admin surface with one of two role lists -- `adminGuard` is
 * `requireRole('Administrador')` and `catalogGuard` is `requireRole('Administrador', 'Trabalhador')` -- and
 * the SPA's tab filter mirrors exactly those lists. The app answered this question nowhere, which is how the
 * sixth surface to ask it invents a seventh answer; a hidden control is not authorization, so what this
 * table decides is only what is *offered*, and every call still meets the server's own guard.
 *
 * The two roles are the two rows the database has (`roles.name`), and anything else gets the worker's set.
 * That direction is the whole of the safety here: the failure worth preventing is offering an
 * administrator's surface to a role nobody recognises, and the server would refuse it anyway -- leaving the
 * operator with a door that does not open.
 */
object RolePolicy {

    /** `roles.name` for the administrator: the server's own spelling, not a translation of it. */
    const val ADMIN = "Administrador"

    /** `roles.name` for the worker. */
    const val WORKER = "Trabalhador"

    /**
     * Every destination the app offers or the parity track commits to, with the server guard behind each.
     *
     * Naming the guard per entry is what keeps this table checkable against the server rather than against
     * itself: a reader can open `admin.routes.ts` and confirm each line.
     *
     * The operator's own account is deliberately absent. It is not a surface the app is about, it hangs off
     * the row as a control rather than as a destination, and no role's guard decides it.
     */
    enum class EntryPoint {
        /** Session only: the summary the dashboard endpoint serves to every signed-in operator. */
        DASHBOARD,

        /** Session only: the reports a shift works with. The `reports` routes carry no role guard. */
        REPORTS,

        /** Session only: the schedule grid and its history. The `loading` routes carry no role guard. */
        LOADING,

        /** `catalogGuard`: both roles read and write task types. */
        CATALOG_TASKS,

        /** `catalogGuard`: both roles read and write drivers. */
        CATALOG_DRIVERS,

        /**
         * `adminGuard` on the tab, and this is the entry that shows why the guard alone is not the whole
         * answer: a worker may *read* categories -- the report generator offers them -- while the admin
         * tab's routes are the administrator's. The entry point is the tab, so the role that cannot open it
         * does not get it.
         */
        CATEGORIES,

        /** `adminGuard`. */
        VEHICLES,

        /** `adminGuard`. */
        TIME_SLOTS,

        /** `adminGuard`. */
        USERS,

        /** `adminGuard`: the audit log, which the SPA reaches from a page of its own. */
        AUDIT,
    }

    /**
     * What both roles see: the surfaces a signed-in operator uses every day, plus the two admin surfaces
     * the server's `catalogGuard` opens to both.
     *
     * Named for what it is -- the base both roles share -- because the name it had first said "worker" and
     * that is a trap: an entry point added here for the worker alone is handed to the administrator as
     * well, and the administrator's set is built from this one, so nothing else would catch it.
     */
    private val SHARED_ENTRY_POINTS = setOf(
        EntryPoint.DASHBOARD,
        EntryPoint.REPORTS,
        EntryPoint.LOADING,
        EntryPoint.CATALOG_TASKS,
        EntryPoint.CATALOG_DRIVERS,
    )

    /**
     * The administrator is the shared base plus the surfaces behind `adminGuard`. Written as an addition
     * rather than as a second list, so a surface added to the shared set cannot be forgotten here -- which
     * is the failure this table exists to prevent, one entry at a time.
     */
    private val ADMIN_ENTRY_POINTS = SHARED_ENTRY_POINTS + setOf(
        EntryPoint.CATEGORIES,
        EntryPoint.VEHICLES,
        EntryPoint.TIME_SLOTS,
        EntryPoint.USERS,
        EntryPoint.AUDIT,
    )

    /**
     * The entry points [role] may open. An absent or unrecognised role gets the shared set, which is what a
     * worker sees: fail closed, so a value this client does not understand can only ever offer less.
     */
    fun visibleEntryPoints(role: String?): Set<EntryPoint> = when (role) {
        ADMIN -> ADMIN_ENTRY_POINTS
        else -> SHARED_ENTRY_POINTS
    }

    /**
     * The destinations a role may see, in the order they were declared.
     *
     * The rule is [visibleEntryPoints]'s; this is the one line that applies it to a row of destinations, and
     * it is a function so that "the navigation is drawn from the policy" is something a test can run with a
     * destination the app does not have yet -- an administrator-only one -- and see a worker's version of
     * the same row without it.
     */
    fun <T> visibleDestinations(role: String?, destinations: List<Pair<EntryPoint, T>>): List<T> =
        destinations
            .filter { (entryPoint, _) -> entryPoint in visibleEntryPoints(role) }
            .map { (_, destination) -> destination }
}
