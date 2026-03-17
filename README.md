Hey everyone! 👋 Welcome to AServer.

What started as a crazy idea—"Can I run a fully functional Minecraft Java server on my Android phone without losing my mind in a terminal?"—turned into this project. AServer is not just a wrapper; it's a fully-fledged, modern control panel built natively for Android.

It uses Termux (PRoot Ubuntu) under the hood to run the heavy lifting (Java, PaperMC, Playit.gg)

🚀 Key Features
One-Click Server Setup: Choose your Minecraft version (from 1.8.8 up to the latest PaperMC), set your RAM, and the app downloads the .jar and prepares the environment automatically.

Live Console & System Monitor: Real-time log reading and RCON integration. Send commands, monitor device RAM usage, CPU load, and your local IP directly from the dashboard.

The "Palace of Justice" (Player Manager): A dedicated tab to manage players. OP, kick, or ban active players, manage the banned-players.json, and toggle your Whitelist with a sleek UI.

Smart File Manager: Navigate your server files, edit .properties or .json files with the built-in text editor, delete corrupted files, or create new folders.

Built-in Plugin Store: Download essential plugins (like GeyserMC, Floodgate, ViaVersion) straight into your plugins folder with one tap.

Time Machine (Backup & Restore): Messed up your world? Hit the backup button to zip your entire server. If things go wrong, use the "Restore" feature to wipe the broken server and extract your backup in seconds.

Automated Tasks (Cron Jobs): Set up recurring commands (like /save-all or automated server announcements) to run in the background.

Foreground Service Armor: The app runs a persistent background service so Android doesn't kill your server when you switch apps.

🛠️ Tech Stack
Language: Kotlin

UI Framework: Jetpack Compose (Material 3)

Backend Engine: Termux (proot-distro Ubuntu, OpenJDK)

Networking: Native Java Sockets (RCON implementation) & Playit.gg for tunneling

⚙️ How it Works
The app acts as the "Brain". It configures the server folder, downloads PaperMC, and generates the server.properties and eula.txt.

It then copies a massive, automated command to your clipboard and launches Termux.

Termux installs Ubuntu, Java, and Playit.gg (if enabled), then boots up the .jar.

You come back to AServer, and it hooks into the server via RCON on port 25575 to give you full control.

⚠️ Disclaimer
Running a Minecraft server requires a decent amount of RAM and CPU. This app is designed for modern Android devices. Remember to keep your device cooled!

Feel free to fork, contribute, or open an issue if you find a bug. Happy hosting! ⛏️

MADE BY BADİADAM
