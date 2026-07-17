# Implementation Plan - Fix Unreliable Disconnect

The user reported that Tailscale turns off and then immediately back on when leaving the app. This is caused by `MainActivity`'s background polling loop which automatically reconnects Tailscale even when the app is in the background.

## User Review Required

> [!IMPORTANT]
> I will ensure that the automatic connection logic only runs when the app is in the foreground. This will allow the `TailscaleApp` lifecycle observer to successfully disconnect the VPN when you leave the app without `MainActivity` immediately turning it back on.

## Proposed Changes

### [MainActivity](file:///Users/connorgag/AndroidStudioProjects/TailscaleToggle/app/src/main/java/com/example/tailscaletoggle/MainActivity.kt)

#### [MODIFY] [MainActivity.kt](file:///Users/connorgag/AndroidStudioProjects/TailscaleToggle/app/src/main/java/com/example/tailscaletoggle/MainActivity.kt)

-   **Track Activity Lifecycle**: Introduce an `isActivityVisible` flag.
-   **Manage Polling**:
    -   Set `isActivityVisible = true` and call `updateUIState()` in `onStart`.
    -   Set `isActivityVisible = false` and remove all `uiUpdateHandler` callbacks in `onStop`.
-   **Guard Auto-Connect**: In `updateUIState()`, only call `TailscaleController.connect(this)` and `checkServerReachability()` if `isActivityVisible` is true.
-   **Clean up `onCreate`**: Remove the initial `updateUIState()` call from `onCreate` since it will now be handled by `onStart`.

## Verification Plan

### Manual Verification
1.  Open the app. Verify Tailscale connects.
2.  Exit the app (Home button).
3.  Check Tailscale app or system VPN status. Verify it stays disconnected.
4.  Re-enter the app. Verify it automatically reconnects.
5.  Check logs to ensure `updateUIState` is not looping in the background.
