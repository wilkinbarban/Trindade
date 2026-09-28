package com.trindade.app.admin

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsersScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun user(
        id: Int,
        username: String = "user$id",
        displayName: String = "User $id",
        roleId: Int = 2,
        roleName: String = if (roleId == 1) "Administrador" else "Trabalhador",
        isActive: Int = 1,
        createdAt: String = "2026-01-01",
    ) = AdminUserResponseUser(
        id = id,
        username = username,
        displayName = displayName,
        roleId = roleId,
        roleName = roleName,
        isActive = isActive,
        createdAt = createdAt,
    )

    private fun state(
        role: String? = UsersViewModel.ADMIN,
        currentUserId: Int? = 1,
        users: List<AdminUserResponseUser> = listOf(
            user(1, "admin1", "Admin One", roleId = 1, isActive = 1),
            user(2, "worker2", "Worker Two", roleId = 2, isActive = 0),
        ),
        loading: Boolean = false,
        saving: Boolean = false,
        error: String? = null,
        refusedStatus: Int? = null,
        editingId: Int? = null,
        username: String = "novousuario",
        displayName: String = "Novo Usuário",
        roleId: Int = 2,
        isActive: Int = 1,
        deleteTarget: AdminUserResponseUser? = null,
        hasPasswordWarning: Boolean = false,
    ) = UsersViewModel.UiState(
        loading = loading,
        saving = saving,
        users = users,
        role = role,
        currentUserId = currentUserId,
        editingId = editingId,
        username = username,
        displayName = displayName,
        roleId = roleId,
        isActive = isActive,
        deleteTarget = deleteTarget,
        error = error,
        refusedStatus = refusedStatus,
        hasPasswordWarning = hasPasswordWarning,
    )

    private fun render(
        state: UsersViewModel.UiState,
        password: String = "",
        onBack: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onUsernameChange: (String) -> Unit = {},
        onDisplayNameChange: (String) -> Unit = {},
        onPasswordChange: (String) -> Unit = {},
        onRoleIdChange: (Int) -> Unit = {},
        onIsActiveChange: (Int) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onEdit: (AdminUserResponseUser) -> Unit = {},
        onToggle: (AdminUserResponseUser) -> Unit = {},
        onRequestDelete: (AdminUserResponseUser) -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onDismissDelete: () -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            UsersScreen(
                state = state,
                password = password,
                onBack = onBack,
                onRefresh = onRefresh,
                onUsernameChange = onUsernameChange,
                onDisplayNameChange = onDisplayNameChange,
                onPasswordChange = onPasswordChange,
                onRoleIdChange = onRoleIdChange,
                onIsActiveChange = onIsActiveChange,
                onSave = onSave,
                onCancel = onCancel,
                onEdit = onEdit,
                onToggle = onToggle,
                onRequestDelete = onRequestDelete,
                onConfirmDelete = onConfirmDelete,
                onDismissDelete = onDismissDelete,
            )
        }
    }

    @Test fun `admin sees users catalog details and active toggle for other user`() {
        var toggledTarget: AdminUserResponseUser? = null
        render(state(currentUserId = 1), onToggle = { toggledTarget = it })

        composeRule.onNodeWithText("Usuários").assertIsDisplayed()
        composeRule.onNodeWithText("admin1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Admin One").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Administrador").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ativo").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("worker2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Worker Two").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Trabalhador").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Inativo").performScrollTo().assertIsDisplayed()

        // Worker 2 is inactive, so the toggle action button should say "Ativar"
        composeRule.onNodeWithText("Ativar").performScrollTo().performClick()
        assertEquals(2, toggledTarget?.id)
    }

    @Test fun `self row in catalog hides role status and delete controls while preserving edit`() {
        var editedTarget: AdminUserResponseUser? = null
        render(
            state(
                currentUserId = 1,
                users = listOf(user(1, "admin1", "Admin One", roleId = 1, isActive = 1)),
            ),
            onEdit = { editedTarget = it },
        )

        composeRule.onNodeWithText("admin1").assertIsDisplayed()
        // Edit is permitted for self
        composeRule.onNodeWithText("Editar").assertIsDisplayed().performClick()
        assertEquals(1, editedTarget?.id)

        // Deactivation toggle and delete controls are strictly hidden for self
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Ativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
    }

    @Test fun `self edit form disables username editing and hides role and status controls`() {
        render(
            state(
                currentUserId = 1,
                editingId = 1,
                username = "admin1",
                displayName = "Admin One",
                roleId = 1,
                isActive = 1,
            ),
        )

        // Username field must be disabled when editing self
        composeRule.onNodeWithText("Nome de usuário").assertIsDisplayed().assertIsNotEnabled()
        // Display name and password remain editable
        composeRule.onNodeWithText("Nome de exibição").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Senha").assertIsDisplayed().assertIsEnabled()

        // Role and status selection controls must NOT be rendered when editing self
        composeRule.onNodeWithText("Função").assertDoesNotExist()
        composeRule.onNodeWithText("Status").assertDoesNotExist()
    }

    @Test fun `other user edit form enables username editing and displays role and status controls`() {
        var roleChanged = 0
        var statusChanged = -1
        render(
            state(
                currentUserId = 1,
                editingId = 2,
                username = "worker2",
                displayName = "Worker Two",
                roleId = 2,
                isActive = 1,
            ),
            onRoleIdChange = { roleChanged = it },
            onIsActiveChange = { statusChanged = it },
        )

        // Username, display name, and password are all editable for other user
        composeRule.onNodeWithText("Nome de usuário").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Nome de exibição").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Senha").assertIsDisplayed().assertIsEnabled()

        // Role and status selection controls are displayed
        composeRule.onNodeWithText("Função").assertIsDisplayed()
        val radioButtonMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        composeRule.onNode(hasText("Administrador") and radioButtonMatcher).performClick()
        assertEquals(1, roleChanged)

        composeRule.onNodeWithText("Status").assertIsDisplayed()
        composeRule.onNode(hasText("Inativo") and radioButtonMatcher).performClick()
        assertEquals(0, statusChanged)
    }

    @Test fun `admin create form renders username display name password role and invokes save`() {
        var userVal = ""
        var nameVal = ""
        var passVal = ""
        var roleVal = 0
        var saved = 0

        render(
            state(users = emptyList(), editingId = null, username = "", displayName = "", roleId = 2),
            password = "",
            onUsernameChange = { userVal = it },
            onDisplayNameChange = { nameVal = it },
            onPasswordChange = { passVal = it },
            onRoleIdChange = { roleVal = it },
            onSave = { saved++ },
        )

        composeRule.onNodeWithText("Novo usuário").performClick()
        composeRule.onNodeWithText("Nome de usuário").performTextInput("alice")
        assertEquals("alice", userVal)

        composeRule.onNodeWithText("Nome de exibição").performTextInput("Alice Smith")
        assertEquals("Alice Smith", nameVal)

        composeRule.onNodeWithText("Senha").performTextInput("secret123")
        assertEquals("secret123", passVal)

        composeRule.onNodeWithText("Administrador").performClick()
        assertEquals(1, roleVal)

        // Status control should not exist in create mode
        composeRule.onNodeWithText("Status").assertDoesNotExist()

        composeRule.onNodeWithText("Criar").performClick()
        assertEquals(1, saved)
    }

    @Test fun `advisory password warning is displayed without blocking save`() {
        var saved = 0
        render(
            state(editingId = null, hasPasswordWarning = true),
            password = "weak",
            onSave = { saved++ },
        )

        composeRule.onNodeWithText("Novo usuário").performClick()
        // Warning is rendered
        composeRule.onNodeWithText(UsersViewModel.PASSWORD_WARNING).assertIsDisplayed()
        // Save button remains enabled and clickable (warning is advisory only, non-blocking)
        composeRule.onNodeWithText("Criar").assertIsEnabled().performClick()
        assertEquals(1, saved)
    }

    @Test fun `advisory password warning is absent when password is strong or not warned`() {
        render(
            state(editingId = null, hasPasswordWarning = false),
            password = "StrongPassword1",
        )

        composeRule.onNodeWithText("Novo usuário").performClick()
        composeRule.onNodeWithText(UsersViewModel.PASSWORD_WARNING).assertDoesNotExist()
    }

    @Test fun `worker sees read-only catalog with somente leitura and no admin controls`() {
        render(state(role = UsersViewModel.WORKER))

        composeRule.onAllNodesWithText("Somente leitura")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Novo usuário").assertDoesNotExist()
        composeRule.onNodeWithText("Editar").assertDoesNotExist()
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Ativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
    }

    @Test fun `unknown and null roles fail closed with no admin controls`() {
        render(state(role = "Operador"))

        composeRule.onAllNodesWithText("Somente leitura")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Novo usuário").assertDoesNotExist()
        composeRule.onNodeWithText("Editar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
    }

    @Test fun `confirmed delete dialog via state target forwards confirmation`() {
        var confirmed = 0
        render(
            state(deleteTarget = user(2, "worker2")),
            onConfirmDelete = { confirmed++ },
        )

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir worker2?").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun `confirmed delete dialog is hidden for self user target`() {
        render(
            state(
                currentUserId = 1,
                deleteTarget = user(1, "admin1"),
            ),
        )

        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()
    }

    @Test fun `confirmed delete dialog is hidden for non-admin role`() {
        render(
            state(
                role = UsersViewModel.WORKER,
                deleteTarget = user(2, "worker2"),
            ),
        )

        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()
    }

    @Test fun `confirmed delete from list item invokes request once and confirm once`() {
        var requested = 0
        var target: AdminUserResponseUser? = null
        var confirmed = 0

        render(
            state(deleteTarget = null),
            onRequestDelete = { requested++; target = it },
            onConfirmDelete = { confirmed++ },
        )

        composeRule.onNodeWithText("worker2").performScrollTo()
        composeRule.onNodeWithText("Excluir").performScrollTo().performClick()
        assertEquals(1, requested)
        assertEquals(2, target?.id)

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir worker2?").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun `confirmed delete from list item forwards dismissal`() {
        var requested = 0
        var dismissed = 0

        render(
            state(deleteTarget = null),
            onRequestDelete = { requested++ },
            onDismissDelete = { dismissed++ },
        )

        composeRule.onNodeWithText("worker2").performScrollTo()
        composeRule.onNodeWithText("Excluir").performScrollTo().performClick()
        assertEquals(1, requested)

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun `delete contract pins list request dialog confirm and dialog cancel without premature deletion`() {
        var requested = 0
        var confirmed = 0
        var dismissed = 0
        var target: AdminUserResponseUser? = null

        render(
            state(deleteTarget = null),
            onRequestDelete = {
                requested++
                target = it
            },
            onConfirmDelete = { confirmed++ },
            onDismissDelete = { dismissed++ },
        )

        assertEquals(0, requested)
        assertEquals(0, confirmed)
        assertEquals(0, dismissed)

        composeRule.onNodeWithText("worker2").performScrollTo()
        composeRule.onNodeWithText("Excluir").performScrollTo().performClick()
        assertEquals(1, requested)
        assertEquals(2, target?.id)
        assertEquals(0, confirmed)
        assertEquals(0, dismissed)

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir worker2?").assertIsDisplayed()

        composeRule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()

        composeRule.onNodeWithText("Excluir").performScrollTo().performClick()
        assertEquals(2, requested)
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()

        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, confirmed)
        assertEquals(2, requested)
        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()
    }

    @Test fun `loading error refusal and saving states are rendered`() {
        render(
            state(
                loading = true,
                saving = true,
                users = emptyList(),
                error = UsersViewModel.REFUSED,
                refusedStatus = 400,
                editingId = 1,
            ),
        )

        composeRule.onNodeWithText("Carregando", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(UsersViewModel.REFUSED).assertIsDisplayed()
        composeRule.onNodeWithText("Status: 400").assertIsDisplayed()
        composeRule.onNodeWithText("Salvando…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Atualizar").assertIsNotEnabled()
    }

    @Test fun `empty list renders empty notice`() {
        render(state(loading = false, users = emptyList()))
        composeRule.onNodeWithText("Nenhum usuário").assertIsDisplayed()
    }

    @Test fun `back and refresh callbacks are wired`() {
        var backs = 0
        var refreshes = 0
        render(state(), onBack = { backs++ }, onRefresh = { refreshes++ })

        composeRule.onNodeWithText("Voltar").performClick()
        composeRule.onNodeWithText("Atualizar").performClick()
        assertEquals(1, backs)
        assertEquals(1, refreshes)
    }

    @Test fun `screen contract has no duplicate or alias action parameters`() {
        val clazz = Class.forName("com.trindade.app.admin.UsersScreenKt")
        val metadata = clazz.getAnnotation(Metadata::class.java)
        assertNotNull("UsersScreenKt must have Metadata annotation", metadata)
        val d2Method = metadata.javaClass.getMethod("d2")
        @Suppress("UNCHECKED_CAST")
        val d2Array = d2Method.invoke(metadata) as Array<String>
        val d2Entries = d2Array.toSet()

        // Direct callbacks that MUST exist:
        assertTrue("Must declare onUsernameChange", d2Entries.contains("onUsernameChange"))
        assertTrue("Must declare onDisplayNameChange", d2Entries.contains("onDisplayNameChange"))
        assertTrue("Must declare onPasswordChange", d2Entries.contains("onPasswordChange"))
        assertTrue("Must declare onRoleIdChange", d2Entries.contains("onRoleIdChange"))
        assertTrue("Must declare onIsActiveChange", d2Entries.contains("onIsActiveChange"))
        assertTrue("Must declare onRequestDelete", d2Entries.contains("onRequestDelete"))
        assertTrue("Must declare onConfirmDelete", d2Entries.contains("onConfirmDelete"))
        assertTrue("Must declare onDismissDelete", d2Entries.contains("onDismissDelete"))

        // Disallowed alias / direct delete parameters that MUST NOT exist:
        assertFalse("Must not declare onRoleChange alias", d2Entries.contains("onRoleChange"))
        assertFalse("Must not declare onStatusChange alias", d2Entries.contains("onStatusChange"))
        assertFalse("Must not declare direct onDelete callback", d2Entries.contains("onDelete"))
        assertFalse("Must not declare onCancelDelete alias", d2Entries.contains("onCancelDelete"))
        assertFalse("Must not declare onNameChange alias", d2Entries.contains("onNameChange"))
        assertFalse("Must not declare onNamePtChange alias", d2Entries.contains("onNamePtChange"))
        assertFalse("Must not declare onNameEsChange alias", d2Entries.contains("onNameEsChange"))
    }

    @Test fun `password input is masked with visual transformation and never rendered in plain text`() {
        val secret = "SecretPass123!"
        render(
            state(users = emptyList(), editingId = null),
            password = secret,
        )

        composeRule.onNodeWithText("Novo usuário").performClick()
        // The password field has SemanticsProperties.Password set
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)).assertCountEquals(1)
        // The visual transformation masks the text with bullets
        composeRule.onAllNodesWithText("\u2022".repeat(secret.length)).assertCountEquals(1)
        // The secret password text must NOT be rendered anywhere outside the masked password field
        composeRule.onNode(hasText(secret) and !SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)).assertDoesNotExist()
    }
}
