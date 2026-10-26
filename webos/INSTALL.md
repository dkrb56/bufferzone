# Buffer Zone for LG TVs (webOS) — how to install

You need:
- An LG TV with webOS 5 or newer (2020 models and later).
- The file `com.dkrb56.bufferzone_0.1.0_all.ipk`.
- A Windows, Mac or Linux computer on the same home network as the TV.
- A Plex account that a friend shared their server with.

LG does not let you install apps from a file directly. The app is not in the LG Content Store. Use one of the two ways below.

## Way A — Developer Mode (any TV, no hacking)

### 1. Turn on Developer Mode on the TV
1. On your computer, create a free account at https://webostv.developer.lge.com (Sign in → Create account).
2. On the TV, open the **LG Content Store**, search for **Developer Mode**, and install it.
3. Open **Developer Mode** on the TV and sign in with the account from step 1.
4. Turn **Dev Mode Status** ON. The TV restarts.
5. Open **Developer Mode** again and turn **Key Server** ON.
6. Write down the **IP address** and the **passphrase** that the app shows.

### 2. Install the app — the easy way: webOS Dev Manager
1. Download **webOS Dev Manager** from https://github.com/webosbrew/dev-manager-desktop/releases and install it.
2. Click **Add device** and type the TV's IP address and passphrase.
3. Open the **Apps** tab, click **Install**, and select the `.ipk` file.

### 2. Install the app — the command-line way
Install Node.js from https://nodejs.org, then run these commands (use your TV's IP address):

```
npm install -g @webos-tools/cli
ares-setup-device --add tv --info "{'host':'192.168.1.50','port':'9922','username':'prisoner'}"
ares-novacom --device tv --getkey
ares-install --device tv com.dkrb56.bufferzone_0.1.0_all.ipk
```

The third command asks for the passphrase from the TV.

### 3. Keep the app installed
Developer Mode ends after about 50 days (1000 hours). **When it ends, the TV deletes the app.**
- Open **Developer Mode** on the TV and press **Extend** before the time runs out. webOS Dev Manager can also extend it.
- If the app was deleted, extend Developer Mode and install the app again.

## Way B — Rooted TV with Homebrew Channel

If your TV is already rooted, install the `.ipk` with webOS Dev Manager (step 2 above). A rooted TV has no 50-day limit.

## First start

1. Open **Buffer Zone** from the TV's app list.
2. The app shows a 4-character code. On your phone or computer, go to **plex.tv/link**, sign in to Plex and type the code.
3. Choose the server, the libraries and your profile.

Remote control:
- The **arrows** move, **OK** selects and **Back** goes back.
- The Magic Remote pointer also works.
- In the player: **OK** pauses or plays, **left and right** jump back and forward, **up or down** show the controls.
- The **play, pause, rewind and fast-forward** keys work in the player.

## Possible problems

| Problem | What to do |
|---|---|
| The install fails, or the computer cannot find the TV | The TV and the computer must be on the same network. Developer Mode and **Key Server** must be ON. Type the passphrase exactly; it is case-sensitive. |
| The app disappeared after some weeks | Developer Mode ended. Extend it and install the app again (see step 3). |
| The app does not start, or the screen stays empty on an older TV | TVs from 2019 and older (webOS 4.x) are not supported. |
| "The code expired" | The sign-in code lasts about 15 minutes. Select **Get a new code**. |
| "Cannot reach server" | Your friend's server is off, or its remote access is off. Ask your friend to check it. |
| "The server refused the stream" | Your friend's server does not allow conversion for shared users. In the player, open **Quality** and select **Original** (Direct Play). |
| A video does not play directly and shows "Converted" | The TV cannot decode this file (for example, DTS audio is missing on many 2020–2021 LG TVs, or the subtitles are images). The server then converts it. This needs more server power and can take a few seconds to start. |
| Subtitles take some seconds to appear, or look different | Some subtitles (for example image subtitles) must be drawn into the video by the server. The stream then restarts once. |
| Subtitles inside the video file have only a few colours and sizes | The TV draws these subtitles itself, so the video plays without conversion. The TV has 6 colours (white, gray, yellow, red, green, blue); the app picks the one nearest to your colour in **Settings → Subtitle style**. Size below 100% is small. Background and position do not apply. |
| Volume boost restarts the video | On a TV the server makes the audio louder, so a change restarts the stream for a moment. |
| Multi-view shows a black or frozen tile | Many TVs decode only one or two videos at a time. Use fewer tiles. |
| The first screen after you start the app is slow | The app starts its helper service on the first request. This takes a second or two. |
| Something else does not work | This is an early version and was not tested on every TV model. Tell the person who sent you the file: your TV model, the webOS version (Settings → General → About this TV) and what you saw. |
