package com.azizexpress.gps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class MainActivity : AppCompatActivity() {

    private var isRunning = false
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDelivery: TextView
    private var activeDeliveryId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 120, 60, 60)
        }

        val title = TextView(this).apply {
            text = "🛵 Aziz GPS"
            textSize = 28f
            setPadding(0, 0, 0, 40)
        }

        tvDelivery = TextView(this).apply {
            text = "🔍 Recherche livraison active..."
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }

        tvStatus = TextView(this).apply {
            text = "⏸ En attente"
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }

        btnToggle = Button(this).apply {
            text = "Démarrer"
            textSize = 16f
            isEnabled = false
            setOnClickListener { toggleService() }
        }

        layout.addView(title)
        layout.addView(tvDelivery)
        layout.addView(tvStatus)
        layout.addView(btnToggle)
        setContentView(layout)

        requestPermissions()
        listenForActiveDelivery()
    }

    private fun listenForActiveDelivery() {
        Firebase.database.getReference("livraisons")
            .orderByChild("active").equalTo(true)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val delivery = snapshot.children.firstOrNull()
                    if (delivery != null) {
                        activeDeliveryId = delivery.key
                        val clientName = delivery.child("clientName").getValue(String::class.java) ?: "Client"
                        tvDelivery.text = "📦 $clientName"
                        btnToggle.isEnabled = true
                    } else {
                        activeDeliveryId = null
                        tvDelivery.text = "⏳ Aucune livraison active"
                        btnToggle.isEnabled = false
                        if (isRunning) stopGps()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun toggleService() {
        if (!isRunning) {
            activeDeliveryId?.let { id ->
                val intent = Intent(this, GpsService::class.java).apply {
                    putExtra("deliveryId", id)
                }
                ContextCompat.startForegroundService(this, intent)
                isRunning = true
                btnToggle.text = "Arrêter"
                tvStatus.text = "✅ GPS actif — envoi en cours"
            }
        } else {
            stopGps()
        }
    }

    private fun stopGps() {
        stopService(Intent(this, GpsService::class.java))
        isRunning = false
        btnToggle.text = "Démarrer"
        tvStatus.text = "⏸ En attente"
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
