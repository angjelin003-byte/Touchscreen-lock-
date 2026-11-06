package com.touchlock.edge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.touchlock.edge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TouchLockService.KEY_SERVICE_RUNNING || key == TouchLockService.KEY_IS_LOCKED) {
            refreshUiState()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshUiState()
        if (Settings.canDrawOverlays(this)) {
            startOverlayServiceInternal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(TouchLockService.PREFS_NAME, Context.MODE_PRIVATE)

        // Dismiss floating iOS sheet when tapping outside or tapping "Done"
        binding.rootScrim.setOnClickListener {
            finish()
        }
        binding.btnCloseSheet.setOnClickListener {
            finish()
        }

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

        setupEditorControls()
    }

    @SuppressLint("SetTextI18n")
    private fun setupEditorControls() {
        val iconSizeDp = prefs.getInt(
            TouchLockService.KEY_ICON_SIZE_DP,
            TouchLockController.DEFAULT_ICON_SIZE_DP
        ).coerceIn(TouchLockController.MIN_ICON_SIZE_DP, TouchLockController.MAX_ICON_SIZE_DP)

        val opacityPercent = prefs.getInt(
            TouchLockService.KEY_OPACITY_PERCENT,
            TouchLockController.DEFAULT_OPACITY_PERCENT
        ).coerceIn(TouchLockController.MIN_OPACITY_PERCENT, TouchLockController.MAX_OPACITY_PERCENT)

        val cornerRadiusDp = prefs.getInt(
            TouchLockService.KEY_CORNER_RADIUS_DP,
            TouchLockController.DEFAULT_CORNER_RADIUS_DP
        ).coerceIn(TouchLockController.MIN_CORNER_RADIUS_DP, TouchLockController.MAX_CORNER_RADIUS_DP)

        val mouseApertureDp = prefs.getInt(
            TouchLockService.KEY_MOUSE_APERTURE_DP,
            TouchLockController.DEFAULT_MOUSE_APERTURE_DP
        ).coerceIn(TouchLockController.MIN_MOUSE_APERTURE_DP, TouchLockController.MAX_MOUSE_APERTURE_DP)

        val showDot = prefs.getBoolean(
            TouchLockService.KEY_SHOW_STATUS_DOT,
            TouchLockController.DEFAULT_SHOW_STATUS_DOT
        )

        // 1. Lock Icon Size (10dp .. 28dp)
        binding.seekIconSize.max =
            TouchLockController.MAX_ICON_SIZE_DP - TouchLockController.MIN_ICON_SIZE_DP
        binding.seekIconSize.progress = iconSizeDp - TouchLockController.MIN_ICON_SIZE_DP
        binding.tvIconSizeValue.text = "$iconSizeDp dp"
        binding.seekIconSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = TouchLockController.MIN_ICON_SIZE_DP + progress
                binding.tvIconSizeValue.text = "$dp dp"
                if (fromUser) {
                    prefs.edit().putInt(TouchLockService.KEY_ICON_SIZE_DP, dp).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 2. Overlay Opacity / Transparency (15% .. 100%)
        binding.seekOpacity.max =
            TouchLockController.MAX_OPACITY_PERCENT - TouchLockController.MIN_OPACITY_PERCENT
        binding.seekOpacity.progress = opacityPercent - TouchLockController.MIN_OPACITY_PERCENT
        binding.tvOpacityValue.text = "$opacityPercent%"
        binding.seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = TouchLockController.MIN_OPACITY_PERCENT + progress
                binding.tvOpacityValue.text = "$pct%"
                if (fromUser) {
                    prefs.edit().putInt(TouchLockService.KEY_OPACITY_PERCENT, pct).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Corner Radius (4dp .. 24dp)
        binding.seekCornerRadius.max =
            TouchLockController.MAX_CORNER_RADIUS_DP - TouchLockController.MIN_CORNER_RADIUS_DP
        binding.seekCornerRadius.progress = cornerRadiusDp - TouchLockController.MIN_CORNER_RADIUS_DP
        binding.tvCornerRadiusValue.text = "$cornerRadiusDp dp"
        binding.seekCornerRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rDp = TouchLockController.MIN_CORNER_RADIUS_DP + progress
                binding.tvCornerRadiusValue.text = "$rDp dp"
                if (fromUser) {
                    prefs.edit().putInt(TouchLockService.KEY_CORNER_RADIUS_DP, rDp).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4. Show Status Dot
        binding.switchShowStatusDot.isChecked = showDot
        binding.switchShowStatusDot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(TouchLockService.KEY_SHOW_STATUS_DOT, isChecked).apply()
        }

        // 5. USB-C Mouse Pass-Through Zone (24dp .. 80dp)
        binding.seekMouseAperture.max =
            TouchLockController.MAX_MOUSE_APERTURE_DP - TouchLockController.MIN_MOUSE_APERTURE_DP
        binding.seekMouseAperture.progress = mouseApertureDp - TouchLockController.MIN_MOUSE_APERTURE_DP
        binding.tvMouseApertureValue.text = "$mouseApertureDp dp"
        binding.seekMouseAperture.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mDp = TouchLockController.MIN_MOUSE_APERTURE_DP + progress
                binding.tvMouseApertureValue.text = "$mDp dp"
                if (fromUser) {
                    prefs.edit().putInt(TouchLockService.KEY_MOUSE_APERTURE_DP, mDp).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 6. Reset Overlay Appearance to Defaults
        binding.btnResetAppearance.setOnClickListener {
            prefs.edit()
                .putInt(TouchLockService.KEY_ICON_SIZE_DP, TouchLockController.DEFAULT_ICON_SIZE_DP)
                .putInt(TouchLockService.KEY_OPACITY_PERCENT, TouchLockController.DEFAULT_OPACITY_PERCENT)
                .putInt(TouchLockService.KEY_CORNER_RADIUS_DP, TouchLockController.DEFAULT_CORNER_RADIUS_DP)
                .putBoolean(TouchLockService.KEY_SHOW_STATUS_DOT, TouchLockController.DEFAULT_SHOW_STATUS_DOT)
                .putInt(TouchLockService.KEY_MOUSE_APERTURE_DP, TouchLockController.DEFAULT_MOUSE_APERTURE_DP)
                .apply()

            binding.seekIconSize.progress =
                TouchLockController.DEFAULT_ICON_SIZE_DP - TouchLockController.MIN_ICON_SIZE_DP
            binding.seekOpacity.progress =
                TouchLockController.DEFAULT_OPACITY_PERCENT - TouchLockController.MIN_OPACITY_PERCENT
            binding.seekCornerRadius.progress =
                TouchLockController.DEFAULT_CORNER_RADIUS_DP - TouchLockController.MIN_CORNER_RADIUS_DP
            binding.switchShowStatusDot.isChecked = TouchLockController.DEFAULT_SHOW_STATUS_DOT
            binding.seekMouseAperture.progress =
                TouchLockController.DEFAULT_MOUSE_APERTURE_DP - TouchLockController.MIN_MOUSE_APERTURE_DP
        }

        // 7. Double-Tap to Unlock & Edge Side
        binding.switchDoubleTapUnlock.isChecked =
            prefs.getBoolean(TouchLockService.KEY_DOUBLE_TAP_UNLOCK, false)
        binding.switchDoubleTapUnlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(TouchLockService.KEY_DOUBLE_TAP_UNLOCK, isChecked).apply()
        }

        val dockedLeft = prefs.getBoolean(TouchLockService.KEY_DOCKED_LEFT, false)
        binding.radioEdgeLeft.isChecked = dockedLeft
        binding.radioEdgeRight.isChecked = !dockedLeft

        binding.radioGroupEdgeSide.setOnCheckedChangeListener { _, checkedId ->
            val dockLeft = checkedId == R.id.radioEdgeLeft
            prefs.edit().putBoolean(TouchLockService.KEY_DOCKED_LEFT, dockLeft).apply()
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

        binding.btnToggleOverlayService.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (isServiceRunning) "#FF3B30" else "#007AFF")
        )
    }
}
