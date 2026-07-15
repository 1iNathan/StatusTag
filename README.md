# StatusTag

> See who is online, away or busy at a glance. Colored status dots for every player in the tab list, on name tags and in chat.

![LabyMod 4](https://img.shields.io/badge/LabyMod-4-blue)
![Minecraft](https://img.shields.io/badge/Minecraft-1.8.9%20%E2%86%92%20latest-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

| Dot | Status |
|-----|--------|
| 🟢 Green | Online |
| 🟠 Orange | Away |
| 🔴 Red | Busy |
| ⚪ Gray | Offline, Invisible or Unknown |
| ⚫ Dark gray | Hidden profile or no LabyMod account |

Every color is customizable, including chroma.

## Features

- Status dots in the tab list, on name tags and in front of chat messages
- Notifications when a friend comes online, goes offline or becomes away or busy
- Live statuses for your LabyChat friends
- StatusTag Sync: users of this addon share their status with each other automatically
- Eight languages: English, German, Arabic, French, Spanish, Portuguese, Russian, Turkish
- Works on every Minecraft version LabyMod 4 supports, from 1.8.9 to the latest

## Installation

From the LabyMod addon store: search for StatusTag and click install.

Manual: build the jar from source (see below), drop it into
`%appdata%\.minecraft\labymod-neo\addons` and restart LabyMod.

## Settings

LabyMod Settings, Addons, StatusTag. Each feature has its own toggle and its own
settings screen: Tab List (dot size, badge width, dot position), Name Tags, Chat
and Notifications. Status colors and a cache reset live under Advanced.

## Building from source

```bash
./gradlew deployLocal
```

Output: `build/libs/statustag.jar`. Requires Java 21.
Run `./gradlew :core:test` for the unit tests.

## License

[MIT](LICENSE)
