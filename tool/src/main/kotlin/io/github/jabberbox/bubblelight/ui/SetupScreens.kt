package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.data.QrSetup
import io.github.jabberbox.bubblelight.data.ServerSetup
import io.github.jabberbox.bubblelight.data.Store

/**
 * Asks how to give the tool a server: scan the QR code the BlueBubbles server shows, or type the address and
 * password. Returns the [ServerSetup] without connecting, so first-time setup and Change server share it.
 */
class SetupScreen(
    sealedActivity: SealedLightActivity,
    private val title: String = "Connect to server",
) : BubbleSimpleScreen<ServerSetup>(sealedActivity) {
    private var error by mutableStateOf<String?>(null)

    @Composable
    override fun Body() {
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                Option(
                    label = "Scan QR code",
                    hint = "Open your BlueBubbles server on the Mac and scan the code it shows.",
                    onClick = ::scan,
                )
                Option(
                    label = "Type address and password",
                    hint = "The server's web address, then its password.",
                    onClick = ::type,
                )
                error?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }

    @Composable
    private fun Option(label: String, hint: String, onClick: () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 0.75f.gridUnitsAsDp()),
        ) {
            LightText(text = label, variant = LightTextVariant.Copy)
            LightText(text = hint, variant = LightTextVariant.Fine, lighten = true)
        }
    }

    private fun scan() {
        error = null
        navigateTo({ QrScanScreen(it) }) { scanned ->
            val setup = QrSetup.parse(scanned)
            if (setup == null) error = "That is not a BlueBubbles setup code." else goBack(setup)
        }
    }

    private fun type() {
        error = null
        navigateTo({ TextEntryScreen(it, title = "Server address", submitLabel = "NEXT") }) { typed ->
            val url = Store.normalizeUrl(typed)
            if (url == null) {
                error = "That does not look like a server address."
                return@navigateTo
            }
            navigateTo({ TextEntryScreen(it, title = "Password", submitLabel = "CONNECT") }) { password ->
                goBack(ServerSetup(url, password.trim()))
            }
        }
    }
}

/**
 * The SDK's QR scanner, returning the raw text it reads. The SDK asks that navigation after a scan wait for a
 * LaunchedEffect, so the result is parked in state and handed back from there.
 */
class QrScanScreen(sealedActivity: SealedLightActivity) : BubbleSimpleScreen<String>(sealedActivity) {
    private var scanned by mutableStateOf<String?>(null)

    @Composable
    override fun Body() {
        val value = scanned
        LaunchedEffect(value) { if (value != null) goBack(value) }
        BubbleTheme {
            LightQrCodeScanner(onScanned = { scanned = it }, onBack = { goBack() }, title = "Scan QR code")
        }
    }
}
