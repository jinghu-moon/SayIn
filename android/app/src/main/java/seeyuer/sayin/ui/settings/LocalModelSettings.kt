package seeyuer.sayin.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.models.*
import seeyuer.sayin.ui.AppSheetInsideMargin
import seeyuer.sayin.ui.SpeakSession
import seeyuer.sayin.ui.plainTextButtonColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ModelSettingsContent(session: SpeakSession, category: ModelCategory, openManagement: () -> Unit) {
    val models = session.localModels
    val selected = session.selectedLocalModel(category)
    val installed = models.catalog.entries(category).filter { it.id in models.installed }
    var chooseOpen by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (category == ModelCategory.Translation) {
            ModelSectionLabel(R.string.settings_language)
            SettingsCard {
                BasicComponent(
                    title = stringResource(R.string.local_model_direction),
                    summary = stringResource(R.string.local_model_zh_en),
                    startAction = { ModelIcon(VoiceLinkIcon.Translate) },
                )
            }
        }
        ModelSectionLabel(
            if (category == ModelCategory.Recognition) R.string.settings_local_recognition_model
            else R.string.settings_local_translation_model,
        )
        SettingsCard {
            if (selected != null) {
                ModelOverview(selected, title = selected.name, status =
                    R.string.local_model_recommended.takeIf { selected.id == models.catalog.recommendedId(category) })
            } else {
                BasicComponent(
                    title = stringResource(when {
                        !models.initialized -> R.string.settings_storage_calculating
                        installed.isEmpty() -> R.string.local_model_none
                        else -> R.string.local_model_unselected
                    }),
                    startAction = { ModelIcon(category.icon, accent = true) },
                )
            }
            if (models.initialized) {
                when {
                    installed.isEmpty() && selected == null -> ModelAction(
                        R.string.local_model_get, VoiceLinkIcon.Folder, openManagement,
                        Modifier.testTag("local-model-get"), primary = true,
                    )
                    selected == null || installed.size > 1 -> ModelAction(
                        if (selected != null) R.string.local_model_switch else R.string.local_model_choose,
                        VoiceLinkIcon.ArrowsLeftRight, { chooseOpen = true },
                        Modifier.testTag("local-model-catalog"), enabled = session.canChangeModels,
                    )
                }
            }
        }
    }
    ModelSelectionSheet(session, category, chooseOpen, { chooseOpen = false })
}

@Composable
internal fun ModelSelectionSheet(
    session: SpeakSession,
    category: ModelCategory,
    show: Boolean,
    dismiss: () -> Unit,
    onImportFolder: (() -> Unit)? = null,
) {
    val models = session.localModels
    val selected = session.selectedLocalModel(category)
    val installed = models.catalog.entries(category).filter { it.id in models.installed }
    var detailId by rememberSaveable(category, show) { mutableStateOf<String?>(null) }
    val detail = models.catalog.entries(category).firstOrNull { it.id == detailId }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = show, onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(detail?.name ?: stringResource(R.string.local_model_choose), style = MiuixTheme.textStyles.title2)
            if (detail != null && onImportFolder != null) {
                ModelDetails(detail, ModelStatus.Available, !models.busy, onImportFolder)
            } else {
                if (installed.isNotEmpty()) SettingsCard {
                    installed.forEachIndexed { index, model ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        BasicComponent(
                            modifier = Modifier.testTag("local-model-use-${model.id}"),
                            title = model.name,
                            summary = modelMetadata(model),
                            enabled = session.canChangeModels,
                            endActions = if (model.id == selected?.id) {
                                { Icon(VoiceLinkIcon.Check.vector, stringResource(R.string.local_model_selected),
                                    Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary) }
                            } else null,
                            onClick = {
                                val accepted = when (category) {
                                    ModelCategory.Recognition -> session.selectRecognitionModel(model)
                                    ModelCategory.Translation -> session.selectTranslationModel(model.id)
                                }
                                if (accepted) dismiss()
                            },
                        )
                    }
                }
                if (onImportFolder != null) {
                    val available = models.catalog.entries(category).filter { it.id !in models.installed }
                    if (available.isNotEmpty()) SettingsCard {
                        available.forEachIndexed { index, model ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ModelListRow(model, ModelStatus.Available) { detailId = model.id }
                        }
                    }
                    ModelAction(R.string.local_model_import, VoiceLinkIcon.Folder, onImportFolder, enabled = !models.busy,
                        primary = true)
                }
            }
        }
    }
}

