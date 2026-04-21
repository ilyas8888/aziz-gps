package com.azizexpress.gps

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class GpsService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var deliveryId: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        deliveryId = intent?.getStringExtra("deliveryId") ?: return START_NOT_STICKY

        createNotificationChannel()
        startForeground(1, buildNotification())

        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                Firebase.database
                    .getReference("livraisons/$deliveryId")
                    .updateChildren(mapOf(
                        "lat"       to loc.latitude,
                        "lng"       to loc.longitude,
                        "timestamp" to System.currentTimeMillis()
                    ))
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, GpsService::class.java).also { it.action = "STOP" }
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "gps_channel")
            .setContentTitle("Aziz GPS — Livraison active")
            .setContentText("Position envoyée en temps réel")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .addAction(android.R.drawable.ic_delete, "Arrêter", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "gps_channel", "GPS Livraison", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
