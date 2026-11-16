package lk.stanford.attendance

import android.Manifest
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lk.stanford.attendance.databinding.ActivityMainBinding
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: Store
    private var cfg: JSONObject? = null
    private var enrolled = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (store.token == null) { logout(); return }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvName.text = store.name

        b.btnMark.setOnClickListener { openCamera("mark") }
        b.btnEnroll.setOnClickListener { openCamera("enroll") }
        b.btnLogout.setOnClickListener { logout() }

        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { loop() }
        }
    }

    private fun openCamera(mode: String) {
        startActivity(Intent(this, CameraActivity::class.java).putExtra("mode", mode))
    }

    private fun logout() {
        Store(this).clear()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun setButtons(inRange: Boolean) {
        b.btnMark.visibility = if (enrolled) View.VISIBLE else View.GONE
        b.btnEnroll.visibility = if (enrolled) View.GONE else View.VISIBLE
        b.btnMark.isEnabled = inRange
        b.btnEnroll.isEnabled = inRange
    }

    private suspend fun loop() {
        // Reload the config + summary every time the screen comes back (for example after marking attendance)
        val r = Api.call("config", JSONObject().put("token", store.token))
        if (r.optInt("http") == 401) { logout(); return }
        if (r.optBoolean("ok")) {
            enrolled = r.optBoolean("face_enrolled")
            b.tvName.text = r.optString("name")
            showSummary(r.optJSONObject("summary"))
            if (r.optBoolean("branch_missing")) {
                cfg = null
                b.tvDistance.text = "No branch is assigned to you. Please contact the admin."
            } else {
                cfg = r
            }
        } else {
            b.tvDistance.text = r.optString("error")
        }
        setButtons(false)

        while (true) {
            val c = cfg
            if (c != null) {
                if (!LocationHelper.hasPermission(this)) {
                    b.tvDistance.text = "Please allow the Location permission (Settings > Apps > Permissions)"
                    setButtons(false)
                } else {
                    val loc = LocationHelper.current(this)
                    if (loc == null) {
                        b.tvDistance.text = "Cannot get your GPS location. Please turn on Location"
                        setButtons(false)
                    } else {
                        showLocation(loc, c)
                    }
                }
            }
            delay(4000)
        }
    }

    private fun showLocation(loc: Location, c: JSONObject) {
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, c.optDouble("lat"), c.optDouble("lng"), res)
        val dist = res[0]
        val radius = c.optDouble("radius", 30.0)
        val maxAcc = c.optDouble("max_accuracy", 30.0)
        val mock = LocationHelper.isMock(loc)
        val inRange = !mock && loc.accuracy <= maxAcc && dist <= radius

        b.tvDistance.text = when {
            mock -> "A fake GPS app was detected"
            loc.accuracy > maxAcc -> "GPS accuracy is low (±${loc.accuracy.roundToInt()} m). Please wait a moment"
            dist > radius -> "You are ${dist.roundToInt()} m from the office. Move within ${radius.roundToInt()} m"
            else -> "You are inside the office area (${dist.roundToInt()} m)"
        }
        setButtons(inRange)
    }

    // ---------- summary cards ----------
    private fun n(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private fun showSummary(s: JSONObject?) {
        if (s == null) return
        val branch = s.optString("branch", "")
        b.tvBranch.text = "$branch  •  Duty ${s.optString("duty")}  •  Day off: ${s.optString("weekly_off")}"

        val t = s.optJSONObject("today")
        if (t != null) {
            val reason = t.optString("reason")
            b.tvToday.text = "${t.optString("status")} - ${t.optString("category")}" +
                if (reason.isNotEmpty()) "\n$reason" else ""
        }

        val m = s.optJSONObject("month")
        if (m != null) {
            b.tvMonthTitle.text = m.optString("label")
            val ded = m.optDouble("late_deduction", 0.0)
            b.tvMonth.text = buildString {
                append("Present: ${m.optInt("present")} days    Absent: ${n(m.optDouble("absent", 0.0))} days\n")
                append("Late arrivals: ${m.optInt("late_days")} (${m.optInt("late_free")} free each month)\n")
                if (ded > 0) append("Late deduction: ${n(ded)} day(s) of ${m.optString("late_deduct_type")} leave\n")
                append("Short leave used: ${m.optInt("short_used")} of ${m.optInt("short_allowed")} (up to ${m.optInt("short_minutes")} min each)")
            }
        }

        val l = s.optJSONObject("leave")
        if (l != null) {
            fun line(name: String, key: String): String {
                val o = l.optJSONObject(key) ?: return "$name: -"
                return "$name: ${n(o.optDouble("left"))} left of ${n(o.optDouble("total"))}"
            }
            b.tvLeave.text = listOf(line("Sick", "sick"), line("Casual", "casual"), line("Official", "official")).joinToString("\n")
        }
    }
}