@Composable
internal fun LocalModelStoragePage(
    session: SpeakSession,
    modifier: Modifier,
    scroll: ScrollState,
    focusCategory: ModelCategory?,
) {
    val models = session.localModels
    val openFolder = rememberModelFolderPicker(models)
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = models.catalog.all.firstOrNull { it.id == detailId }
    val confirm = models.catalog.all.firstOrNull { it.id == deleteId && it.id in models.installed }
    val requester = remember(focusCategory) { BringIntoViewRequester() }
    var placed by remember(focusCategory) { mutableStateOf(false) }
    LaunchedEffect(focusCategory, placed) {
        if (focusCategory == ModelCategory.Recognition) scroll.scrollTo(0)
        if (focusCategory == ModelCategory.Translation && placed) requester.bringIntoView()
    }

    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModelStorageSummary(models, openFolder)
        for (category in ModelCategory.entries) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val heading = if (category == focusCategory) {
                    Modifier.bringIntoViewRequester(requester).onGloballyPositioned { placed = true }
                } else Modifier
                ModelSectionLabel(category.title, heading)
                SettingsCard {
                    models.catalog.entries(category).forEachIndexed { index, model ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ModelListRow(model, session.modelStatus(model)) { detailId = model.id }
                    }
                }
            }
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = detail != null, onDismissRequest = { detailId = null }) {
        if (detail != null) key(detail.id) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(detail.name, style = MiuixTheme.textStyles.title2)
                val status = session.modelStatus(detail)
                ModelDetails(detail, status, !models.busy, {
                    detailId = null
                    openFolder()
                }, onDelete = {
                    detailId = null
                    deleteId = detail.id
                }, deleteEnabled = session.canChangeModels)
            }
        }
    }
    ModelImportPreview(models, openFolder)
    ModelProgressSheet(models)
    WindowDialog(
        title = stringResource(R.string.local_model_delete),
        summary = confirm?.let { stringResource(R.string.local_model_delete_summary, it.name) },
        show = confirm != null,
        onDismissRequest = { deleteId = null },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.assist_cancel), { deleteId = null }, Modifier.weight(1f))
            TextButton(stringResource(R.string.settings_translation_delete_confirm), {
                confirm?.let(session::deleteLocalModel)
                deleteId = null
            }, Modifier.weight(1f).testTag("local-model-delete-confirm"), enabled = session.canChangeModels)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelStorageSummary(models: LocalModelManager, openFolder: () -> Unit) {
    val context = LocalContext.current
    val bytes = ModelCategory.entries.associateWith { category ->
        models.catalog.entries(category).sumOf { models.installed[it.id] ?: 0L }
    }
    val total = bytes.values.sum()
    val primary = MiuixTheme.colorScheme.primary
    val translationColor = primary.copy(alpha = .44f)
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModelIcon(VoiceLinkIcon.Database, accent = true)
                    Column {
                        val count = models.installed.size
                        ModelNote(if (models.initialized) pluralStringResource(R.plurals.local_model_storage_count, count, count)
                            else stringResource(R.string.local_model_total))
                        Text(
                            if (models.initialized) Formatter.formatFileSize(context, total)
                            else stringResource(R.string.settings_storage_calculating),
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("local-model-storage-total"),
                        )
                    }
                }
                Button(openFolder, Modifier.testTag("local-model-import"), enabled = !models.busy,
                    insideMargin = PaddingValues(horizontal = 11.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Icon(VoiceLinkIcon.Folder.vector, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.local_model_import_short), fontSize = 13.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
            }
            if (models.initialized) {
                Row(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                    for ((category, size) in bytes) if (size > 0) {
                        val color = if (category == ModelCategory.Recognition) primary else translationColor
                        Box(Modifier.weight(size.toFloat()).fillMaxHeight().background(color))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((category, size) in bytes) {
                        ModelLegend(if (category == ModelCategory.Recognition) primary else translationColor,
                            stringResource(category.title), Formatter.formatFileSize(context, size))
                    }
                }
                val referenced = models.catalog.all.count { models.storage.isReference(it) }
                if (referenced > 0) {
                    ModelNote(stringResource(R.string.local_model_referenced_summary, referenced))
                }
            }
        }
    }
}

