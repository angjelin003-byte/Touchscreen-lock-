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
      'Foreground service managing two WindowManager overlays: the lower-Z full-screen touchBlockerView (consumes all screen touches when locked) and the higher-Z edgeButtonView docked on the screen edge.',
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class TouchLockService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controller: TouchLockController
    private val mainHandler = Handler(Looper.getMainLooper())

    private var touchBlockerView: FrameLayout? = null
    private var blockerBorderView: View? = null
    private var blockerHintText: TextView? = null
    private var blockerParams: WindowManager.LayoutParams? = null

    private var edgeButtonView: LinearLayout? = null
    private var edgeIconView: ImageView? = null
    private var edgeStatusDot: View? = null
    private var edgeParams: WindowManager.LayoutParams? = null

    private val hideBorderRunnable = Runnable {
        blockerBorderView?.alpha = 0f
        blockerHintText?.alpha = 0f
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        controller = TouchLockController(
            initialLocked = false,
            requireDoubleTapToUnlock = prefs.getBoolean(KEY_DOUBLE_TAP_UNLOCK, false),
            dockedLeftEdge = prefs.getBoolean(KEY_DOCKED_LEFT, false),
            yOffsetPx = prefs.getInt(KEY_Y_OFFSET_PX, dpToPx(220))
        )

        createNotificationChannel()
        startServiceForeground()

        if (Settings.canDrawOverlays(this)) {
            attachOverlays()
            setServiceRunningState(true)
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            setServiceRunningState(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        controller.requireDoubleTapToUnlock = prefs.getBoolean(KEY_DOUBLE_TAP_UNLOCK, false)
        val prefLeft = prefs.getBoolean(KEY_DOCKED_LEFT, controller.dockedLeftEdge)
        if (prefLeft != controller.dockedLeftEdge) {
            controller.dockedLeftEdge = prefLeft
            applyEdgeButtonLayout()
        }

        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> {
                controller.setLockedState(!controller.isTouchLocked)
                syncOverlayState()
            }
            ACTION_LOCK_NOW -> {
                controller.setLockedState(true)
                syncOverlayState()
            }
            ACTION_UNLOCK_NOW -> {
                controller.setLockedState(false)
                syncOverlayState()
            }
            ACTION_STOP_SERVICE -> {
                controller.setLockedState(false)
                setServiceRunningState(false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                syncOverlayState()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideBorderRunnable)
        removeOverlays()
        setServiceRunningState(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun lockedBlockerFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private fun unlockedBlockerFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}`
  },
  {
    path: 'app/src/main/java/com/touchlock/edge/TouchLockController.kt',
    category: 'kotlin',
    language: 'kotlin',
    summary:
      'Pure Kotlin state machine handling single-tap and double-tap unlock rules, vertical edge dragging bounds, and left/right edge snapping.',
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

    var isTouchLocked: Boolean = initialLocked
        private set

    private var lastUnlockAttemptMs: Long = 0L

    fun onEdgeButtonClick(nowMs: Long): ToggleOutcome {
        if (!isTouchLocked) {
            isTouchLocked = true
            lastUnlockAttemptMs = 0L
            return ToggleOutcome.LOCKED
        }

        if (requireDoubleTapToUnlock) {
            val delta = nowMs - lastUnlockAttemptMs
            if (delta in 1..DOUBLE_TAP_WINDOW_MS) {
                isTouchLocked = false
                lastUnlockAttemptMs = 0L
                return ToggleOutcome.UNLOCKED
            } else {
                lastUnlockAttemptMs = nowMs
                return ToggleOutcome.AWAITING_SECOND_TAP
            }
        }

        isTouchLocked = false
        lastUnlockAttemptMs = 0L
        return ToggleOutcome.UNLOCKED
    }

    fun setLockedState(locked: Boolean) {
        isTouchLocked = locked
        lastUnlockAttemptMs = 0L
    }

    fun updateVerticalPosition(rawY: Int, screenHeightPx: Int, buttonHeightPx: Int): Int {
        val minY = 64
        val maxY = (screenHeightPx - buttonHeightPx - 64).coerceAtLeast(minY)
        yOffsetPx = rawY.coerceIn(minY, maxY)
        return yOffsetPx
    }

    fun updateEdgeSide(rawTouchX: Float, screenWidthPx: Int): Boolean {
        if (screenWidthPx > 0) {
            dockedLeftEdge = rawTouchX < (screenWidthPx / 2f)
        }
        return dockedLeftEdge
    }

    companion object {
        const val DOUBLE_TAP_WINDOW_MS = 650L
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
