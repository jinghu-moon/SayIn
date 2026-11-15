package seeyuer.sayin

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Isolated system permission probe, never included in the daily application. */
class NetworkPermissionProbeActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        getSharedPreferences("platform-permission-probe", MODE_PRIVATE).edit()
            .putBoolean(requireNotNull(intent.getStringExtra("ticket")), it).apply()
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
}
