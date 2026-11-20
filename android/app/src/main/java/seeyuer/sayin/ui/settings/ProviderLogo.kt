package seeyuer.sayin.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import seeyuer.sayin.R
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.polish.PolishProvider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Brand marks are fixed-color assets on a light tile so the monochrome OpenAI mark stays visible. */
@Composable
internal fun ProviderLogo(provider: PolishProvider, modifier: Modifier = Modifier) {
    val asset = when (provider) {
        PolishProvider.DeepSeek -> R.drawable.ic_provider_deepseek
        PolishProvider.Qwen -> R.drawable.ic_provider_qwen
        PolishProvider.Anthropic -> R.drawable.ic_provider_claude
        PolishProvider.OpenAi -> R.drawable.ic_provider_openai
        PolishProvider.Custom -> null
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(7.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (asset != null) {
            Image(painterResource(asset), contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(3.dp))
        } else {
            Icon(VoiceLinkIcon.Ai.vector, null, Modifier.fillMaxSize().padding(4.dp),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }
}
