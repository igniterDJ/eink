# Gradle Log Summary

Logs are in chronological order by file timestamp: `gradle_test.log` (08:54) →
`gradle_test2.log` (08:55) → `gradle_test3.log` (08:56) → `gradle_run.log` (09:29).
All predate the Calendar feature (paths in the logs still say
`/home/digvijay-reddy/Desktop/keychain/...`, the project's old directory name,
before it became `eink`).

## gradle_test.log

- **Failed**: `ImageProcessorTest > previewLandscape_roundTrip_preservesDithered`
  (`ArrayComparisonFailure` at `ImageProcessorTest.kt:134`). 7 other tests passed.
- **Also logged**: a Kotlin compiler warning at `ImageProcessorTest.kt:165` — `Java
  type mismatch: inferred type is 'kotlin.String?', but 'kotlin.String' was expected`.
- **Root cause**: not visible beyond "array mismatch" in the assertion; the log
  doesn't show expected/actual values.
- **Status today**: already-fixed / no longer relevant. The current
  `ImageProcessorTest.kt` has no test named `preservesDithered` (it was renamed —
  see below), and line 165 now reads
  `val userDir = System.getProperty("user.dir") ?: throw AssertionError(...)`,
  which is null-safe and would not produce that warning.

## gradle_test2.log

- **Failed**: `ImageProcessorTest > previewLandscape_roundTrip_preservesFrame`
  (`ArrayComparisonFailure` at `ImageProcessorTest.kt:134`). 7 other tests passed.
- **Root cause**: not visible beyond the assertion location; same round-trip test
  as above, now under its current name (`preservesFrame`), still failing here.
- **Status today**: already-fixed. The current codebase's `gradle_run.log` (the
  most recent of the four, 09:29) runs `testDebugUnitTest` to completion with no
  failures reported, and the test still exists today at the same name/line
  (`ImageProcessorTest.kt:121-135`, assertion at line 134). Whatever produced the
  mismatch between `gradle_test2.log` and `gradle_test3.log` was fixed in between.

## gradle_test3.log

- **Failed**: nothing — `BUILD SUCCESSFUL`.
- **Notable non-fatal event**: `compileDebugKotlin` logged
  `e: The daemon has terminated unexpectedly on startup attempt #1 with error
  code: 0` (Kotlin compile daemon), but the build continued and succeeded anyway
  (fell back to in-process/retry compilation). This is Gradle/Kotlin daemon
  tooling flakiness, not a code defect.
- **Status today**: not relevant to current code — it's a one-off local build
  infrastructure hiccup, not a reproducible failure tied to any source file.

## gradle_run.log

- **Failed**: nothing — `BUILD SUCCESSFUL` (full `assembleDebug`, including
  `compileDebugKotlin`, `compileDebugUnitTestKotlin`, and `testDebugUnitTest`).
- **Warnings only**: `compileDebugKotlin` emits deprecation warnings for
  `BleManager.kt` — `BluetoothGattCharacteristic`/`BluetoothGattDescriptor`
  `.value` setter and the no-`ByteArray`-argument `writeCharacteristic`/
  `writeDescriptor` overloads are deprecated in the Android SDK. Warnings only;
  build and tests still pass.
- **Status today**: still relevant but not a failure. Checked current
  `BleManager.kt`: it still calls the deprecated `characteristic.value = payload`
  / `gatt.writeCharacteristic(characteristic)` / `desc.value = ...` /
  `gatt.writeDescriptor(desc)` forms (now at lines ~333-343, inside
  `writeCharacteristicCompat`, evidently a fallback path for older API levels).
  These warnings will keep appearing on every build; they don't fail the build
  and this task was not scoped to touch `BleManager.kt`.

## Bottom line

None of the four logs point to a currently-live bug. The one real test failure
(the `previewLandscape` round-trip assertion) was already fixed by the time of
`gradle_test3.log`/`gradle_run.log`, and today's independently-verified
`./gradlew compileDebugKotlin` / `./gradlew testDebugUnitTest` runs (per this
task's brief) both succeed. The only standing item is the `BleManager.kt`
deprecation warnings, which are cosmetic (non-blocking) and out of this task's
scope.
