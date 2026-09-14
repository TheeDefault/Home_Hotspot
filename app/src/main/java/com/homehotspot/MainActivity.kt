package com.homehotspot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var tvHomeLocation: TextView
    private lateinit var btnFetchLocation: MaterialButton
    private lateinit var tvRadiusLabel: TextView
    private lateinit var seekBarRadius: SeekBar
    private lateinit var tvStatus: TextView
    private lateinit var btnMonitor: MaterialButton
    private lateinit var btnMonitor10s: MaterialButton
    private lateinit var btnStopMonitoring: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var btnTestSequence: MaterialButton
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var rvLogs: RecyclerView

    private val logAdapter = LogAdapter()

    // Persistent listener references for clean deregistration
    private val stateListener: (MonitoringState) -> Unit = { state ->
        updateStateUI(state)
    }

    private val homeLocationListener: (Location?) -> Unit = { loc ->
        updateHomeLocationUI(loc)
    }

    private val logListener: (List<LogEntry>) -> Unit = { logs ->
        logAdapter.submitList(logs) {
            if (logs.isNotEmpty()) {
                rvLogs.scrollToPosition(0)
            }
        }
    }

    // Permission launchers
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            LogManager.log("PERMISSION", LogStatus.SUCCESS, "POST_NOTIFICATIONS granted")
        } else {
            LogManager.log("PERMISSION", LogStatus.INFO, "POST_NOTIFICATIONS denied by user")
        }
        checkAndRequestBackgroundLocation()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            LogManager.log("PERMISSION", LogStatus.SUCCESS, "ACCESS_BACKGROUND_LOCATION granted")
        } else {
            LogManager.log(
                "PERMISSION",
                LogStatus.INFO,
                "ACCESS_BACKGROUND_LOCATION denied. Geofence triggers may be delayed when backgrounded."
            )
        }
    }

    private val foregroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            LogManager.log("PERMISSION", LogStatus.SUCCESS, "Foreground location permission granted")
            checkAndRequestNotificationPermission()
        } else {
            LogManager.log("PERMISSION", LogStatus.FAILED, "Foreground location permissions denied")
            Toast.makeText(this, "Location permission is required to detect HOME", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        setupRecyclerView()
        observeData()

        // Step 1: Startup permission sequence
        checkAndRequestForegroundLocation()
    }

    private fun initViews() {
        tvHomeLocation = findViewById(R.id.tvHomeLocation)
        btnFetchLocation = findViewById(R.id.btnFetchLocation)
        tvRadiusLabel = findViewById(R.id.tvRadiusLabel)
        seekBarRadius = findViewById(R.id.seekBarRadius)
        tvStatus = findViewById(R.id.tvStatus)
        btnMonitor = findViewById(R.id.btnMonitor)
        btnMonitor10s = findViewById(R.id.btnMonitor10s)
        btnStopMonitoring = findViewById(R.id.btnStopMonitoring)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnTestSequence = findViewById(R.id.btnTestSequence)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        rvLogs = findViewById(R.id.rvLogs)

        // Initialize radius from persisted setting
        val savedRadius = MonitoringManager.getSavedRadius()
        seekBarRadius.progress = savedRadius
        tvRadiusLabel.text = getString(R.string.radius_label, savedRadius)
    }

    private fun setupListeners() {
        btnFetchLocation.setOnClickListener {
            btnFetchLocation.isEnabled = false
            MonitoringManager.fetchCurrentLocation(this) { success, location, details ->
                btnFetchLocation.isEnabled = true
                if (!success) {
                    Toast.makeText(this, details, Toast.LENGTH_SHORT).show()
                }
            }
        }

        seekBarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progress.coerceIn(0, 100)
                tvRadiusLabel.text = getString(R.string.radius_label, radius)
                if (fromUser) {
                    MonitoringManager.setRadius(this@MainActivity, radius)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnMonitor.setOnClickListener {
            MonitoringManager.startMonitoringImmediately(this)
        }

        btnMonitor10s.setOnClickListener {
            MonitoringManager.startMonitoringIn10Seconds(this)
        }

        btnStopMonitoring.setOnClickListener {
            MonitoringManager.stopMonitoring(this)
        }

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        btnTestSequence.setOnClickListener {
            LogManager.log("TEST_SEQUENCE", LogStatus.INFO, "Manually initiated test action sequence")
            ActionSequenceService.startActionSequence(this)
        }

        btnClearLogs.setOnClickListener {
            LogManager.clearLogs()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityUI()
    }

    private fun updateAccessibilityUI() {
        val isServiceRunning = HotspotAccessibilityService.isRunning()
        if (isServiceRunning) {
            tvAccessibilityStatus.text = getString(R.string.accessibility_status_enabled)
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring))
            btnOpenAccessibility.text = getString(R.string.accessibility_section_title)
        } else {
            tvAccessibilityStatus.text = getString(R.string.accessibility_status_disabled)
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            btnOpenAccessibility.text = getString(R.string.btn_enable_accessibility)
        }
    }

    private fun setupRecyclerView() {
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = logAdapter
    }

    private fun observeData() {
        MonitoringManager.addHomeLocationListener(homeLocationListener)
        MonitoringManager.addStateListener(stateListener)
        LogManager.addListener(logListener)
    }

    private fun updateHomeLocationUI(loc: Location?) {
        if (loc != null) {
            val formatted = String.format("%.6f, %.6f (±%.1fm)", loc.latitude, loc.longitude, loc.accuracy)
            tvHomeLocation.text = getString(R.string.home_location_label, formatted)
        } else {
            tvHomeLocation.text = getString(R.string.home_location_label, getString(R.string.location_not_set))
        }
        // Update button enabled states when HOME status changes
        updateStateUI(MonitoringManager.getCurrentState())
    }

    private fun updateStateUI(state: MonitoringState) {
        tvStatus.text = state.name
        val colorRes = when (state) {
            MonitoringState.NOT_MONITORING -> R.color.status_not_monitoring
            MonitoringState.STARTING -> R.color.status_starting
            MonitoringState.MONITORING -> R.color.status_monitoring
            MonitoringState.STOPPING -> R.color.status_stopping
            MonitoringState.ERROR -> R.color.status_error
        }
        tvStatus.background.setTint(ContextCompat.getColor(this, colorRes))

        // Update button states
        val isHomeSet = MonitoringManager.isHomeConfigured()
        btnMonitor.isEnabled = isHomeSet && state != MonitoringState.MONITORING && state != MonitoringState.STARTING
        btnMonitor10s.isEnabled = isHomeSet && state != MonitoringState.MONITORING && state != MonitoringState.STARTING
        btnStopMonitoring.isEnabled = state == MonitoringState.MONITORING || state == MonitoringState.STARTING
    }

    // --- Permissions ---

    private fun checkAndRequestForegroundLocation() {
        val finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarsePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (finePermission != PackageManager.PERMISSION_GRANTED || coarsePermission != PackageManager.PERMISSION_GRANTED) {
            foregroundLocationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            checkAndRequestNotificationPermission()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (notificationPermission != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkAndRequestBackgroundLocation()
            }
        } else {
            checkAndRequestBackgroundLocation()
        }
    }

    private fun checkAndRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            if (bgPermission != PackageManager.PERMISSION_GRANTED) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MonitoringManager.removeHomeLocationListener(homeLocationListener)
        MonitoringManager.removeStateListener(stateListener)
        LogManager.removeListener(logListener)
    }
}
