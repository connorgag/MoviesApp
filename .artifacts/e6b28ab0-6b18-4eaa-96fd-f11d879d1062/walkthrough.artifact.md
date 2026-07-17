# Walkthrough - Tab Icon Updates

I have updated the tab icons in the `TabLayout` to better align with the requested changes.

## Changes Made

### UI Layout

#### [MODIFY] [activity_main.xml](file:///Users/connorgag/AndroidStudioProjects/TailscaleToggle/app/src/main/res/layout/activity_main.xml)
- Updated the icons for the **Watch** and **YouTube** tabs.
- Swapped the positions of the **YouTube** and **Downloads** tabs. The new order is: Discover, Watch, YouTube, Downloads.

### Application Logic

#### [MODIFY] [MainActivity.kt](file:///Users/connorgag/AndroidStudioProjects/TailscaleToggle/app/src/main/java/com/example/tailscaletoggle/MainActivity.kt)
- Updated `handleTabSelection` to match the new tab indices (YouTube at 2, Downloads at 3).
- Updated startup logic to select the Downloads tab (index 3) when offline.
- Updated `handleDownload` and `updateYoutubeDownloadBarVisibility` to use the correct YouTube tab index.

### Resources

#### [NEW] [ic_movie.xml](file:///Users/connorgag/AndroidStudioProjects/TailscaleToggle/app/src/main/res/drawable/ic_movie.xml)
Created a new movie icon using a standard Material Design path.

## Verification Results

### Automated Tests
- Ran `:app:assembleDebug` and the build finished successfully.

### Manual Verification
- Verified that `ic_movie.xml` is valid XML and uses the correct path.
- Verified that `activity_main.xml` correctly references the updated drawables and has the new tab order.
- Verified that `MainActivity.kt` logic correctly handles the swapped tab indices.
