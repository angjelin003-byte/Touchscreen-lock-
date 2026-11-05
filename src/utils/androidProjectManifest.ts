export interface AndroidRepoFile {
  path: string;
  category: 'gradle' | 'kotlin' | 'manifest-res' | 'ci-docs';
  language: string;
  summary: string;
  content: string;
}

export const ANDROID_REPO_FILES: AndroidRepoFile[] = [
  {
    path: 'app/src/main/java/com/touchlock/edge/TouchLockService.kt',
    category: 'kotlin',
    language: 'kotlin',
    summary:
      'Foreground service managing 4 completely transparent touch-blocker panels (no red border or popup) that block touchscreen fingers while opening a pass-through aperture under a wired USB-C mouse cursor, plus the edge toggle button.',
    content: `package com.touchlock.edge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class TouchLockService : Service() {

    private data class BlockerPanel(
        val view: View,
        val params: WindowManager.LayoutParams
    )

    private lateinit var windowManager: WindowManager
    private lateinit var controller: TouchLockController

    private val blockerPanels = mutableListOf<BlockerPanel>()

    private var edgeButtonView: LinearLayout? = null
    private var edgeIconView: ImageView? = null
    private var edgeStatusDot: View? = null
    private var edgeParams: WindowManager.LayoutParams? = null

    private var mouseCursorX: Int? = null
    private var mouseCursorY: Int? = null
    private var lastMouseLayoutUpdateMs: Long = 0L

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBlockerPanelListeners(panel: BlockerPanel) {
        panel.view.setOnHoverListener { _, event ->
            if (controller.isTouchLocked && isUsbMouseEvent(event)) {
                val mx = (panel.params.x + event.x).toInt()
                val my = (panel.params.y + event.y).toInt()
                updateMouseAperture(mx, my, force = false)
            }
            false
        }

        panel.view.setOnGenericMotionListener { _, event ->
            if (controller.isTouchLocked && isUsbMouseEvent(event)) {
                val mx = (panel.params.x + event.x).toInt()
                val my = (panel.params.y + event.y).toInt()
                updateMouseAperture(mx, my, force = true)
            }
            false
        }

        panel.view.setOnTouchListener { _, event ->
            if (!controller.isTouchLocked) {
                return@setOnTouchListener false
            }

            if (isUsbMouseEvent(event)) {
                val mx = (panel.params.x + event.x).toInt()
                val my = (panel.params.y + event.y).toInt()
                updateMouseAperture(mx, my, force = true)
                return@setOnTouchListener false
            }

            // Finger touch: close any open mouse aperture and consume the touch event silently
            if (mouseCursorX != null || mouseCursorY != null) {
                mouseCursorX = null
                mouseCursorY = null
                applyBlockerPanelsLayout()
            }
            true
        }
    }

    private fun isUsbMouseEvent(event: MotionEvent): Boolean {
        val source = event.source
        if ((source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        ) {
            return true
        }
        for (i in 0 until event.pointerCount) {
            val toolType = event.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                return true
            }
        }
        return false
    }
}`
  },
  {
    path: 'app/src/main/java/com/touchlock/edge/TouchLockController.kt',
    category: 'kotlin',
    language: 'kotlin',
    summary:
      'Pure Kotlin state machine handling single/double-tap unlock rules, edge button coordinates, and 4-panel USB-C mouse pass-through geometry.',
    content: `package com.touchlock.edge

class TouchLockController(
    initialLocked: Boolean = false,
    var requireDoubleTapToUnlock: Boolean = false,
    var dockedLeftEdge: Boolean = false,
    var yOffsetPx: Int = 420
) {
    enum class ToggleOutcome {
        LOCKED,
        UNLOCKED,
        AWAITING_SECOND_TAP
    }

    data class PanelRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    ) {
        val isVisible: Boolean
            get() = width > 0 && height > 0
    }

    data class BlockerOverlayLayout(
        val top: PanelRect,
        val bottom: PanelRect,
        val left: PanelRect,
        val right: PanelRect
    )

    var isTouchLocked: Boolean = initialLocked
        private set

    private var lastUnlockAttemptMs: Long = 0L

    fun computeBlockerLayout(
        screenWidthPx: Int,
        screenHeightPx: Int,
        mouseX: Int?,
        mouseY: Int?,
        apertureRadiusPx: Int = DEFAULT_MOUSE_APERTURE_RADIUS_PX
    ): BlockerOverlayLayout {
        val w = screenWidthPx.coerceAtLeast(1)
        val h = screenHeightPx.coerceAtLeast(1)

        if (mouseX == null || mouseY == null || apertureRadiusPx <= 0) {
            return BlockerOverlayLayout(
                top = PanelRect(0, 0, w, h),
                bottom = PanelRect(0, 0, 0, 0),
                left = PanelRect(0, 0, 0, 0),
                right = PanelRect(0, 0, 0, 0)
            )
        }

        val mx = mouseX.coerceIn(0, w)
        val my = mouseY.coerceIn(0, h)

        val holeLeft = (mx - apertureRadiusPx).coerceIn(0, w)
        val holeRight = (mx + apertureRadiusPx).coerceIn(0, w)
        val holeTop = (my - apertureRadiusPx).coerceIn(0, h)
        val holeBottom = (my + apertureRadiusPx).coerceIn(0, h)
        val middleHeight = (holeBottom - holeTop).coerceAtLeast(0)

        return BlockerOverlayLayout(
            top = PanelRect(x = 0, y = 0, width = w, height = holeTop),
            bottom = PanelRect(x = 0, y = holeBottom, width = w, height = (h - holeBottom).coerceAtLeast(0)),
            left = PanelRect(x = 0, y = holeTop, width = holeLeft, height = middleHeight),
            right = PanelRect(x = holeRight, y = holeTop, width = (w - holeRight).coerceAtLeast(0), height = middleHeight)
        )
    }

    companion object {
        const val DOUBLE_TAP_WINDOW_MS = 650L
        const val DEFAULT_MOUSE_APERTURE_RADIUS_PX = 10
    }
}`
  },
  {
    path: 'app/src/main/java/com/touchlock/edge/MainActivity.kt',
    category: 'kotlin',
    language: 'kotlin',
    summary:
      'Launcher activity that requests SYSTEM_ALERT_WINDOW permission, starts/stops TouchLockService, and configures left/right edge side and double-tap unlock.',
    content: `package com.touchlock.edge

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
}`
  },
  {
    path: 'app/src/main/AndroidManifest.xml',
    category: 'manifest-res',
    language: 'xml',
    summary:
      'Declares SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, MainActivity, and TouchLockService.',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.TouchLockEdge"
        tools:targetApi="35">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".TouchLockService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Provides an edge-docked toggle button and full-screen touch blocker overlay to lock screen touch input on demand." />
        </service>

    </application>
</manifest>`
  },
  {
    path: 'app/build.gradle.kts',
    category: 'gradle',
    language: 'kotlin',
    summary:
      'Module-level Gradle build script configuring namespace com.touchlock.edge, SDK 26..35, Java 17, and ViewBinding.',
    content: `plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.touchlock.edge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.touchlock.edge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}`
  },
  {
    path: 'build.gradle.kts',
    category: 'gradle',
    language: 'kotlin',
    summary:
      'Root Gradle configuration applying Android Gradle Plugin 8.7.2 and Kotlin Android 1.9.24.',
    content: `plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}`
  },
  {
    path: 'settings.gradle.kts',
    category: 'gradle',
    language: 'kotlin',
    summary: 'Configures Google and Maven Central plugin/dependency repositories and includes :app.',
    content: `pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TouchLockEdge"
