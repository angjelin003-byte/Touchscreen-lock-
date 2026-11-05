package com.touchlock.edge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.touchlock.edge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshUiState()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshUiState()
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startOverlayServiceInternal()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(TouchLockService.PREFS_NAME, Context.MODE_PRIVATE)

        binding.btnGrantOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnOpenAppInfo.setOnClickListener {
            openAppDetailsSettings()
        }

        binding.btnToggleOverlayService.setOnClickListener {
            val isRunning = prefs.getBoolean(TouchLockService.KEY_SERVICE_RUNNING, false)
            if (isRunning) {
                stopOverlayService()
            } else {
                startOverlayService()
            }
        }

        binding.switchDoubleTapUnlock.isChecked =
            prefs.getBoolean(TouchLockService.KEY_DOUBLE_TAP_UNLOCK, false)
        binding.switchDoubleTapUnlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(TouchLockService.KEY_DOUBLE_TAP_UNLOCK, isChecked).apply()
            notifyServiceConfigUpdated()
        }

        binding.radioEdgeLeft.isChecked = prefs.getBoolean(TouchLockService.KEY_DOCKED_LEFT, false)
        binding.radioEdgeRight.isChecked = !prefs.getBoolean(TouchLockService.KEY_DOCKED_LEFT, false)

        binding.radioGroupEdgeSide.setOnCheckedChangeListener { _, checkedId ->
            val dockLeft = checkedId == R.id.radioEdgeLeft
            prefs.edit().putBoolean(TouchLockService.KEY_DOCKED_LEFT, dockLeft).apply()
            notifyServiceConfigUpdated()
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshUiState()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startOverlayServiceInternal()
    }

    private fun startOverlayServiceInternal() {
        val serviceIntent = Intent(this, TouchLockService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        refreshUiState()
    }

    private fun stopOverlayService() {
        val stopIntent = Intent(this, TouchLockService::class.java).apply {
            action = TouchLockService.ACTION_STOP_SERVICE
        }
        startService(stopIntent)
        refreshUiState()
    }

    private fun notifyServiceConfigUpdated() {
        val isRunning = prefs.getBoolean(TouchLockService.KEY_SERVICE_RUNNING, false)
        if (isRunning && Settings.canDrawOverlays(this)) {
            val updateIntent = Intent(this, TouchLockService::class.java)
            ContextCompat.startForegroundService(this, updateIntent)
        }
    }

    private fun refreshUiState() {
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val isServiceRunning = prefs.getBoolean(TouchLockService.KEY_SERVICE_RUNNING, false) &&
            hasOverlayPermission
        val isTouchLocked = prefs.getBoolean(TouchLockService.KEY_IS_LOCKED, false) &&
            isServiceRunning

        binding.tvPermissionStatus.text = if (hasOverlayPermission) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_missing)
        }

        binding.btnGrantOverlayPermission.isEnabled = !hasOverlayPermission
        binding.btnGrantOverlayPermission.text = if (hasOverlayPermission) {
            getString(R.string.btn_permission_granted)
        } else {
            getString(R.string.btn_grant_permission)
        }

        binding.tvServiceStatus.text = when {
            !isServiceRunning -> getString(R.string.service_status_stopped)
            isTouchLocked -> getString(R.string.service_status_locked)
            else -> getString(R.string.service_status_active_unlocked)
        }

        binding.btnToggleOverlayService.text = if (isServiceRunning) {
            getString(R.string.btn_stop_edge_overlay)
        } else {
            getString(R.string.btn_start_edge_overlay)
        }
    }
}
