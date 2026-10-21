package tf.monochrome.android.ui.eq

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.NothingEarFitter
import tf.monochrome.android.audio.eq.NothingEarShareCode
import tf.monochrome.android.domain.model.EqBand

/** Nothing X shows profile names in a single line; longer ones get cut. */
private const val NOTHING_NAME_CHARS = 24

private class NothingEarExport(val qr: Bitmap, val fit: NothingEarFitter.Fit)

/**
 * The current EQ as a Nothing X share code: [bands] fitted onto the Nothing
 * Ear's 8 bells and drawn as a QR code for Nothing X's Equalizer scanner.
 * Uses [NothingEarFitter.EXTENDED_LIMITS], gain past the ±6 dB the app's
 * sliders stop at, which the app keeps when it arrives in a code.
 *
 * A phone cannot scan its own screen, so the code can also be saved as an
 * image to show from another one.
 */
@Composable
fun NothingEarExportDialog(
    bands: List<EqBand>,
    name: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val export by produceState<NothingEarExport?>(null, bands, name) {
        value = withContext(Dispatchers.Default) {
            val fit = NothingEarFitter.fit(bands, NothingEarFitter.EXTENDED_LIMITS)
            val code = NothingEarShareCode.encode(
                NothingEarShareCode.Profile(
                    name.take(NOTHING_NAME_CHARS),
                    fit.bands.map { NothingEarShareCode.Band(it.freq, it.gain, it.q) },
                )
            )
            NothingEarExport(qrBitmap(code, 720), fit)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val qr = export?.qr
        if (uri != null && qr != null) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use {
                    qr.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: false
            } catch (_: Exception) {
                false
            }
            android.widget.Toast.makeText(
                context,
                context.getString(if (ok) R.string.eq_nothing_saved else R.string.eq_nothing_save_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_nothing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ready = export
                    if (ready == null) {
                        CircularProgressIndicator(Modifier.size(48.dp))
                    } else {
                        // The bitmap carries its own white quiet zone, which
                        // scanners need on a dark theme.
                        Image(
                            bitmap = ready.qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.eq_nothing_title),
                            modifier = Modifier.size(240.dp),
                        )
                    }
                }
                val ready = export
                Text(
                    if (ready == null) stringResource(R.string.eq_nothing_fitting)
                    else stringResource(
                        R.string.eq_nothing_fit,
                        "%.1f".format(ready.fit.maxErrorDb),
                        "%.1f".format(ready.fit.rmsErrorDb),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.eq_nothing_howto), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.eq_nothing_hack),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { saveLauncher.launch("${name.take(NOTHING_NAME_CHARS)} - Nothing Ear.png") },
                enabled = export != null,
            ) { Text(stringResource(R.string.eq_nothing_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** [text] as a black-on-white QR code, [px] square, with the standard 4-module quiet zone. */
private fun qrBitmap(text: String, px: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, px, px,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        ),
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i ->
        if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