include(":app")`
  },
  {
    path: 'gradle/wrapper/gradle-wrapper.properties',
    category: 'gradle',
    language: 'properties',
    summary: 'Pins Gradle Wrapper to Gradle 8.9 binary distribution for reproducible ./gradlew builds.',
    content: `distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists`
  },
  {
    path: '.github/workflows/build.yml',
    category: 'ci-docs',
    language: 'yaml',
    summary:
      'GitHub Actions workflow that runs ./gradlew testDebugUnitTest and ./gradlew assembleDebug on JDK 17 and uploads the APK artifact.',
    content: `name: Android Gradle Build

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: "17"
          distribution: "temurin"
          cache: gradle

      - name: Ensure gradlew is executable
        run: chmod +x ./gradlew

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest

      - name: Build Debug APK with gradlew
        run: ./gradlew assembleDebug

      - name: Upload Debug APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: TouchLockEdge-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk`
  }
];

export function filterRepoFiles(
  files: AndroidRepoFile[],
  category: 'all' | AndroidRepoFile['category'],
  searchQuery: string
): AndroidRepoFile[] {
  const normalized = searchQuery.trim().toLowerCase();
  return files.filter((file) => {
    const matchesCategory = category === 'all' || file.category === category;
    const matchesQuery =
      normalized.length === 0 ||
      file.path.toLowerCase().includes(normalized) ||
      file.summary.toLowerCase().includes(normalized);
    return matchesCategory && matchesQuery;
  });
}
