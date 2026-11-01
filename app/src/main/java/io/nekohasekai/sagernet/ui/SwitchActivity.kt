package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.R

class SwitchActivity : ThemedActivity(R.layout.layout_empty) {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        // The profile list lives in the Flutter UI now (batch L2).
        startActivity(
            Intent(this, io.throne.bbox.flutter.BboxFlutterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        finish()
    }
}
