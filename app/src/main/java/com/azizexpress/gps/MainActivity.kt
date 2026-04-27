package com.azizexpress.gps

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private var isRunning = false
    private lateinit var btnToggle: Button
    private lateinit var btnLogout: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDelivery: TextView
    private lateinit var mapView: MapView
    private var activeDeliveryId: String? = null
    private var courierMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routeOverlay: Polyline? = null
    private var destLat: Double? = null
    private var destLng: Double? = null
    private var locationManager: LocationManager? = null

    private val locationListener = LocationListener { location ->
        updateCourierOnMap(location)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Firebase.auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        tvDelivery = findViewById(R.id.tvDelivery)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnLogout = findViewById(R.id.btnLogout)
        mapView = findViewById(R.id.mapView)

        btnToggle.setOnClickListener { toggleService() }
        btnLogout.setOnClickListener {
            stopGps()
            Firebase.auth.signOut()
            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            finish()
        }

        setupMap()
        checkForUpdate()
        requestPermissions()
        restoreState()
        listenForActiveDelivery()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        startMapLocationUpdates()
    }

    override fun onPause() {
        locationManager?.removeUpdates(locationListener)
        mapView.onPause()
        super.onPause()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(34.6814, -1.9086))
    }

    private fun checkForUpdate() {
        Firebase.database.getReference("_config").get()
            .addOnSuccessListener { snap ->
                val remoteVersion = snap.child("version").getValue(Long::class.java) ?: return@addOnSuccessListener
                val apkUrl = snap.child("apkUrl").getValue(String::class.java) ?: return@addOnSuccessListener
                if (remoteVersion > BuildConfig.VERSION_CODE) {
                    AlertDialog.Builder(this)
                        .setTitle("Mise à jour disponible")
                        .setMessage("Une nouvelle version de l'app est disponible.")
                        .setPositiveButton("Télécharger") { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl)))
                        }
                        .setNegativeButton("Plus tard", null)
                        .show()
                }
            }
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission requise")
                    .setMessage("Autorisez l'app à installer des mises à jour.")
                    .setPositiveButton("Autoriser") { _, _ ->
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
                return
            }
        }

        val request = DownloadManager.Request(android.net.Uri.parse(apkUrl))
            .setTitle("Aziz GPS — Mise à jour")
            .setDescription("Téléchargement en cours...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, "aziz-update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    unregisterReceiver(this)
                    installApk()
                }
            }
        }
        registerReceiver(
            receiver,
            android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            android.content.Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk() {
        val file = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "aziz-update.apk")
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startMapLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = manager

        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::updateCourierOnMap)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(::updateCourierOnMap)

        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, locationListener)
        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, locationListener)
    }

    private fun updateCourierOnMap(location: Location) {
        val fromLat = location.latitude
        val fromLng = location.longitude
        val point = GeoPoint(fromLat, fromLng)
        if (courierMarker == null) {
            courierMarker = Marker(mapView).apply {
                title = "Votre position"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(courierMarker)
        }
        courierMarker?.position = point

        val currentDestLat = destLat
        val currentDestLng = destLng
        if (currentDestLat != null && currentDestLng != null) {
            drawRoute(fromLat, fromLng, currentDestLat, currentDestLng)
        } else {
            mapView.controller.animateTo(point)
        }
        mapView.invalidate()
    }

    private fun updateDestinationOnMap(lat: Double, lng: Double, clientName: String) {
        destLat = lat
        destLng = lng
        val point = GeoPoint(lat, lng)
        if (destinationMarker == null) {
            destinationMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(destinationMarker)
        }
        destinationMarker?.title = "Destination: $clientName"
        destinationMarker?.position = point
        mapView.invalidate()
    }

    private fun clearDestinationMarker() {
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = null
        routeOverlay?.let { mapView.overlays.remove(it) }
        routeOverlay = null
        destLat = null
        destLng = null
        mapView.invalidate()
    }

    private fun drawRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double) {
        Thread {
            try {
                val url = java.net.URL(
                    "https://router.project-osrm.org/route/v1/driving/" +
                        "$fromLng,$fromLat;$toLng,$toLat?overview=full&geometries=geojson"
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val points = parseOsrmCoords(json)
                runOnUiThread { applyRoute(points, fromLat, fromLng, toLat, toLng) }
            } catch (_: Exception) {
                runOnUiThread { applyRoute(emptyList(), fromLat, fromLng, toLat, toLng) }
            }
        }.start()
    }

    private fun applyRoute(points: List<GeoPoint>, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double) {
        routeOverlay?.let { mapView.overlays.remove(it) }

        val routePoints = if (points.isNotEmpty()) {
            points
        } else {
            listOf(GeoPoint(fromLat, fromLng), GeoPoint(toLat, toLng))
        }

        routeOverlay = Polyline().apply {
            setPoints(routePoints)
            outlinePaint.color = Color.parseColor("#FF5A1F")
            outlinePaint.strokeWidth = if (points.isNotEmpty()) 8f else 6f
        }

        mapView.overlays.add(0, routeOverlay)
        val box = BoundingBox.fromGeoPoints(listOf(GeoPoint(fromLat, fromLng), GeoPoint(toLat, toLng)))
        mapView.zoomToBoundingBox(box.increaseByScale(1.4f), true)
        mapView.invalidate()
    }

    private fun parseOsrmCoords(json: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        try {
            val routes = JSONObject(json).getJSONArray("routes")
            if (routes.length() == 0) {
                return points
            }

            val coords = routes.getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")

            for (i in 0 until coords.length()) {
                val coord = coords.getJSONArray(i)
                points.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
            }
        } catch (_: Exception) {
        }
        return points
    }

    private fun restoreState() {
        val prefs = getSharedPreferences("gps_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("running", false)) {
            isRunning = true
            btnToggle.text = "ArrÃªter"
            tvStatus.text = "GPS actif - envoi en cours"
        }
    }

    private fun listenForActiveDelivery() {
        val uid = Firebase.auth.currentUser?.uid ?: return

        Firebase.database.getReference("livraisons")
            .orderByChild("active").equalTo(true)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val delivery = snapshot.children
                        .filter { it.child("courierId").getValue(String::class.java) == uid }
                        .maxByOrNull {
                            it.child("createdAt").getValue(Long::class.java)
                                ?: it.child("timestamp").getValue(Long::class.java)
                                ?: 0L
                        }

                    if (delivery != null) {
                        activeDeliveryId = delivery.key
                        val clientName = delivery.child("clientName").getValue(String::class.java) ?: "Client"
                        tvDelivery.text = "Livraison active: $clientName"
                        btnToggle.isEnabled = true

                        val destLat = delivery.child("destLat").getValue(Double::class.java)
                        val destLng = delivery.child("destLng").getValue(Double::class.java)
                        if (destLat != null && destLng != null) {
                            updateDestinationOnMap(destLat, destLng, clientName)
                        } else {
                            clearDestinationMarker()
                        }

                        if (!isRunning) {
                            startGpsServiceForActiveDelivery()
                        }
                    } else {
                        activeDeliveryId = null
                        tvDelivery.text = "Aucune livraison active"
                        btnToggle.isEnabled = false
                        clearDestinationMarker()
                        if (isRunning) {
                            stopGps()
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            })
    }

    private fun startGpsServiceForActiveDelivery() {
        activeDeliveryId?.let { id ->
            val intent = Intent(this, GpsService::class.java).apply {
                putExtra("deliveryId", id)
            }
            ContextCompat.startForegroundService(this, intent)
            isRunning = true
            btnToggle.text = "ArrÃªter"
            tvStatus.text = "GPS actif - envoi en cours"
        }
    }

    private fun toggleService() {
        if (!isRunning) {
            startGpsServiceForActiveDelivery()
        } else {
            stopGps()
        }
    }

    private fun stopGps() {
        activeDeliveryId?.let { id ->
            Firebase.database.getReference("livraisons/$id/active").setValue(false)
        }
        stopService(Intent(this, GpsService::class.java))
        isRunning = false
        btnToggle.text = "DÃ©marrer"
        tvStatus.text = "En attente"
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }
}




