package lk.stanford.attendance

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Config {
    // Cloudflare Worker URL + /api
    const val API_URL = "https://stanford-attendance.attendance-079.workers.dev/api"
}

object Api {
    suspend fun call(action: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("${Config.API_URL}?action=$action").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "Server error ($code)")
            }
            json.put("http", code)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Cannot connect to the server. Please check your internet connection").put("http", 0)
        }
    }
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("att", Context.MODE_PRIVATE)

    var token: String?
        get() = p.getString("token", null)
        set(v) { p.edit().putString("token", v).apply() }

    var name: String
        get() = p.getString("name", "") ?: ""
        set(v) { p.edit().putString("name", v).apply() }

    fun clear() = p.edit().clear().apply()
}
