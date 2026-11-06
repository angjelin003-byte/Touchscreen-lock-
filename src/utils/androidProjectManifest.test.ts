import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { ANDROID_REPO_FILES, filterRepoFiles } from './androidProjectManifest';

test('all required Android Gradle Wrapper and source files exist on disk', () => {
  const requiredPaths = [
    'gradlew',
    'gradlew.bat',
    'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties',
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/touchlock/edge/TouchLockController.kt',
    'app/src/main/java/com/touchlock/edge/TouchLockService.kt',
    'app/src/main/java/com/touchlock/edge/MainActivity.kt',
    'app/src/test/java/com/touchlock/edge/TouchLockControllerTest.kt',
    '.github/workflows/build.yml',
    'public/TouchLockEdge-Android.zip',
  ];

  for (const relPath of requiredPaths) {
    assert.equal(fs.existsSync(relPath), true, `Missing required file: ${relPath}`);
  }

  const gradlewStat = fs.statSync('gradlew');
  assert.equal((gradlewStat.mode & 0o111) !== 0, true, 'gradlew must have executable permissions');

  const jarStat = fs.statSync('gradle/wrapper/gradle-wrapper.jar');
  assert.equal(jarStat.size > 30000, true, 'gradle-wrapper.jar should be a valid non-empty binary JAR');
});

test('AndroidManifest.xml and build.gradle.kts disable data backup, extraction, and telemetry', () => {
  const manifestXml = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');
  assert.equal(manifestXml.includes('android.permission.SYSTEM_ALERT_WINDOW'), true);
  assert.equal(manifestXml.includes('android.permission.FOREGROUND_SERVICE_SPECIAL_USE'), true);
  assert.equal(manifestXml.includes('android.permission.POST_NOTIFICATIONS'), false);
  assert.equal(manifestXml.includes('android:allowBackup="false"'), true);
  assert.equal(manifestXml.includes('androidx.profileinstaller.ProfileInstallReceiver'), true);
  assert.equal(manifestXml.includes('tools:node="remove"'), true);
  assert.equal(manifestXml.includes('.TouchLockService'), true);

  const buildGradle = fs.readFileSync('app/build.gradle.kts', 'utf8');
  assert.equal(buildGradle.includes('includeInApk = false'), true);
  assert.equal(fs.existsSync('app/src/main/res/xml/data_extraction_rules.xml'), true);
});

test('TouchLockService.kt removes red border indicator and supports flicker-free USB-C mouse pass-through and overlay editor', () => {
  const serviceCode = fs.readFileSync(
    'app/src/main/java/com/touchlock/edge/TouchLockService.kt',
    'utf8'
  );
  assert.equal(serviceCode.includes('blockerBorderView'), false);
  assert.equal(serviceCode.includes('flashBlockedTouchFeedback'), false);
  assert.equal(serviceCode.includes('isUsbMouseEvent'), true);
  assert.equal(serviceCode.includes('ACTION_HOVER_EXIT'), true);
  assert.equal(serviceCode.includes('event.rawX'), true);
  assert.equal(serviceCode.includes('panel.params.x + event.x'), false);
  assert.equal(serviceCode.includes('KEY_ICON_SIZE_DP'), true);
  assert.equal(serviceCode.includes('KEY_OPACITY_PERCENT'), true);

  const themesXml = fs.readFileSync('app/src/main/res/values/themes.xml', 'utf8');
  assert.equal(themesXml.includes('android:windowIsTranslucent">true<'), true);
});

test('filterRepoFiles filters by category and search query accurately', () => {
  const kotlinOnly = filterRepoFiles(ANDROID_REPO_FILES, 'kotlin', '');
  assert.equal(kotlinOnly.length, 3);

  const summaryMatches = filterRepoFiles(ANDROID_REPO_FILES, 'all', 'TouchLockService');
  assert.equal(summaryMatches.length, 3);

  const exactFileMatches = filterRepoFiles(ANDROID_REPO_FILES, 'all', 'TouchLockService.kt');
  assert.equal(exactFileMatches.length, 1);
  assert.equal(exactFileMatches[0].path, 'app/src/main/java/com/touchlock/edge/TouchLockService.kt');
});
