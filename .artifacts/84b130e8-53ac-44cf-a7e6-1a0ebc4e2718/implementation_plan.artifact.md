# Implementation Plan - Persistent YouTube Download Bar

Ensure the YouTube download bar remains visible during an active download, even if the user navigates away from the video.

## Proposed Changes

### [Component Name] app

#### [MODIFY] [MainActivity.kt](file:///Users/connorgag/AndroidStudioProjects/TailscaleToggle/app/src/main/java/com/example/tailscaletoggle/MainActivity.kt)
- Add `private var isDownloadingYoutube = false` property.
- Update `downloadYoutubeVideo()`:
    - Set `isDownloadingYoutube = true` at the start.
    - Set `isDownloadingYoutube = false` once the request completes (and the success/fail message is shown).
    - Call `updateYoutubeDownloadBarVisibility()` whenever `isDownloadingYoutube` changes.
- Update `updateYoutubeDownloadBarVisibility()`:
    - Change logic to: `val shouldShow = isYoutubeTab && (isWatchingVideo || isDownloadingYoutube)`.
    - This ensures the bar stays visible if a download is in progress, regardless of the current URL.
- Ensure the UI state (button disabled, spinner visible) is maintained if the user navigates back to the video while a download is still active (though `updateYoutubeDownloadBarVisibility` only controls visibility of the *bar*, the views inside it should retain their state).

## Verification Plan

### Manual Verification
1.  **Start Download**: Go to a YouTube video and click "Download Video".
2.  **Navigate Away**: While the spinner is active, click the YouTube home icon or search for something else.
3.  **Check Persistence**: Verify the download bar (with the spinner) remains visible at the top.
4.  **Completion**: Verify the bar stays visible until the "Successful" message appears and then hides (or reverts to video-only visibility) after the 7-second delay.
5.  **Navigate Back**: If you navigate back to a video while a download is active, the bar should still show the progress/status correctly.
