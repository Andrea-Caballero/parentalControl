package com.tudominio.parentalcontrol.ui.parent.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.PairingCodeResult
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PairingBottomSheet] QR rendering (PR 1 task #7 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end`).
 *
 * Verifies that the QR is rendered (with the `pairing_qr` test tag) when
 * the parent advances to step 2 and `pairingCode` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: ParentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val mockRepository = mockk<ParentRepository>(relaxed = true)
        coEvery {
            mockRepository.createPairingCode(any(), any(), any())
        } returns Result.success(
            PairingCodeResult(
                code = "ABCDEFGH",
                expiresAt = "2026-06-04T12:10:00Z",
                deeplink = "parentalcontrol://pair?code=ABCDEFGH"
            )
        )
        viewModel = ParentViewModel(mockRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun qr_renders_for_pairing_step_2() {
        // Pre-populate the viewmodel's pairingCode state via reflection on the
        // private MutableStateFlow so the test doesn't depend on the
        // viewModelScope coroutine timing.
        val codeField = ParentViewModel::class.java.getDeclaredField("_pairingCode")
        codeField.isAccessible = true
        val codeState = codeField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PairingCodeResult?>
        codeState.value = PairingCodeResult(
            code = "ABCDEFGH",
            expiresAt = "2026-06-04T12:10:00Z",
            deeplink = "parentalcontrol://pair?code=ABCDEFGH"
        )

        composeTestRule.setContent {
            ParentalControlTheme {
                PairingBottomSheet(
                    viewModel = viewModel,
                    onDismiss = {},
                    initialStep = 2
                )
            }
        }
        composeTestRule.waitForIdle()

        // The QR must be rendered with the test tag
        composeTestRule.onNodeWithTag("pairing_qr").assertExists()
    }

    @Test
    fun eight_char_code_still_shown_below_qr() {
        val codeField = ParentViewModel::class.java.getDeclaredField("_pairingCode")
        codeField.isAccessible = true
        val codeState = codeField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PairingCodeResult?>
        codeState.value = PairingCodeResult(
            code = "XYZ12345",
            expiresAt = "2026-06-04T12:10:00Z",
            deeplink = "parentalcontrol://pair?code=XYZ12345"
        )

        composeTestRule.setContent {
            ParentalControlTheme {
                PairingBottomSheet(
                    viewModel = viewModel,
                    onDismiss = {},
                    initialStep = 2
                )
            }
        }
        composeTestRule.waitForIdle()

        // The 8-char code text is still shown for manual entry
        composeTestRule.onNodeWithText("XYZ12345").assertExists()
        // And the "Válido por 10 minutos" caption
        composeTestRule.onNodeWithText("Válido por 10 minutos").assertExists()
    }
}
