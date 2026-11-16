package lk.stanford.attendance

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import lk.stanford.attendance.databinding.ActivityLoginBinding
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        if (store.token != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnLogin.setOnClickListener {
            val phone = b.etPhone.text.toString().trim()
            val pin = b.etPin.text.toString()
            if (phone.isEmpty() || pin.isEmpty()) {
                b.tvStatus.text = "Enter your phone number and PIN"
                return@setOnClickListener
            }
            b.btnLogin.isEnabled = false
            b.tvStatus.text = "Logging in..."
            lifecycleScope.launch {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                val r = Api.call(
                    "login",
                    JSONObject().put("phone", phone).put("pin", pin).put("device_id", deviceId)
                )
                if (r.optBoolean("ok")) {
                    store.token = r.getString("token")
                    store.name = r.optString("name")
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    b.tvStatus.text = r.optString("error", "Error")
                    b.btnLogin.isEnabled = true
                }
            }
        }
    }
}
