package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsersScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun user(
        id: Int,
        username: String = "user_$id",
        displayName: String = "Usuário $id",
        roleId: Int = 2,
        roleName: String = if (roleId == 1) "Administrador" else "Trabalhador",
        isActive: Int = 1,
    ) = AdminUserResponseUser(
        id = id,
        username = username,
        displayName = displayName,
        roleId = roleId,
        roleName = roleName,
        isActive = isActive,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun state(
        role: String = RolePolicy.ADMIN,
        currentUserId: Int? = 1,
        users: List<AdminUserResponseUser> = listOf(user(1, roleId = 1), user(2, roleId = 2)),
        form: UsersViewModel.FormState? = null,
        message: String? = null,
        isError: Boolean = false,
        deleteConfirmUser: AdminUserResponseUser? = null,
    ) = UsersViewModel.UiState(
        loading = false,
        users = users,
        form = form,
        role = role,
        currentUserId = currentUserId,
        message = message,
        isError = isError,
        deleteConfirmUser = deleteConfirmUser,
    )

    private fun render(
        state: UsersViewModel.UiState,
        onBack: () -> Unit = {},
        onOpenCreate: () -> Unit = {},
        onOpenEdit: (AdminUserResponseUser) -> Unit = {},
        onUsernameChanged: (String) -> Unit = {},
        onDisplayNameChanged: (String) -> Unit = {},
        onPasswordChanged: (String) -> Unit = {},
        onRoleIdChanged: (Int) -> Unit = {},
        onIsActiveChanged: (Int) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelForm: () -> Unit = {},
        onToggleActive: (AdminUserResponseUser) -> Unit = {},
        onRequestDelete: (AdminUserResponseUser) -> Unit = {},
        onCancelDelete: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                UsersScreen(
                    state = state,
                    onBack = onBack,
                    onOpenCreate = onOpenCreate,
                    onOpenEdit = onOpenEdit,
                    onUsernameChanged = onUsernameChanged,
                    onDisplayNameChanged = onDisplayNameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onRoleIdChanged = onRoleIdChanged,
                    onIsActiveChanged = onIsActiveChanged,
                    onSave = onSave,
                    onCancelForm = onCancelForm,
                    onToggleActive = onToggleActive,
                    onRequestDelete = onRequestDelete,
                    onCancelDelete = onCancelDelete,
                    onConfirmDelete = onConfirmDelete,
                )
            }
        }
    }

    @Test
    fun `loading state shows progress indicator`() {
        render(UsersViewModel.UiState(loading = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `user list renders display name, username, role, and status`() {
        val adminUser = user(1, username = "admin_chef", displayName = "Chefe Admin", roleId = 1, isActive = 1)
        val workerUser = user(2, username = "operador_yard", displayName = "Operador Yard", roleId = 2, isActive = 1)

        render(state(currentUserId = 1, users = listOf(adminUser, workerUser)))

        composeRule.onNodeWithText(copy(R.string.users_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Chefe Admin").assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_username_value, "admin_chef")).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_role_value, copy(R.string.users_role_admin))).assertIsDisplayed()

        composeRule.onNodeWithText("Operador Yard").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_username_value, "operador_yard")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_role_value, copy(R.string.users_role_worker))).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `self user card does not offer active switch or delete action`() {
        val selfUser = user(1, username = "admin", displayName = "Admin Self", roleId = 1)
        val otherUser = user(2, username = "worker", displayName = "Other Worker", roleId = 2)

        render(state(currentUserId = 1, users = listOf(selfUser, otherUser)))

        // Other user has a toggle switch and delete button
        composeRule.onNodeWithContentDescription(copy(R.string.users_deactivate)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_delete)).performScrollTo().assertIsDisplayed()

        // Both have edit buttons (total of 2 edit buttons)
        val editButtons = composeRule.onAllNodesWithText(copy(R.string.users_edit))
        assertEquals(2, editButtons.fetchSemanticsNodes().size)

        // Only one delete button exists (selfUser does not have one)
        val deleteButtons = composeRule.onAllNodesWithText(copy(R.string.users_delete))
        assertEquals(1, deleteButtons.fetchSemanticsNodes().size)
    }

    @Test
    fun `create button opens form and triggers callback`() {
        var createOpened = false
        render(
            state = state(users = emptyList()),
            onOpenCreate = { createOpened = true },
        )

        composeRule.onNodeWithText(copy(R.string.users_add_new)).performClick()
        assertEquals(true, createOpened)
    }

    @Test
    fun `delete confirmation dialog displays user name and confirms`() {
        val targetUser = user(2, displayName = "Operador Alvo")
        var deleteConfirmed = false

        render(
            state = state(users = emptyList(), deleteConfirmUser = targetUser),
            onConfirmDelete = { deleteConfirmed = true },
        )

        composeRule.onNodeWithText(copy(R.string.users_delete_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_delete_confirm_body, "Operador Alvo")).assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.users_delete)).performClick()
        assertEquals(true, deleteConfirmed)
    }

    @Test
    fun `delete confirmation dialog dismisses through cancel callback`() {
        val targetUser = user(2, displayName = "Operador Alvo")
        var deleteCancelled = false

        render(
            state = state(users = emptyList(), deleteConfirmUser = targetUser),
            onCancelDelete = { deleteCancelled = true },
        )

        composeRule.onNodeWithText(copy(R.string.users_cancel)).performClick()
        assertEquals(true, deleteCancelled)
    }

    @Test
    fun `user form for other user renders username, display name, password, and role selector`() {
        val form = UsersViewModel.FormState(
            userId = 2,
            username = "operador",
            displayName = "Operador Yard",
            roleId = 2,
            isActive = 1,
            isSelf = false,
        )

        render(state(form = form))

        composeRule.onNodeWithText(copy(R.string.users_edit_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_username)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_display_name)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_password)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_role_worker)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.users_role_admin)).assertIsDisplayed()
    }
}
