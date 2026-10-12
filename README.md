# Bubble Light

iMessage on the Light Phone III, through your own BlueBubbles server.

Bubble Light is a tool built with the [Light SDK](https://github.com/lightphone/light-sdk). It reads and sends
iMessages by talking to a [BlueBubbles](https://bluebubbles.app) server running on a Mac you own. No accounts,
no analytics, no third-party servers: the phone talks only to your server.

| Chats | Thread | Write |
|---|---|---|
| ![Chats](screenshots/chats.png) | ![Thread](screenshots/thread.png) | ![Write](screenshots/compose.png) |

| Group | New message | Settings |
|---|---|---|
| ![Group](screenshots/group.png) | ![New message](screenshots/new-message.png) | ![Settings](screenshots/settings.png) |

The screenshots use made-up contacts from the fake server in `tools/`, and come from the emulator, which has a
different font from the phone. On a Light Phone III the text is set in Akkurat, like the rest of LightOS.

## What you need

- A Mac that stays on, signed in to Messages, running a BlueBubbles server. Setup is covered in the
  [BlueBubbles docs](https://docs.bluebubbles.app).
- An address the phone can reach that server at. See [Reaching your server](#reaching-your-server).
- The server's setup QR code, or its address and password.

## Setup

1. Open Bubble Light and tap SET UP.
2. Choose **Scan QR code** and point the camera at the setup QR code your BlueBubbles server shows. Or choose
   **Type address and password** and enter them with the Light keyboard.
3. That is all. The address and password are stored on the phone only, and the password is encrypted with a key
   held in the Android Keystore.

Scanning reads the password and the server address from the code and ignores everything else in it.

## Reaching your server

The tool only needs a web address it can open, and nothing has to be installed on the phone besides the tool.

- **A public HTTPS address** is the easiest for most people. BlueBubbles can set up a tunnel for you (a Cloudflare
  or ngrok tunnel, among others, see its docs), or you can use dynamic DNS with a forwarded port. Tailscale
  Funnel also publishes the Mac's server at a public address.
- **Your home Wi-Fi** works with the server's local address, for example `192.168.1.20:1234`. An address on the
  home network defaults to plain `http`, since such a server has no certificate.
- **A private network such as a Tailscale tailnet** works too, if you would rather not expose the server. The
  phone then needs that network's app running.

Anyone who finds a public address can try the password, so use a long one and HTTPS only.

Some tunnels, such as a free Cloudflare quick tunnel, give the server a new address every time it restarts. The
official BlueBubbles apps learn the new address through Firebase, which this tool cannot. When that happens,
open Settings, choose **Change server** and scan the QR code again, or pick an address that stays the same.

**Resync** in Settings throws away the copy of your chats kept on the phone and downloads it again from the
same server. Your sign-in stays. Use it if what the phone shows ever looks different from what the server holds.
A progress bar with a percentage shows how far the download has got, and Settings says whether it finished. The
first sync after signing in shows the same bar at the top of the chat list.

**Change server** checks the new details first, so a wrong one changes nothing. If the server reports the same
Apple account as before, your chats stay as they are. If it reports a different account, or none, the chats
cached on the phone are cleared and downloaded again from the new server.

## What works

- Conversation list of every chat the server has, with unread dots and contact names taken from the Mac's
  address book. Chats that were already quiet when you first connected start out as read.
- Search finds chats by name, by anyone in them, or by number, and finds messages by their text, with a few words
  of the message around the match. Messages on the phone appear at once; the server's own search adds older ones
  that were never downloaded.
- Chats from numbers and addresses that are not in the Mac's address book (verification codes, delivery notices,
  strangers) wait behind a last row, **Unknown senders**, so the main list is the people you know. Nothing is hidden
  or deleted, and search still finds them.
- Pin a chat with the star at the top of its thread. Pinned chats stay at the top of the list.
- Tap the title of a thread for its options: pin, and **Archive**. An archived chat leaves the list and waits behind
  an **Archived** row until something new arrives in it, which brings it back. Pins and archives are kept on the
  phone only.
- **Mark all read** in Settings clears every unread dot on the phone. It sends no read receipts.
- Drafts: what you have typed in a chat's editor is kept, and is there again when you come back to it. Sending
  clears it.
- A message written while the server cannot be reached says **WAITING TO SEND** and goes out by itself when the
  connection returns, in the order it was written. A send that may already have reached the server is not sent
  again by itself, because that could deliver it twice; it says FAILED TO SEND and waits for a tap.
- A phone number in a message gets a "Call" line under it, which hands the number to LightOS's dialer.
- Tap the empty band at the top of the chat list, where the bubble sits, to sync now. It says "Up to date", "2 new
  messages" or "Could not reach the server" for a moment, and sends anything waiting to go out.
- Settings shows the newest message the server has delivered and when it last answered, so a server that has gone
  quiet looks different from a chat that is.
- Play voice messages you receive: tap the play button in the thread. The phone fetches the sound from the server
  the first time. A sound the phone cannot play says so.
- Reading threads, one-to-one and group, with older history loading as you scroll up.
- Laid out like the stock Phone and Messaging screens: the same row sizes, text sizes, stamps and scrollbar, and
  an unnamed group titled by its members' initials, for example "PA, JT". The sizes were measured from a real
  Light Phone III.
- Writing and sending messages, starting new conversations by name, number or email.
- Voice messages: the plus button opens a half-screen sheet, as in the stock app, with **Audio message**. Record,
  listen back, then send or throw it away. With the server's Private API on, it arrives as an iMessage voice
  message; without it, as an ordinary audio file. A failed one can be sent again by tapping it.
- Photos in messages, with a full-screen viewer (pinch to zoom, double tap to reset).
- Tapbacks shown as a line under the message they belong to.
- Delivered and Read status on your newest message.
- Calling a contact: the call button hands the number to LightOS's dialer.
- Live updates while the tool is open.
- Optional read receipts, when the server's Private API is available.
- Setup by scanning the server's QR code, and a Change server option for when its address changes.

## What it does not do, and why

- **No notifications while the tool is closed.** A Light tool cannot run a background service, and the SDK's
  push channel is built for Light's own push service, which a self-hosted BlueBubbles server has no way to
  feed. Messages that arrive while the tool is closed appear as soon as you open it. Live delivery runs only
  while the tool is on screen.
- **No sending photos or files.** The SDK gives a tool no way to take a picture or pick one: CameraX is not
  exposed to tools, the gallery permission is not on Light's allowlist, and `startActivity` and
  `contentResolver` are blocked. Receiving and viewing photos works, and so does sending a voice message,
  since the SDK has an audio recorder. The sheet will gain Camera and Photo if Light adds those APIs.
- **Tapbacks, typing indicators and edits cannot be sent**, and the password is visible while you type it,
  because the Light keyboard editor has no masked mode.

## Privacy

Everything stays between the phone and your server. There is no analytics, no tracking and no advertising.
`tool/lighttool.toml` declares the internet permission, the camera permission, the microphone permission and the
`cleartext-http` capability. The camera is used only to scan the server's setup QR code, and no image is stored
or sent. The microphone is used only while you are recording a voice message, which is sent to your server and
deleted from the phone once it has arrived. The
capability lets the tool reach a server on your home network over plain HTTP. The SDK's own libraries add a few
more permissions to every tool's manifest; this tool does not use them. Use HTTPS whenever the server is
reachable from outside your home network.

## Why BlueBubbles and not OpenBubbles

[OpenBubbles](https://github.com/OpenBubbles/openbubbles-app) is a separate project that implements Apple's
protocol itself, inside a Flutter app with native Rust code. It has no server API for another app to call, and
the Light SDK does not allow bundling native libraries like that. BlueBubbles exposes a documented REST and
Socket.IO interface, which is what this tool uses.

## Building from source

This repository is a fork of [Light's tool SDK](https://github.com/lightphone/light-sdk) at the `v0.2.0` tag.
The tool lives entirely under [`tool/`](tool).

1. Follow the SDK's [Quickstart](https://github.com/lightphone/light-sdk#quickstart) for the GitHub Packages
   token and an emulator.
2. `./gradlew :tool:assembleDebug`

Light's builder compiles only the `tool/` module, against its own pinned copy of the SDK, so write against the
SDK API at the tag the builder uses.

### Trying it without a Mac

`tools/FakeBlueBubbles.java` is a stand-in server with made-up conversations, including a Socket.IO feed.

```
java tools/FakeBlueBubbles.java 8099 fake
```

From the Android emulator the host is `http://10.0.2.2:8099` and the password is `fake`. While it runs:

- `/admin/incoming?password=fake&text=hello&from=%2B15550100001` pushes a new message over the socket.
- `/admin/drop` closes every socket and `/admin/mute` stops the heartbeat, to exercise reconnecting.
- `/admin/account?password=fake&name=someone%40icloud.com` changes the Apple account the server reports, to
  exercise Change server.

To run in the LightOS emulator, set `serverPackage = "com.thelightphone.sdk.emulator"` in
`tool/lighttool.toml` and build with `-DlightSdk.allowAltServerPackage=true`, then set the emulator's client
filter to allow all APKs.

## Credits

The BlueBubbles client code is based on [craigeley/chat](https://github.com/craigeley/chat) (MIT), reworked for
a Room database and the Light SDK. See [NOTICE.md](NOTICE.md). Light, Light Phone and LightOS belong to Light.
This is an unofficial community tool, not affiliated with or endorsed by Light, Apple or BlueBubbles.

## License

MIT, inherited from [Light's SDK](LICENSE).

If Bubble Light is useful to you, [buy me a coffee](https://ko-fi.com/jabberbox) to help keep it going.
