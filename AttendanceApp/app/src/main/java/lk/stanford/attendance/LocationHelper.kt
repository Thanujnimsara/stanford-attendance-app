package lk.stanford.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

object LocationHelper {

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        return try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
        } catch (e: CancellationException) {
            cts.cancel()
            throw e
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
}
