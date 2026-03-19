Hey everyone! 👋 Welcome to **AServer**.

What started as a crazy idea—*"Can I run a fully functional Minecraft Java server on my Android phone without losing my mind in a terminal?"*—has evolved into a fully-fledged, robust, and modern node control panel built natively for Android. 

AServer isn't just a basic wrapper; it's a dual-engine architecture capable of running both **Plugin-based (PaperMC)** and **Modded (Fabric)** servers right from your pocket. It uses Termux (PRoot Ubuntu) under the hood to handle the heavy lifting (Java, API routing, Playit.gg tunnels) while providing a sleek, Jetpack Compose-powered dashboard.

## 🚀 Key Features

* **Dual-Engine Setup (PaperMC & Fabric):** Choose your infrastructure. AServer automatically fetches the latest stable builds or constructs the complex 2-stage Fabric Installer environment for you. From classic 1.8.8 PvP to the latest 1.21.x modpacks, it’s all one tap away.
* **Context-Aware Stores (Modrinth & Spiget):** The built-in file manager is smart. Navigate to the `plugins` folder, and the **Spiget Store** appears. Navigate to the `mods` folder, and the **Modrinth Store** takes over.
* **Sniper Version-Matching Algorithm:** Say goodbye to "Incompatible Mod" crashes. The Modrinth engine reads your server's specific `mc_version.txt` and *only* fetches mods explicitly compatible with your server's version and loader.
* **Live Console & Quick Macros:** Real-time log parsing with ANSI color-coding. Use the horizontally scrollable **Macro Bar** to instantly execute lifesaver commands (Set Day, Clear Weather, Kill Mobs) without touching the keyboard.
* **The "Palace of Justice" (Player Manager):** A dedicated UI to manage your player base. OP, kick, or ban active players, manage `banned-players.json`, and toggle your whitelist seamlessly.
* **Smart File Manager & IDE-like Editor:** Navigate files, create directories, and edit `.properties`, `.yml`, or `.json` files via the built-in code editor. 
* **Time Machine (Secure Backups):** Zip your entire server architecture for safekeeping. Restoring wipes the corrupted server and extracts the backup safely (Patched against Zip-Slip vulnerabilities).
* **Persistent Automation (Cron Jobs):** Set up recurring commands (like `/save-all` or broadcasts) that are written to internal memory and executed by the background service—even if the UI is asleep.
* **Dual Language Support:** Fully localized in both **English** and **Türkçe**.

## 🛡️ Under the Hood (Pro-Level Security & Stability)

We didn't just build a UI; we built armor around the server:
* **Dynamic RCON Security:** Hardcoded passwords are gone. AServer generates a secure, randomized UUID cryptographic password for every new server instance to prevent unauthorized RCON hijacking via open tunnels.
* **Zombie Process Slayer:** Ensures `playit.gg` background tunnels are aggressively terminated upon server shutdown, preventing memory leaks and battery drain.
* **OOM (Out-Of-Memory) Shield:** Console logs are capped and heavily cached. The app reads only the tail of `latest.log`, ensuring 60fps scrolling and zero RAM bloating even after days of uptime.
* **Real-Time Hardware Metrics:** Accurately calculates allocated RAM, true device RAM usage, and active disk size footprint natively. 

## 🛠️ Tech Stack
* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (Material 3)
* **Backend Engine:** Termux (proot-distro Ubuntu, OpenJDK 8/17/21)
* **Networking:** Native Java Sockets (RCON implementation) & Playit.gg integrated for global tunneling.

## ⚙️ How it Works
1.  **The Brain (AServer):** You select your specs. AServer calculates the correct Java version, fetches the PaperMC jar or Fabric Installer, injects EULA/server.properties, generates a secure RCON password, and creates the launch script.
2.  **The Muscle (Termux):** AServer copies a massive, automated script to your clipboard and launches Termux. Termux automatically installs Ubuntu, Java, and Playit (if enabled), then boots up your node.
3.  **The Control:** You return to AServer. A foreground "Armor" service connects via RCON on port 25575, giving you complete, real-time control over the live server instance.

## ⚠️ Disclaimer
Running a Minecraft server natively requires a decent amount of RAM and a capable CPU. This app is designed for modern Android devices. Your phone will get warm—remember to keep your device reasonably cooled if running 24/7!

Feel free to fork, contribute, or open an issue if you find a bug. Happy hosting! ⛏️
