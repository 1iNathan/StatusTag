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
| ⚪ Gray | Offline, Invisible or Hidden profile |
| ⚫ Dark gray | Unknown or no LabyMod account |

Every color is customizable, including chroma.

## Features

- Status dots in the tab list, on name tags and in front of chat messages
- Hover over a chat dot to see the status, click it to open the player's laby.net profile, even on servers that don't tell who sent a message
- Status Overview HUD widget: online, away, busy and offline players on the server at a glance
- Change your own LabyChat status with `/statustag away` (online, away, busy, offline) or a keybind
- `/statustag` shows a summary of the server, `/statustag <player>` shows one player's status and where it came from
- `/statustag friends` lists your online LabyChat friends with their status and server
- Notifications when a friend comes online, goes offline, changes status (with the previous status in dark gray), or joins your server, optionally only for pinned friends and with a sound. Every message can be replaced with your own text
- Live statuses for your LabyChat friends
- StatusTag Sync: users of this addon share their status with each other automatically
- Optionally show dots for players without LabyMod
- Turn StatusTag off on servers of your choice
- Eight languages: English, German, Arabic, French, Spanish, Portuguese, Russian, Turkish, including the regional variants of Spanish, German and French
- Works on every Minecraft version LabyMod 4 supports, from 1.8.9 to the latest

## Where statuses come from

1. **LabyChat friends** – live, straight from your LabyChat connection.
2. **StatusTag Sync** – players who use this addon share their own status with everyone in the tab list over LabyChat. Replies go only to the player who asked, so even full servers stay quiet.
3. **laby.net** – up to 100 players per request, players you can see first, paced by laby.net's rate-limit headers and refreshed every few minutes while the player is in the tab list. Statuses that players hide on laby.net stay hidden.

Each source can be turned off under Status Sources.

## Installation

Download the jar from the [latest release](https://github.com/1iNathan/StatusTag/releases/latest),
drop it into the `addons` folder of your LabyMod instance and restart LabyMod.
You can also build it yourself (see below).

## Settings

LabyMod Settings, Addons, StatusTag. Each feature has its own toggle and its own
settings screen: Tab List (dot size, badge width, dot position), Name Tags (dot size),
Chat (sender detection) and Notifications. Disabled Servers takes a list of server addresses
where StatusTag stays hidden. Status colors and a cache reset live under Advanced.
Debug Logging under Advanced writes `statustag/debug.log` in your Minecraft folder, next to
`status-cache.json`, for troubleshooting.

## Building from source

```bash
./gradlew build createReleaseJar
```

Output: `build/libs/statustag-release.jar`. Requires Java 21.
`./gradlew deployLocal` also copies the jar into the folder set as `labymodAddonsDir`
in your Gradle properties. Run `./gradlew :core:test` for the unit tests.

## License

[MIT](LICENSE)
