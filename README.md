# Movies

A minimal Android app that connects Tailscale when it's opened, and
disconnects Tailscale when it's closed/backgrounded.

## How it works

It sends explicit broadcasts to the official Tailscale app's built-in
receiver (the same one automation apps like Tasker use):

- `com.tailscale.ipn.CONNECT_VPN` → tells Tailscale to connect
- `com.tailscale.ipn.DISCONNECT_VPN` → tells Tailscale to disconnect

`TailscaleApp.kt` uses `ProcessLifecycleOwner` to detect when your *whole
app* (not just one screen) enters/leaves the foreground, and fires the
matching broadcast.

## Setup

1. Open this folder in Android Studio (File → Open).
2. Let it sync Gradle (it will offer to upgrade the Android Gradle Plugin /
   Kotlin versions if newer ones are available — that's fine to accept).
3. Make sure the **Tailscale app is already installed and you're already
   logged into your tailnet** on the device/emulator you test with. This app
   only starts/stops an existing session — it doesn't handle login.
4. Run the app. Opening it should connect Tailscale; pressing Home or
   switching apps should disconnect it. The two buttons on screen let you
   trigger connect/disconnect manually to sanity-check the broadcast works
   before relying on the automatic lifecycle behavior.

## Important caveats

- **This toggles the normal, system-wide Tailscale VPN.** While connected,
  *all* apps on the phone route through your tailnet, not just this one —
  that's how Tailscale's Android client works. If you actually want only
  *this app's* traffic tunneled while it's open, and nothing else on the
  phone affected, that needs a different approach: embedding Tailscale
  directly into your app via Google's `tsnet` library through `gomobile`
  bindings (see the open-source
  [tailsocks](https://github.com/bropines/tailsocks) project for a working
  example). That's a much bigger lift — real Go toolchain, `gomobile bind`,
  building an `.aar`, and no dependency on having the Tailscale app
  installed at all.
- **First-ever connect may show a system dialog.** The first time *any*
  broadcast successfully triggers a connect, Android may show its standard
  "Allow this app to set up a VPN connection?" prompt on Tailscale's behalf.
  This is normal and only happens once per Tailscale install.
- **No delivery confirmation.** `sendBroadcast` is fire-and-forget — there's
  currently no public intent to query Tailscale's live connection status
  from another app, so this project can't reliably show "connected" vs.
  "connecting" in the UI. It just assumes the broadcast worked.
- **Battery optimization** on some devices (Samsung, Xiaomi, etc.) can delay
  or kill background broadcasts. If disconnects feel unreliable, exclude
  both apps from battery optimization.
- This is unofficial behavior (not a documented, versioned Tailscale API),
  so a future Tailscale update could change or remove it. It's been stable
  and used by the Tasker community for a while, but keep that in mind for
  anything beyond personal use.
