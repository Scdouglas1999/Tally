# Tally on a Samsung TV

This guide puts Tally on a Samsung TV from 2020 or later. You do it once and it takes about 15 minutes. After that,
Tally updates itself from the Jellyfin server.

**You need:**

- The Samsung TV, switched on.
- A computer on the same home network as the TV (the same Wi-Fi or router). Windows 10 or 11, a Mac, or Linux.
- The **address of the Jellyfin server** and **your Jellyfin account**. The person who runs the server gives you both.
  The address looks like `https://jellyfin.example.com` or `192.168.1.10:8096`.

There are four steps:

1. Download and open the installer on the computer.
2. Turn on Developer Mode on the TV.
3. Let the installer put Tally on the TV.
4. Sign in on the TV with a code.

## 1. Get the installer

Download the file for your computer from the
[latest Tally release](https://github.com/Scdouglas1999/Tally/releases/latest):

| Computer | File |
|---|---|
| Windows | `Tally-Samsung-Installer-windows.exe` |
| Mac with Apple silicon (M1 and later) | `Tally-Samsung-Installer-macos-arm64` |
| Mac with an Intel processor | `Tally-Samsung-Installer-macos-x64` |
| Linux | `Tally-Samsung-Installer-linux` |

You don't need to install anything else. Open the file:

- **Windows:** double-click it.
  - The first time, Windows may show **"Windows protected your PC"**, because the program is new and not from a large
    company. Click **More info**, then **Run anyway**.
  - A black window opens. This is the installer.
- **Mac:** open **Terminal** and type the lines below. Use `x64` instead of `arm64` on an Intel Mac.

  ```sh
  cd ~/Downloads
  chmod +x Tally-Samsung-Installer-macos-arm64
  xattr -d com.apple.quarantine Tally-Samsung-Installer-macos-arm64
  ./Tally-Samsung-Installer-macos-arm64
  ```

- **Linux:** in a terminal, run `chmod +x Tally-Samsung-Installer-linux && ./Tally-Samsung-Installer-linux`.

Near the top, the installer shows a line like this:

`This PC's address (what Developer Mode's "Host PC IP" must be): 192.168.1.20`

Write that address down. The TV needs it in the next step. Leave the installer open.

## 2. Turn on Developer Mode on the TV

Developer Mode lets the TV accept an app from your computer. You only do this once.

1. **Open Apps.** On the remote, press **Home**. In the row at the bottom of the screen, go to **Apps** and press
   the middle button. The Apps screen opens, full of app tiles.
2. **Type 1 2 3 4 5 with the remote**, while you're on the Apps screen. Nothing shows while you type.
   - **Remote with number buttons:** press 1, 2, 3, 4, 5.
   - **Slim remote without numbers** (the Samsung Smart Remote): press the **123** button, or the button with
     colored dots, to bring up numbers on the screen. Then select 1, 2, 3, 4 and 5 one after another.
   - **If no window appears:** some TVs need this in **App Settings** instead. Open the gear icon on the Apps screen
     and type 1 2 3 4 5 there.
3. **Fill in the Developer Mode window.**
   - Set **Developer mode** to **On**.
   - Select **Host PC IP** and type the address from step 1, such as `192.168.1.20`. Use the remote's numbers, or
     the on-screen keyboard for the dots.
   - Select **OK**.
4. **Restart the TV fully.** Pressing the power button once isn't enough, because the TV only goes to standby. Do one
   of these:
   - Hold the remote's **power button** for about 5 seconds, until the TV turns off and back on and shows the Samsung
     logo.
   - Unplug the TV, wait 30 seconds, and plug it in again.
5. **Check it worked.** Open Apps again. It now says **Developer Mode** at the top of the screen.

Developer Mode can stay on. A large TV software update sometimes turns it off, and then Tally has to be installed
again with this guide.

## 3. Let the installer put Tally on the TV

Go back to the installer on the computer. It walks you through four steps. Most of the time you only press
**Enter** and type the server address.

1. **Find your TV.** The installer looks for the TV, then lists it with a status:
   - **"Developer Mode is on, ready":** press **Enter**.
   - **"Developer Mode is off"** or **"for another computer":** go back to step 2. Check the Host PC IP, restart the
     TV fully, then press **Enter** to look again.
   - **No TV found:** you can type the TV's IP address instead. On the TV, it's under **Settings > General > Network
     > Network Status > IP Settings**.
2. **Your TV.** The installer connects and shows the TV's Tizen version and model year.
3. **Your Jellyfin server.** Type the server address you were given and press **Enter**. The installer checks that
   the server answers.
4. **Install.** The installer prepares Tally for your TV, copies it over and starts it. It says **"Tally is
   installed"** when it's done.

**TVs from 2023 and later** need a Samsung certificate made for that exact TV. The installer shows two choices:

- **Type 1** to sign in with a Samsung account. It's free, and you can create one on the sign-in page. Your browser
  opens Samsung's sign-in page. Sign in, then come back to the installer. You only do this once per TV, about once a
  year.
- **Type 2** if the server's owner made a `Tally.wgt` file for your TV. The installer shows your TV's **DUID**, a
  code like `XTCJYJZXZBZVK`. Send them that code. When you have the file, run the installer again, type 2, and drag
  the file into the window.

## 4. Sign in on the TV

Tally opens on the TV with a **6-digit code**. To use it:

1. On your phone or a computer, open Jellyfin (the same server) and sign in.
2. Open your profile (the picture or initial at the top), then **Quick Connect**.
3. Type the code from the TV and press **Authorize**. The TV signs in by itself.

If Quick Connect isn't turned on for the server, choose **Use username/password** on the TV instead.

From now on, Tally is in the TV's **Apps** list. To keep it on the Home row, highlight it in Apps, press and hold the
middle button and choose **Add to Home**.

## Later

- **Updates come from the server.** When the server's Tally plugin is updated, Tally on the TV updates the next time
  it starts. Run the installer again only if Tally on the TV asks you to reinstall it, or if a TV update removed it.
- **Keep the installer's folder.** The installer keeps what it signed Tally with in a folder on the computer:
  - Windows: `%APPDATA%\Tally\Samsung`
  - Linux: `~/.config/Tally/Samsung`
  - Mac: the folder shown at the end of the installer
  A later reinstall has to come from the same computer, or from a copy of that folder. Otherwise the installer has
  to remove Tally from the TV first. It asks before it does, and you would sign in on the TV again.
- The installer writes what it did to `Tally-Samsung-Installer.log` in the computer's temp folder. Send that file
  when asking for help.

## If something goes wrong

| The installer says | What to do |
|---|---|
| "Your TV is not in Developer Mode, or the IP in Developer Mode is not this PC's" | Do step 2 again. Check that Host PC IP is exactly the address the installer shows, and restart the TV fully (hold the power button). |
| "Nothing answered at …" | The TV is off, asleep or on another network, or the address is wrong. Turn the TV on, check it's on the same Wi-Fi, and press Enter to try again. |
| "No Jellyfin server answered" | Check the address with the server's owner. Try it in the computer's browser, where it should open Jellyfin. |
| "This server does not have Tally's TV app yet" | Ask the server's owner to install or update the Tally plugin. Tally installs anyway. |
| "Tally is already on this TV, installed from another computer" | Type `y` to replace it. You'll sign in on the TV again. |
| "The TV's clock is behind" | Set the TV's date and time: **Settings > General > System Manager > Time**. Then try again. |
| "This Tally.wgt was made for another TV" | The file's maker needs your TV's DUID, which the installer shows. |
| "Your Samsung account needs an email address" | Add an email address at [account.samsung.com](https://account.samsung.com), then run the installer again. |

## For the server's owner: making a Tally.wgt for someone else's TV

A 2023 or later TV needs a Samsung certificate that lists the TV's DUID. If the TV's owner doesn't want to sign in to
Samsung, you can make the file with your own Samsung account:

```sh
Tally-Samsung-Installer-linux --make-wgt <their DUID> --server https://jellyfin.example.com --out Tally.wgt
```

It opens the Samsung sign-in once, then writes `Tally.wgt`, which works only on that TV. Send them the file. They run
the installer, choose 2 and give it the file. It also works as `--wgt Tally.wgt`, or by dragging the file onto the
installer on Windows. Samsung's certificates last about a year, so after that, make a new file.

Run the installer with `--help` for its other options.
