package dev.tubebeam.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tubebeam.TubeBeamApp
import dev.tubebeam.ui.MainActivity
import dev.tubebeam.ui.theme.TubeBeamTheme
import kotlinx.coroutines.delay

/**
 * Entry point from the system share sheet. Translucent, draws a card over the sharing app,
 * stays alive only for the ~1 s a send takes. No service, no overlay permission.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = TubeBeamApp.container(this)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.dataString // also accept ACTION_VIEW on a youtube link

        setContent {
            TubeBeamTheme {
                val vm: ShareViewModel = viewModel { ShareViewModel(container) }
                LaunchedEffect(Unit) { vm.start(sharedText) }
                val ui by vm.ui.collectAsStateWithLifecycle()

                LaunchedEffect(ui) {
                    when (ui) {
                        ShareUi.NotYouTube -> { toast("Not a YouTube link"); finish() }
                        ShareUi.NoTvs -> { toast("Add a TV first"); openApp(); finish() }
                        is ShareUi.Sent -> { delay(1_100); finish() }
                        else -> Unit
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val showCard = ui is ShareUi.Pick || ui is ShareUi.Sending || ui is ShareUi.Sent || ui is ShareUi.Error
                    if (showCard) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(16.dp)
                                .fillMaxWidth()
                                .pointerInput(Unit) { } // swallow taps so they don't reach the scrim
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { },
                        ) {
                            when (val s = ui) {
                                is ShareUi.Pick -> TvPickerSheet(
                                    tvs = s.tvs,
                                    defaultId = s.defaultId,
                                    status = s.status,
                                    onPick = { tv, remember -> vm.choose(tv, remember) },
                                    onLocate = vm::locate,
                                )
                                is ShareUi.Sending -> SendingCard(s.tv.name, s.progress)
                                is ShareUi.Sent -> SentCard(s.tv.name, ShareViewModel.describePackage(s.packageUsed))
                                is ShareUi.Error -> ErrorCard(
                                    message = s.message,
                                    hint = s.hint,
                                    onRetry = vm::retry,
                                    onOpenApp = { openApp(s.tv.id); finish() },
                                    onClose = ::finish,
                                )
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun openApp(tvId: Long? = null) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tvId != null) putExtra(MainActivity.EXTRA_TV_ID, tvId)
            },
        )
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