@Composable
private fun ModelListRow(model: LocalModel, status: ModelStatus, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val contentAlpha = if (status == ModelStatus.Available) 0.55f else 1f
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("local-model-manage-${model.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.alpha(contentAlpha)) {
            ModelIcon(
                model.category.icon,
                accent = status == ModelStatus.Selected,
                dashed = status == ModelStatus.Available,
                size = 38.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(contentAlpha), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                model.name,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                status.label?.let { label ->
                    ModelStatusIcon(status)
                    Text(
                        stringResource(label),
                        fontSize = 11.5.sp,
                        fontWeight = if (status == ModelStatus.Selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (status == ModelStatus.Selected) scheme.primary else scheme.onSurfaceSecondary,
                    )
                    Text(stringResource(R.string.local_model_meta_separator), fontSize = 11.5.sp, color = scheme.onSurfaceSecondary.copy(alpha = .55f))
                }
                Text(
                    modelMetadata(model),
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            VoiceLinkIcon.ChevronRight.vector, null, Modifier.size(17.dp),
            tint = scheme.onSurfaceSecondary.copy(alpha = .72f),
        )
    }
}

@Composable
private fun ModelStatusIcon(status: ModelStatus) {
    val scheme = MiuixTheme.colorScheme
    when (status) {
        ModelStatus.Selected -> Box(
            Modifier.size(14.dp).clip(CircleShape).background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(VoiceLinkIcon.Check.vector, null, Modifier.size(9.dp), tint = scheme.onPrimary)
        }
        ModelStatus.Installed -> Box(
            Modifier.size(14.dp).clip(CircleShape)
                .border(1.dp, scheme.onSurfaceSecondary.copy(alpha = .45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                VoiceLinkIcon.Check.vector, null, Modifier.size(9.dp),
                tint = scheme.onSurfaceSecondary.copy(alpha = .8f),
            )
        }
        ModelStatus.Available -> Icon(
            VoiceLinkIcon.Download.vector, null, Modifier.size(13.dp),
            tint = scheme.onSurfaceSecondary,
        )
        ModelStatus.Loading -> Unit
    }
}

@Composable
private fun ModelDetails(
    model: LocalModel,
    status: ModelStatus,
    importEnabled: Boolean,
    importFolder: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = false,
) {
    val context = LocalContext.current
    var filesOpen by rememberSaveable(model.id) { mutableStateOf(false) }
    var linkFailed by remember(model.id) { mutableStateOf(false) }
    SettingsCard { ModelOverview(model, stringResource(model.mode), status.label, status == ModelStatus.Selected) }

    val links = remember(model) { model.sources() }
    if (links.isNotEmpty()) {
        ModelSectionLabel(R.string.local_model_sources)
        SettingsCard {
            links.forEachIndexed { index, link ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                BasicComponent(
                    title = stringResource(link.title),
                    summary = stringResource(when {
                        link.url.endsWith(".tar.bz2") -> R.string.local_model_archive_file
                        link.url.endsWith(".gguf") -> R.string.local_model_gguf_file
                        else -> R.string.local_model_file_list
                    }),
                    modifier = Modifier.testTag("local-model-source-${link.title}"),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    startAction = {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Image(painterResource(link.logo), null, Modifier.size(19.dp),
                                colorFilter = if (link.logo == R.drawable.model_source_github)
                                    ColorFilter.tint(MiuixTheme.colorScheme.onSurface) else null)
                        }
                    },
                    endActions = { Icon(VoiceLinkIcon.ExternalLink.vector, null, Modifier.size(17.dp),
                        tint = MiuixTheme.colorScheme.primary) },
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                            linkFailed = false
                        } catch (_: ActivityNotFoundException) {
                            linkFailed = true
                        }
                    },
                )
            }
        }
        if (linkFailed) ModelNote(stringResource(R.string.local_model_browser_missing))
    }
    SettingsCard {
        BasicComponent(
            title = stringResource(R.string.local_model_files_count, model.files.size),
            modifier = Modifier.testTag("local-model-files-toggle-${model.id}"),
            startAction = { ModelIcon(VoiceLinkIcon.File) },
            endActions = { Icon(VoiceLinkIcon.ChevronDown.vector, null,
                Modifier.size(16.dp).rotate(if (filesOpen) 180f else 0f),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary) },
            onClick = { filesOpen = !filesOpen },
        )
        if (filesOpen) {
            SelectionContainer {
                Text(model.fileTree(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .testTag("local-model-files-${model.id}"))
            }
        }
    }
    when (status) {
        ModelStatus.Installed, ModelStatus.Selected -> if (onDelete != null) ModelAction(
            R.string.local_model_delete, VoiceLinkIcon.Clear, onDelete,
            Modifier.testTag("local-model-delete"), enabled = deleteEnabled, destructive = true,
        )
        ModelStatus.Available -> {
            ModelNote(stringResource(if (model.archive != null) R.string.local_model_extract_hint else R.string.local_model_folder_hint))
            ModelAction(R.string.local_model_import, VoiceLinkIcon.Folder, importFolder, enabled = importEnabled, primary = true)
        }
        ModelStatus.Loading -> Unit
    }
}

@Composable
private fun ModelOverview(model: LocalModel, title: String, @StringRes status: Int?, accentStatus: Boolean = true) {
    BasicComponent(
        title = title,
        summary = modelMetadata(model),
        startAction = { ModelIcon(model.category.icon, accent = true) },
        endActions = status?.let { { ModelBadge(it, accentStatus) } },
        modifier = Modifier.testTag("local-model-card-${model.id}"),
    )
    Text(stringResource(model.description), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        fontSize = 13.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun ModelAction(
    @StringRes title: Int,
    icon: VoiceLinkIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    val contentColor = when {
        !enabled -> scheme.onSurfaceSecondary
        primary -> scheme.onPrimary
        destructive -> scheme.error
        else -> scheme.primary
    }
    Button(
        onClick, modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        enabled = enabled,
        colors = when {
            primary -> ButtonDefaults.buttonColorsPrimary()
            destructive -> ButtonDefaults.buttonColors(color = scheme.errorContainer)
            else -> ButtonDefaults.buttonColors(color = scheme.primary.copy(alpha = .09f))
        },
    ) {
        Icon(icon.vector, null, Modifier.size(17.dp), tint = contentColor)
        Spacer(Modifier.width(7.dp))
        Text(stringResource(title), color = contentColor)
    }
}

@Composable
private fun ModelIcon(
    icon: VoiceLinkIcon,
    accent: Boolean = false,
    dashed: Boolean = false,
    size: Dp = 33.dp,
) {
    val scheme = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(9.dp)
    val base = Modifier.size(size).then(
        if (dashed) Modifier.drawBehind {
            val strokePx = 1.dp.toPx()
            val inset = strokePx / 2f
            drawRoundRect(
                color = scheme.onSurfaceSecondary.copy(alpha = .45f),
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - strokePx, this.size.height - strokePx),
                cornerRadius = CornerRadius(9.dp.toPx() - inset),
                style = Stroke(
                    width = strokePx,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        0f,
                    ),
                ),
            )
        } else Modifier.clip(shape)
            .background(if (accent) scheme.primary.copy(alpha = .12f) else scheme.surfaceContainerHigh),
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(base, contentAlignment = Alignment.Center) {
            Icon(
                icon.vector, null, Modifier.size(size * 0.56f),
                tint = if (accent) scheme.primary else scheme.onSurfaceSecondary,
            )
        }
    }
}

@Composable
private fun ModelBadge(@StringRes status: Int, active: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Text(
        stringResource(status), Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (active) scheme.primary.copy(alpha = .12f) else scheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        fontSize = 11.sp, color = if (active) scheme.primary else scheme.onSurfaceSecondary,
    )
}

@Composable
private fun modelMetadata(model: LocalModel) = stringResource(
    R.string.local_model_meta, model.precision, Formatter.formatFileSize(LocalContext.current, model.bytes),
)

private enum class ModelStatus(@StringRes val label: Int?) {
    Loading(null),
    Available(R.string.local_model_not_imported),
    Installed(R.string.local_model_imported),
    Selected(R.string.local_model_selected),
}

private fun SpeakSession.selectedLocalModel(category: ModelCategory): LocalModel? = when (category) {
    ModelCategory.Recognition -> currentRecognitionModel.takeIf { recognitionInstalled }
    ModelCategory.Translation -> currentTranslationModel.takeIf { translationModelReady }?.let { current ->
        localModels.catalog.translation.firstOrNull { it.id == current.id }
    }
}

private fun SpeakSession.modelStatus(model: LocalModel): ModelStatus = when {
    selectedLocalModel(model.category)?.id == model.id -> ModelStatus.Selected
    model.id in localModels.installed -> ModelStatus.Installed
    localModels.initialized -> ModelStatus.Available
    else -> ModelStatus.Loading
}

private val ModelCategory.title get() = if (this == ModelCategory.Recognition)
    R.string.settings_voice_recognition else R.string.settings_translation

private val ModelCategory.icon get() = if (this == ModelCategory.Recognition)
    VoiceLinkIcon.Microphone else VoiceLinkIcon.Translate

private val LocalModel.mode get() = when {
    category == ModelCategory.Translation -> R.string.local_model_mode_translation
    id == "x-asr" -> R.string.local_model_mode_live
    id == "r2t2" -> R.string.local_model_mode_preview
    else -> R.string.local_model_mode_batch
}

private data class ModelSource(@StringRes val title: Int, @DrawableRes val logo: Int, val url: String)

private fun LocalModel.sources(): List<ModelSource> = buildList {
    archive?.let {
        add(ModelSource(R.string.local_model_github_archive, R.drawable.model_source_github,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$it"))
    }
    huggingFacePath?.let {
        add(ModelSource(R.string.local_model_hf, R.drawable.model_source_huggingface, "https://huggingface.co$it"))
        add(ModelSource(R.string.local_model_hf_mirror, R.drawable.model_source_huggingface, "https://hf-mirror.com$it"))
    }
    modelScopePath?.let {
        add(ModelSource(R.string.local_model_scope, R.drawable.model_source_modelscope, "https://modelscope.cn/models$it"))
    }
}


@Composable
internal fun rememberModelFolderPicker(models: LocalModelManager): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(models::scanFolder)
    }
    return { if (!models.busy) launcher.launch(null) }
}

@Composable
internal fun ModelImportPreview(models: LocalModelManager, chooseAgain: () -> Unit) {
    val result = models.scan
    val context = LocalContext.current
    var reference by rememberSaveable(result) { mutableStateOf(false) }
    val canReference = result?.candidates?.isNotEmpty() == true &&
        result.candidates.all { it.sourceDirectory != null }
    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allFilesAccess = hasAllFilesAccess()
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = result != null, onDismissRequest = models::dismissScan) {
        if (result != null) Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.local_model_scan_title), style = MiuixTheme.textStyles.title2)
            if (result.candidates.isEmpty() && result.installed.isEmpty() && result.problems.isEmpty()) {
                ModelNote(stringResource(R.string.local_model_unsupported))
            }
            result.candidates.forEach { candidate ->
                BasicComponent(title = candidate.model.label, summary = Formatter.formatFileSize(context, candidate.model.bytes),
                    startAction = startIcon(candidate.model.icon))
            }
            result.installed.forEach {
                BasicComponent(title = it.label, summary = stringResource(R.string.local_model_already_installed), startAction = startIcon(VoiceLinkIcon.Check))
            }
            result.duplicates.forEach {
                BasicComponent(title = it.label, summary = stringResource(R.string.local_model_duplicate), startAction = startIcon(VoiceLinkIcon.Alert))
            }
            result.problems.forEach {
                BasicComponent(title = it.model.label,
                    summary = stringResource(R.string.local_model_invalid_files) + "\n" + it.files.joinToString("\n"),
                    startAction = startIcon(VoiceLinkIcon.Alert))
            }
            if (result.candidates.isNotEmpty()) {
                TabRowWithContour(
                    tabs = listOf(
                        stringResource(R.string.local_model_import_mode_copy),
                        stringResource(R.string.local_model_import_mode_reference),
                    ),
                    selectedTabIndex = if (reference) 1 else 0,
                    onTabSelected = { reference = it == 1 },
                    modifier = Modifier.fillMaxWidth().testTag("local-model-import-mode"),
                )
                if (reference) {
                    ModelNote(stringResource(when {
                        !canReference -> R.string.local_model_reference_unavailable
                        !allFilesAccess -> R.string.local_model_reference_permission
                        else -> R.string.local_model_import_reference_summary
                    }))
                    if (canReference && !allFilesAccess) {
                        TextButton(
                            stringResource(R.string.local_model_grant_all_files),
                            {
                                val direct = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                runCatching { permissionLauncher.launch(direct) }
                                    .onFailure { permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                            },
                            Modifier.fillMaxWidth().testTag("local-model-all-files"),
                            colors = plainTextButtonColors(),
                        )
                    }
                } else {
                    ModelNote(stringResource(
                        R.string.local_model_import_copy_summary,
                        Formatter.formatFileSize(context, result.candidates.sumOf { it.model.bytes }),
                    ))
                }
                val count = result.candidates.size
                val bytes = result.candidates.sumOf { it.model.bytes }
                Button(
                    onClick = { models.importScanned(if (reference) ModelImportMode.Reference else ModelImportMode.Copy) },
                    modifier = Modifier.fillMaxWidth().testTag("local-model-commit"),
                    enabled = !reference || (canReference && allFilesAccess),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    if (reference) {
                        Text(pluralStringResource(R.plurals.local_model_reference_count, count, count, Formatter.formatFileSize(context, bytes)))
                    } else {
                        Text(pluralStringResource(R.plurals.local_model_import_count, count, count, Formatter.formatFileSize(context, bytes)))
                    }
                }
            } else {
                TextButton(stringResource(R.string.local_model_choose_again), {
                    models.dismissScan()
                    chooseAgain()
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
@Composable
internal fun ModelProgressSheet(models: LocalModelManager) {
    val context = LocalContext.current
    val progress = models.progress
    val deleting = models.deleting
    val count = models.importedCount
    val completion = if (count > 0) pluralStringResource(R.plurals.local_model_import_complete, count, count) else null
    LaunchedEffect(count) {
        if (completion != null) {
            Toast.makeText(context, completion, Toast.LENGTH_SHORT).show()
            models.consumeImportResult()
        }
    }
    val title = when {
        models.scanning -> stringResource(R.string.local_model_scanning)
        progress != null && progress.reference -> stringResource(R.string.local_model_verifying_reference)
        progress != null -> stringResource(R.string.local_model_copying, progress.model.name)
        deleting != null -> stringResource(R.string.settings_translation_deleting)
        else -> null
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = title != null, allowDismiss = false) {
        if (title != null) Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MiuixTheme.textStyles.title2)
            if (progress != null) {
                ModelNote(stringResource(when {
                    progress.reference && progress.publishing -> R.string.local_model_publishing
                    progress.reference -> R.string.local_model_reference_verify
                    progress.publishing -> R.string.local_model_publishing
                    progress.copied >= progress.model.bytes -> R.string.local_model_verifying
                    else -> R.string.local_model_copy_verify
                }))
                LinearProgressIndicator(Modifier.fillMaxWidth(),
                    (progress.copied.toDouble() / progress.model.bytes).toFloat().coerceIn(0f, 1f))
                ModelNote(stringResource(R.string.local_model_progress_bytes,
                    Formatter.formatFileSize(context, progress.copied),
                    Formatter.formatFileSize(context, progress.model.bytes)))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (models.scanning || progress != null) {
                TextButton(stringResource(R.string.assist_cancel), models::cancelImport, Modifier.fillMaxWidth(),
                    enabled = progress?.publishing != true, colors = plainTextButtonColors())
            }
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = !models.busy && models.problems.isNotEmpty(), onDismissRequest = models::dismissProblems) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.local_model_operation_failed), style = MiuixTheme.textStyles.title2)
            ModelOperationErrors(models.problems)
            TextButton(stringResource(R.string.local_model_close_result), models::dismissProblems, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ModelOperationErrors(problems: List<ModelOperationProblem>) {
    SettingsCard {
        problems.forEach { problem ->
            val reason = stringResource(when (problem.error) {
                ModelOperationError.ReadFolder -> R.string.local_model_read_error
                ModelOperationError.Space -> R.string.local_model_space_error
                ModelOperationError.Integrity -> R.string.local_model_integrity_error
                ModelOperationError.Copy -> R.string.local_model_copy_error
                ModelOperationError.Reference -> R.string.local_model_reference_error
                ModelOperationError.Delete -> R.string.settings_translation_delete_failed
            })
            BasicComponent(
                title = problem.model?.name,
                summary = reason,
                startAction = startIcon(VoiceLinkIcon.Alert),
            )
        }
    }
}

@Composable
private fun ModelNote(text: String) = Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)

@Composable
internal fun ModelSectionLabel(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title),
        modifier = modifier.padding(start = 5.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
    )
}

@Composable
private fun ModelLegend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.local_model_legend, label, value), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
internal fun ModelFootnote(icon: VoiceLinkIcon, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon.vector, null, Modifier.size(14.dp).padding(top = 1.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

private val LocalModel.description get() = when (id) {
    "x-asr" -> R.string.local_model_xasr
    "r2t2" -> R.string.local_model_r2t2
    "qwen-06", "qwen-17" -> R.string.local_model_qwen
    "fun-int8" -> R.string.local_model_fun
    "plamo-0.8b-q4", "plamo-2b-q4" -> R.string.local_model_plamo
    else -> R.string.local_model_hy
}

private val LocalModel.icon get() = category.icon

private fun LocalModel.fileTree(): String = buildString {
    append("./")
    files.groupBy { it.path.substringBeforeLast('/', "") }.forEach { (directory, group) ->
        if (directory.isNotEmpty()) append("\n  $directory/")
        for (file in group) {
            append(if (directory.isEmpty()) "\n  " else "\n    ")
            append(file.path.substringAfterLast('/'))
        }
    }
}
