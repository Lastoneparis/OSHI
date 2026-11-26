package com.oshi.messenger.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.ui.components.BotCommandsSheetContent
import com.oshi.messenger.ui.components.SlashCommandBar
import com.oshi.messenger.ui.onboarding.OnboardingPage
import com.oshi.messenger.ui.screens.BotCommandsSection
import com.oshi.messenger.ui.screens.BotConnectCard
import com.oshi.messenger.ui.screens.OnboardingContent
import com.oshi.messenger.ui.theme.OSHITheme
import com.oshi.messenger.ui.viewmodel.BotPortalViewModel
import com.oshi.messenger.ui.viewmodel.GroupChatViewModel
import com.oshi.messenger.util.BotCommandSyntax
import com.oshi.messenger.util.SlashCommandCatalog
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * __DESIGN_SHOTS_2026_11_24__ Renders the onboarding steps and the bot command surfaces
 * to PNG for design review, without an emulator (Robolectric native graphics).
 *
 * Opt-in because it writes files: `./gradlew :app:testDebugUnitTest --tests
 * '*DesignShotsTest' -Pshots=<dir>`. Without `-Pshots` every test is skipped, so the
 * normal suite neither renders nor pays for it.
 *
 * Not `captureToImage()`: it waits for an idle frame and onboarding's ambient motion
 * never idles. The window is drawn directly instead (recipe from the Freedom VPN app).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class DesignShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, dark: Boolean, oled: Boolean = false, rtl: Boolean = false, content: @Composable () -> Unit) {
        val dir = System.getProperty("design.shots")
        assumeTrue("design shots are opt-in (-Pshots=<dir>)", dir != null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OSHITheme(darkTheme = dark, oledBlack = oled) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun onboarding(step: OnboardingPage, name: String = "") = @Composable {
        OnboardingContent(
            isCreatingIdentity = false,
            creationError = null,
            onCreateIdentity = {},
            onImportRecoveryKey = { false },
            onRecoverFromDevice = { false },
            initialStep = step,
            initialName = name,
        )
    }

    // ── Onboarding ─────────────────────────────────────────────────────────
    @Test fun obPrivateLight() = shot("onboarding_1_private_light", dark = false, content = onboarding(OnboardingPage.PRIVATE))
    @Test fun obPrivateOled() = shot("onboarding_1_private_oled", dark = true, oled = true, content = onboarding(OnboardingPage.PRIVATE))
    @Test fun obOfflineDark() = shot("onboarding_2_offline_dark", dark = true, content = onboarding(OnboardingPage.OFFLINE))
    @Test fun obAnonLight() = shot("onboarding_3_anonymous_light", dark = false, content = onboarding(OnboardingPage.ANONYMOUS))
    @Test fun obTogetherOled() = shot("onboarding_4_together_oled", dark = true, oled = true, content = onboarding(OnboardingPage.TOGETHER))
    @Test fun obRulesLight() = shot("onboarding_5_rules_light", dark = false, content = onboarding(OnboardingPage.RULES))
    @Test fun obNotifLight() = shot("onboarding_6_notifications_light", dark = false, content = onboarding(OnboardingPage.NOTIFICATIONS))
    @Test fun obNotifOled() = shot("onboarding_6_notifications_oled", dark = true, oled = true, content = onboarding(OnboardingPage.NOTIFICATIONS))
    @Test fun obIdentityEmptyLight() = shot("onboarding_7_identity_empty_light", dark = false, content = onboarding(OnboardingPage.IDENTITY))
    @Test fun obIdentityNamedOled() = shot("onboarding_7_identity_named_oled", dark = true, oled = true, content = onboarding(OnboardingPage.IDENTITY, "Camille"))
    @Test fun obPrivateRtl() = shot("onboarding_1_private_rtl_dark", dark = true, rtl = true, content = onboarding(OnboardingPage.PRIVATE))
    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.3f)
    fun obIdentitySmallLargeFont() = shot("onboarding_7_identity_small_font130_light", dark = false, content = onboarding(OnboardingPage.IDENTITY, "Camille"))

    // ── Bots ───────────────────────────────────────────────────────────────
    private val faq = SlashCommandCatalog.botEntries(
        listOf(
            SlashCommandCatalog.BotCommandSource(
                "Meteo", "🌦️", "Weather for the group",
                listOf("/start", "/weather", "/forecast", "/help"),
                commandDescriptions = mapOf(
                    "start" to "Say hello and see what I can do",
                    "weather" to "Weather right now where you are",
                    "forecast" to "The next three days",
                    "help" to "All commands",
                ),
            )
        )
    )

    private fun chatSurface(text: String) = @Composable {
        Column(Modifier.fillMaxSize().padding(top = 400.dp, start = 8.dp, end = 8.dp)) {
            SlashCommandBar(text = text, botCommands = faq, onSend = {}, onPick = {})
        }
    }

    @Test fun slashAllLight() = shot("bots_1_slash_menu_light", dark = false, content = chatSurface("/"))
    @Test fun slashFilteredOled() = shot("bots_2_slash_filtered_fo_oled", dark = true, oled = true, content = chatSurface("/fo"))

    private val bots = listOf(
        GroupChatViewModel.BotHere("Meteo", "🌦️", "b1", 4),
        GroupChatViewModel.BotHere("Quiz", "🎲", "b2", 0),
    )

    @Test fun sheetLight() = shot("bots_3_commands_sheet_light", dark = false) {
        Column(Modifier.padding(top = 120.dp)) { BotCommandsSheetContent(bots, faq, {}, {}) }
    }
    @Test fun sheetEmptyDark() = shot("bots_4_commands_sheet_empty_dark", dark = true) {
        Column(Modifier.padding(top = 120.dp)) { BotCommandsSheetContent(emptyList(), emptyList(), {}, {}) }
    }

    private val rows = listOf(
        BotManager.CommandRow("t1", BotCommandSyntax.Spec("start", "Say hello and see what I can do"), "Hi {{input}}! Try /weather.", true),
        BotManager.CommandRow("t2", BotCommandSyntax.Spec("weather", "Weather right now where you are"), "It is sunny in {{input}}", true),
        BotManager.CommandRow("t3", BotCommandSyntax.Spec("help", ""), "Commands: /start /weather", true),
    )

    @Test fun editorLight() = shot("bots_5_owner_commands_light", dark = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            BotCommandsSection(rows, BotPortalViewModel.CommandSync.LOCAL, false, {}, {}, {}, {})
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            BotConnectCard(isWebhook = false, groupNames = listOf("Climbing club", "Family"))
        }
    }
    @Test fun editorWebhookFailedOled() = shot("bots_6_owner_commands_webhook_failed_oled", dark = true, oled = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            BotCommandsSection(rows.take(2), BotPortalViewModel.CommandSync.FAILED, true, {}, {}, {}, {})
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            BotConnectCard(isWebhook = true, groupNames = emptyList())
        }
    }
    @Test fun editorEmptyLight() = shot("bots_7_owner_commands_empty_light", dark = false) {
        Column(Modifier.padding(16.dp)) {
            BotCommandsSection(emptyList(), BotPortalViewModel.CommandSync.LOCAL, false, {}, {}, {}, {})
        }
    }

    // ── Bots phase 1: BotFather, profile / add, search, bot post ──────────
    private val meteo = com.oshi.messenger.util.BotPublicProfile(
        botId = "id1", username = "meteo_parisbot", name = "Météo Paris", avatarEmoji = "🌦️",
        about = "Weather for your group, every morning",
        description = "Type /weather for the forecast, or tap the buttons under my posts to vote on tomorrow's outing.",
        commands = listOf(BotCommandSyntax.Spec("weather", "<city> Weather now"), BotCommandSyntax.Spec("forecast", "The next three days")),
    )
    private val fatherBot = com.oshi.messenger.data.model.OSHIBot(
        token = "3f9a1c0e7b2d4a6f8e1c3b5d7f9a2c4e", name = "Météo Paris", username = "meteo_parisbot", ownerPublicKey = "me", type = "webhook")
    private val chats = listOf(
        com.oshi.messenger.data.model.Group(id = "g1", name = "Climbing club", creatorPublicKey = "me", memberPublicKeys = listOf("me"), adminPublicKeys = listOf("me")),
        com.oshi.messenger.data.model.Group(id = "g2", name = "Paris news", creatorPublicKey = "me", memberPublicKeys = listOf("me"), adminPublicKeys = listOf("me"),
            groupType = com.oshi.messenger.data.model.GroupType.ADMIN_ONLY),
    )
    private fun father(ui: com.oshi.messenger.ui.bots.BotFatherViewModel.Ui) = @Composable {
        com.oshi.messenger.ui.bots.BotFatherContent(ui, chats, com.oshi.messenger.ui.bots.BotFatherActions())
    }

    @Test fun fatherNameLight() = shot("botfather_1_name_light", dark = false,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 1, name = "Météo Paris")))
    @Test fun fatherUsernameTakenLight() = shot("botfather_2_username_taken_light", dark = false,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 2, name = "Météo Paris", username = "meteobot",
            usernameStatus = com.oshi.messenger.ui.bots.BotFatherViewModel.UsernameStatus.Taken(com.oshi.messenger.util.BotUsername.suggestions("meteobot")))))
    @Test fun fatherUsernameOkOled() = shot("botfather_2_username_available_oled", dark = true, oled = true,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 2, username = "meteo_parisbot",
            usernameStatus = com.oshi.messenger.ui.bots.BotFatherViewModel.UsernameStatus.Available)))
    @Test fun fatherAboutLight() = shot("botfather_3_about_light", dark = false,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 3, emoji = "🌦️", about = "Weather for your group, every morning")))
    @Test fun fatherCommandsLight() = shot("botfather_4_commands_light", dark = false,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 4, kind = com.oshi.messenger.util.BotKind.POLL,
            commands = listOf(BotCommandSyntax.Spec("start", "Start the bot")))))
    @Test fun fatherTokenDark() = shot("botfather_5_token_dark", dark = true,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 5, bot = fatherBot)))
    @Test fun fatherAddToLight() = shot("botfather_6_add_to_light", dark = false,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 6, bot = fatherBot)))

    @Test @Config(qualifiers = "fr-w393dp-h852dp-xxhdpi")
    fun fatherTokenFr() = shot("botfather_5_token_fr_light", dark = false,
        content = father(com.oshi.messenger.ui.bots.BotFatherViewModel.Ui(step = 5, bot = fatherBot)))

    @Test fun profileSheetLight() = shot("bots_8_profile_sheet_light", dark = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 40.dp)) {
            com.oshi.messenger.ui.bots.BotProfileContent(meteo, {}, {}, {}, {})
        }
    }
    @Test fun addToChannelNoticeLight() = shot("bots_9_add_to_channel_notice_light", dark = false) {
        Column(Modifier.padding(24.dp).padding(top = 80.dp)) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.oshi.messenger.R.string.botf_add_title_channel, meteo.name),
                style = MaterialTheme.typography.headlineSmall)
            com.oshi.messenger.ui.bots.AddBotToChatContent(meteo.name, true, true, chats.drop(1), chats[1], {},
                com.oshi.messenger.util.BotAdminRights.DEFAULT, {})
        }
    }
    @Test @Config(qualifiers = "fr-w393dp-h852dp-xxhdpi")
    fun addToGroupNoticeFr() = shot("bots_9b_add_to_group_notice_fr_oled", dark = true, oled = true) {
        Column(Modifier.padding(24.dp).padding(top = 80.dp)) {
            com.oshi.messenger.ui.bots.AddBotToChatContent(meteo.name, true, false, chats.take(1), chats[0], {},
                com.oshi.messenger.util.BotAdminRights.DEFAULT, {})
        }
    }
    @Test fun searchResultsLight() = shot("bots_10_search_results_light", dark = false) {
        Column(Modifier.padding(top = 60.dp)) {
            com.oshi.messenger.ui.bots.BotSearchContent(
                query = "@met", onQuery = {},
                search = com.oshi.messenger.ui.bots.BotDirectoryViewModel.SearchState.Results(listOf(meteo,
                    meteo.copy(botId = "id2", username = "metro_alertsbot", name = "Metro alerts", avatarEmoji = "🚇", about = "Line closures in real time"))),
                myBots = emptyList(), onPick = {}, onCreate = {},
            )
        }
    }
    @Test fun searchMyBotsOled() = shot("bots_11_add_bot_my_bots_oled", dark = true, oled = true) {
        Column(Modifier.padding(top = 60.dp)) {
            com.oshi.messenger.ui.bots.BotSearchContent(
                query = "", onQuery = {}, search = com.oshi.messenger.ui.bots.BotDirectoryViewModel.SearchState.Idle,
                myBots = listOf(meteo), onPick = {}, onCreate = {},
            )
        }
    }
    @Test fun ownerPublicProfileLight() = shot("bots_12_owner_public_profile_light", dark = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            com.oshi.messenger.ui.bots.BotPublicProfileCardContent(
                ui = com.oshi.messenger.ui.bots.BotPublicProfileViewModel.Ui(bot = fatherBot, username = "meteo_parisbot",
                    usernameStatus = com.oshi.messenger.ui.bots.BotFatherViewModel.UsernameStatus.Available,
                    about = "Weather for your group, every morning", isPublic = true),
                isServerBot = true, canSave = true, onUsername = {}, onAbout = {}, onPublic = {}, onSave = {},
                onShare = {}, onCopy = {}, onQr = {}, onRevoke = {},
            )
        }
    }
    @Test fun slashWithInsertArrowLight() = shot("bots_13_slash_arrow_and_arg_light", dark = false) {
        Column(Modifier.fillMaxSize().padding(top = 400.dp, start = 8.dp, end = 8.dp)) {
            SlashCommandBar(text = "/", botCommands = SlashCommandCatalog.botEntries(listOf(SlashCommandCatalog.BotCommandSource(
                "Meteo", "🌦️", "", listOf("/weather", "/forecast", "/help"),
                commandDescriptions = mapOf("weather" to "<city> Weather now", "forecast" to "The next three days", "help" to "All commands")))),
                onSend = {}, state = com.oshi.messenger.ui.components.SlashPickerState(), onPick = {})
        }
    }

    @Test fun hostingChoiceLight() = shot("bots_14_create_choice_light", dark = false) {
        Column(Modifier.padding(top = 120.dp)) { com.oshi.messenger.ui.bots.BotKindChoiceContent {} }
    }
    @Test fun hostingChoiceOled() = shot("bots_14_create_choice_oled", dark = true, oled = true) {
        Column(Modifier.padding(top = 120.dp)) { com.oshi.messenger.ui.bots.BotKindChoiceContent {} }
    }
}
