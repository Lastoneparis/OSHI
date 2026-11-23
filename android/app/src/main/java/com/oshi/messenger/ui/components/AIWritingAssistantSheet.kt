package com.oshi.messenger.ui.components

import androidx.compose.foundation.horizontalScroll
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.service.NLPManager
import com.oshi.messenger.ui.viewmodel.NLPViewModel

/**
 * AIWritingAssistantSheet - Android equivalent of the iOS AI writing assistant
 * "onglet" that lives above the input bar in ChatView.
 *
 * Three tabs:
 *  - Improve: 9 tones (3 categories) + Correct + Translate
 *  - Generate: 4 conversation modes (Icebreaker/Relaunch/Flirty/Friendly)
 *  - Thinking of you: tone picker + one generated message
 *
 * The result is just plain text placed into the input bar (cross-platform safe —
 * no special wire format).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIWritingAssistantSheet(
    initialText: String,
    conversationContext: String,
    contactName: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: NLPViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Local editable text for the Improve tab
    var editorText by remember(initialText) { mutableStateOf(initialText) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(initialText, conversationContext) {
        viewModel.setOriginalText(initialText)
        viewModel.setConversationContext(conversationContext)
    }

    val tabTitles = listOf(
        stringResource(R.string.ai_tab_improve),
        stringResource(R.string.ai_tab_generate),
        stringResource(R.string.ai_tab_thinking_of_you)
    )

    val accent = Color(0xFF5616EC)

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ai_assistant_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = accent
            ) {
                tabTitles.forEachIndexed { i, title ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = {
                            selectedTab = i
                            viewModel.clearTransientResults()
                        },
                        text = { Text(title, fontSize = 13.sp) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                0 -> ImproveTab(
                    state = uiState,
                    accent = accent,
                    editorText = editorText,
                    onEditorTextChange = { editorText = it },
                    onImprove = { viewModel.aiImprove(editorText, uiState.selectedStyle) },
                    onCorrect = { viewModel.aiCorrect(editorText) },
                    onSelectStyle = { viewModel.selectStyle(it) },
                    onSelectCategory = { viewModel.selectCategory(it) },
                    onSelectTranslationTarget = { viewModel.selectTranslationTarget(it) },
                    onTranslate = {
                        val target = uiState.selectedTranslationTarget
                            ?: NLPViewModel.TRANSLATION_TARGETS.first()
                        viewModel.aiTranslate(editorText, target)
                    },
                    onUseResult = {
                        onApply(uiState.correctedText.ifBlank { editorText })
                        onDismiss()
                    },
                    onRegenerate = { viewModel.processCurrentAction() }
                )

                1 -> GenerateTab(
                    state = uiState,
                    accent = accent,
                    onSelectMode = { viewModel.selectGeneratorMode(it) },
                    onGenerate = { viewModel.generateConversationSuggestions(contactName) },
                    onPickSuggestion = { suggestion ->
                        onApply(suggestion)
                        onDismiss()
                    }
                )

                2 -> ThinkingOfYouTab(
                    state = uiState,
                    accent = accent,
                    onSelectTone = { viewModel.selectThinkingOfYouTone(it) },
                    onGenerate = { viewModel.generateThinkingOfYou(contactName) },
                    onUse = {
                        val text = uiState.thinkingOfYouResult
                        if (text.isNotBlank()) {
                            onApply(text)
                            onDismiss()
                        }
                    },
                    onRegenerate = { viewModel.generateThinkingOfYou(contactName) }
                )
            }
        }
    }
}

// ============================================================================
// IMPROVE TAB
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImproveTab(
    state: NLPViewModel.NLPUiState,
    accent: Color,
    editorText: String,
    onEditorTextChange: (String) -> Unit,
    onImprove: () -> Unit,
    onCorrect: () -> Unit,
    onSelectStyle: (NLPManager.ImprovementStyle) -> Unit,
    onSelectCategory: (NLPManager.StyleCategory) -> Unit,
    onSelectTranslationTarget: (NLPViewModel.TranslationTarget) -> Unit,
    onTranslate: () -> Unit,
    onUseResult: () -> Unit,
    onRegenerate: () -> Unit
) {
    Column {
        // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
        // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
        com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
            OutlinedTextField(
                value = editorText,
                onValueChange = onEditorTextChange,
                label = { Text(stringResource(R.string.message_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                maxLines = 5
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tone categories
        val categories = NLPManager.StyleCategory.entries
        val selectedCategoryIndex = categories.indexOf(state.selectedCategory).coerceAtLeast(0)

        TabRow(
            selectedTabIndex = selectedCategoryIndex,
            containerColor = Color.Transparent,
            contentColor = accent
        ) {
            categories.forEachIndexed { idx, category ->
                Tab(
                    selected = selectedCategoryIndex == idx,
                    onClick = { onSelectCategory(category) },
                    text = { Text(category.displayName, fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tone chips for the selected category
        val stylesInCategory = NLPManager.ImprovementStyle.entries.filter {
            it.category == state.selectedCategory
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            stylesInCategory.forEach { style ->
                OshiFilterChip(
                    selected = state.selectedStyle == style,
                    onClick = { onSelectStyle(style) },
                    label = { Text(style.displayName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (style.category) {
                            NLPManager.StyleCategory.GENERAL -> accent
                            NLPManager.StyleCategory.SOCIAL -> Color(0xFF2196F3)
                            NLPManager.StyleCategory.DATING -> Color(0xFFE91E63)
                        },
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action buttons row: Improve, Correct
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onImprove,
                enabled = !state.isLoading && editorText.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.ai_quickbar_improve), fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onCorrect,
                enabled = !state.isLoading && editorText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.ai_correct), fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Translate row
        Text(stringResource(R.string.ai_translate), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NLPViewModel.TRANSLATION_TARGETS.forEach { target ->
                OshiFilterChip(
                    selected = state.selectedTranslationTarget?.code == target.code,
                    onClick = { onSelectTranslationTarget(target) },
                    label = { Text(target.name, fontSize = 12.sp) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTranslate,
            enabled = !state.isLoading && editorText.isNotBlank() && state.selectedTranslationTarget != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.ai_translate))
        }

        // Loader
        if (state.isLoading) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_shortcuts_processing), fontSize = 13.sp)
            }
        }

        // Result
        if (state.correctedText.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            OshiCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.correctedText,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onUseResult,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text(stringResource(R.string.ai_use_suggestion))
                }
                OutlinedButton(
                    onClick = onRegenerate,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ai_regenerate))
                }
            }
        }
    }
}

// ============================================================================
// GENERATE TAB
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerateTab(
    state: NLPViewModel.NLPUiState,
    accent: Color,
    onSelectMode: (NLPManager.ConversationGeneratorMode) -> Unit,
    onGenerate: () -> Unit,
    onPickSuggestion: (String) -> Unit
) {
    Column {
        Text(stringResource(R.string.ai_shortcuts_conversation_mode), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NLPManager.ConversationGeneratorMode.entries.forEach { mode ->
                val labelRes = when (mode) {
                    NLPManager.ConversationGeneratorMode.ICEBREAKER -> R.string.ai_mode_icebreaker
                    NLPManager.ConversationGeneratorMode.RELAUNCH -> R.string.ai_mode_relaunch
                    NLPManager.ConversationGeneratorMode.FLIRTY -> R.string.ai_mode_flirty
                    NLPManager.ConversationGeneratorMode.FRIENDLY -> R.string.ai_mode_friendly
                }
                OshiFilterChip(
                    selected = state.selectedGeneratorMode == mode,
                    onClick = { onSelectMode(mode) },
                    label = { Text(stringResource(labelRes), fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (mode) {
                            NLPManager.ConversationGeneratorMode.FLIRTY -> Color(0xFFE91E63)
                            NLPManager.ConversationGeneratorMode.FRIENDLY -> Color(0xFF2196F3)
                            else -> accent
                        },
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_shortcuts_generating))
            } else {
                Text(stringResource(R.string.ai_generate_suggestions))
            }
        }

        // Suggestions
        if (state.conversationSuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = if (state.isRuleBased) MaterialTheme.colorScheme.tertiaryContainer
                       else accent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (state.isRuleBased) stringResource(R.string.ai_shortcuts_rule_based)
                    else stringResource(R.string.ai_shortcuts_ai),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            state.conversationSuggestions.forEach { suggestion ->
                OshiCard(
                    onClick = { onPickSuggestion(suggestion) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Text(
                        text = suggestion,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// ============================================================================
// THINKING-OF-YOU TAB
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingOfYouTab(
    state: NLPViewModel.NLPUiState,
    accent: Color,
    onSelectTone: (NLPManager.ImprovementStyle) -> Unit,
    onGenerate: () -> Unit,
    onUse: () -> Unit,
    onRegenerate: () -> Unit
) {
    // Subset of tones that map well to a "thinking of you" message
    val tones = listOf(
        NLPManager.ImprovementStyle.FRIENDLY,
        NLPManager.ImprovementStyle.CASUAL,
        NLPManager.ImprovementStyle.PLAYFUL,
        NLPManager.ImprovementStyle.FLIRTY,
        NLPManager.ImprovementStyle.SEDUCTIVE,
        NLPManager.ImprovementStyle.FORMAL
    )

    Column {
        Text(
            stringResource(R.string.ai_thinking_of_you_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tones.forEach { tone ->
                OshiFilterChip(
                    selected = state.thinkingOfYouTone == tone,
                    onClick = { onSelectTone(tone) },
                    label = { Text(tone.displayName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (tone.category) {
                            NLPManager.StyleCategory.GENERAL -> accent
                            NLPManager.StyleCategory.SOCIAL -> Color(0xFF2196F3)
                            NLPManager.StyleCategory.DATING -> Color(0xFFE91E63)
                        },
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_shortcuts_generating))
            } else {
                Text(stringResource(R.string.ai_generate_suggestions))
            }
        }

        if (state.thinkingOfYouResult.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            OshiCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.thinkingOfYouResult,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onUse,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text(stringResource(R.string.ai_use_suggestion))
                }
                OutlinedButton(
                    onClick = onRegenerate,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ai_regenerate))
                }
            }
        }
    }
}
