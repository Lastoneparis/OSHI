package com.oshi.messenger.ui.scan

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.oshi.messenger.R

/**
 * The QR scanner, pinned to portrait and given OSHI's own viewfinder.
 *
 * Two reasons this exists rather than using ZXing's stock `CaptureActivity`:
 *
 *  1. **Orientation.** Both call sites used to pass `setOrientationLocked(false)`,
 *     which lets the capture activity follow the sensor. On a device held
 *     naturally that produced a scanner drawn sideways — the "horizontal" scan
 *     the owner reported. The fix is a manifest entry pinning this activity to
 *     portrait AND `setOrientationLocked(true)` at the call site; either alone
 *     is not enough, because the lock only freezes whatever orientation the
 *     activity happened to start in.
 *
 *  2. **Presentation.** The stock layout is a bare full-bleed preview with a red
 *     laser line. `R.layout.oshi_capture` replaces it with a dimmed surround, a
 *     square framed window and the prompt above it, which is also what tells the
 *     user *what* to point the camera at.
 *
 * Note for anyone touching the manifest entry: `targetSdk 36` ignores
 * `screenOrientation` on any display ≥ 600 dp, so on a tablet or an unfolded
 * foldable this will still rotate. The layout is built to survive that rather
 * than to assume it cannot happen.
 */
class OshiScanActivity : CaptureActivity() {

    /**
     * `CaptureActivity` inflates `R.layout.zxing_capture` in its own `onCreate`
     * and then looks the view up by id, so the layout has to be swapped by
     * overriding this hook rather than by calling `setContentView` afterwards —
     * doing it afterwards leaves the manager bound to the discarded view and the
     * preview never starts.
     */
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.oshi_capture)
        return findViewById(R.id.zxing_barcode_scanner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same screenshot protection as MainActivity (ON unless the user turned it
        // off): the viewfinder shows someone's identity QR.
        com.oshi.messenger.service.ScreenshotProtectionManager.applyToUnmanagedActivity(this)
        // The laser line belongs to ZXing's viewfinder, which this layout does not
        // use; the framed window is the affordance instead.
    }
}
