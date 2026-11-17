# Changelog

## 1.1.3

More colored dots, fewer wrong gray ones.

- laby.net often answers Offline for players who are online and shows their real status only now and then. A status seen on laby.net is now kept for up to an hour while the player stays in the tab list instead of turning gray at the next check.
- Players in the tab list are re-checked every 2 minutes instead of every 6 to 10, in combined requests, so a status is caught while laby.net shows it.
- Players with a LabyMod account are also recognised from LabyMod's own account list. Players who hide their status keep their gray dot even when LabyChat sends no player data, for example right after Clear Cache.
- Players already known with a status or a hidden profile are no longer shown as having no LabyMod account when laby.net leaves them out.

## 1.1.2

Smoother statuses, no duplicate notifications and fewer laby.net requests.

- Friend status notifications also show the previous status in dark gray, for example "is now online (was Away)". The setting is now called Friend Changes Status.
- New: Notifications, Messages. Write your own text for each friend notification, with {status} for the new status, {previous} for the one before and color codes like &8. Leave a field empty to keep the default text in your language.
- No second notification when LabyMod already shows its own "is now online" or "is now offline" for a friend, also when LabyMod's notification comes late. StatusTag's friend notifications now appear 3.5 seconds after the change.
- The sound for pinned friends only plays when the notification is actually shown, not while your status is Busy or LabyMod notifications are off.
- A status shared through StatusTag Sync is kept for up to 8 minutes without news from that player before laby.net may replace it (was 4), so the dots of other StatusTag users no longer turn gray during short gaps.
- StatusTag Sync stops sending your status to players who left your server, and /statustag counts only StatusTag users in the current tab list.
- Sync announces you again when a network moves you to another lobby or game server.
- If LabyConnect rejects a Sync message, it is sent again after the pause instead of being dropped.
- Sync never shares a status you are still switching through, and sends a heartbeat every 2 minutes so players still on 1.0.0 no longer answer each one with a message to the whole server.
- Turning Sync off forgets other StatusTag users and the statuses they shared right away.
- Players who hide their status on laby.net get their gray dot as soon as LabyMod recognises them, without a second request.
- Re-checks of offline, invisible and hidden players that are due within a minute are combined into one laby.net request instead of one request per player.
- After a LabyChat reconnect, friends keep their last color until LabyChat sends the friend list again instead of turning gray.
- Your own dot keeps following your status when the LabyChat Friends source is off, and switching accounts no longer leaves the previous account's friends frozen.
- /statustag shows player names with their real capitalization and no longer adds an age to live LabyChat statuses.
- The Status Overview widget follows color changes right away and shows real numbers from the first moment in a world.
- The status cache is written at most once a minute instead of every few seconds.
- Better handling of laby.net error codes and Retry-After headers.
- Spanish, German and French also load for regional variants such as Español (México), Deutsch (Österreich) and Français (Canada).
- Arabic: clearer names for Invisible and Name Tags.
- Settings from 1.0.0 are no longer migrated again at every start.

## 1.1.1

Fewer friend notifications, and StatusTag Sync no longer runs into LabyConnect's message limit.

- Friend notifications wait until a friend's status has stayed the same for 3 seconds. LabyChat often flickers between Online, Away and Offline while a friend's game reconnects. You now get one notification for where they end up, or none if they end up where they started.
- When a friend joins your server, you get "joined your server" instead of "is now online" as well.
- StatusTag Sync sends at most 3 messages per minute, at least 5 seconds apart, and counts the messages other addons send over LabyConnect, such as Spotify track sharing. LabyConnect limits these messages per account, which showed up as "Custom payload: Request failed: RATE_LIMITED".
- If LabyConnect still rejects a message, Sync pauses for a minute, and longer if it happens again.
- A new status is shared once it has stayed the same for 2 seconds, so switching through several statuses sends only the last one.
- Clicking Clear Cache several times no longer sends a burst of messages.
- Status announcements wait until other players are around.
- Sync sends a heartbeat every 200 seconds and a full announcement every 10 minutes, about half as often as before. Still compatible with 1.0.0 and 1.1.0.

## 1.1.0

Faster, lighter and more accurate.

- Status lookups now use a single laby.net request per player instead of up to three.
- Players you can see in the world are looked up first.
- Uses laby.net's bulk status lookup: up to 100 players per request instead of one request per player, so a full tab list is filled in seconds. If laby.net rejects it or it keeps failing, StatusTag switches to single lookups on its own and tries bulk again later.
- Request pacing follows laby.net's own rate-limit headers, so lookups are faster when the API allows it and never run into limits.
- Statuses refresh every few minutes while a player is in the tab list. Previously a status could stay unchanged for up to 30 days.
- Statuses that players hide on laby.net stay hidden. They show as Hidden Profile and are re-checked once a day.
- New default colors: Offline, Invisible and Hidden Profile are gray, Unknown and No LabyMod are dark gray. Colors you changed yourself are kept.
- StatusTag Sync replies only to the player who asked instead of the whole server, batches replies, and sends heartbeats only to other StatusTag users. Compatible with 1.0.0.
- Players on offline-mode servers and NPCs are no longer looked up.
- Unknown players show the Unknown color instead of a green dot until their status is known.
- Chat messages from players with an unknown status trigger a lookup right away.
- Chat dots now also work on servers that don't tell who sent a message: the sender is found by their name in the tab list. Can be turned off.
- Hover over a chat dot to see the status, click it to open the player's laby.net profile.
- New HUD widget: Status Overview shows how many players on the server are online, away, busy or offline.
- Change your own LabyChat status with /statustag online, away, busy or offline, or with a keybind that switches between Online, Away and Busy.
- /statustag <player> also shows when an offline player was last online, from LabyChat for friends and from laby.net for everyone else.
- New command: /statustag shows how many players are known, how many use StatusTag and what's still waiting. /statustag <player> shows one player's status, where it came from and how old it is.
- New setting: Name Tags, Dot Size.
- New setting: Notifications, Pinned Friends Only. Only notifies you about friends you pinned in LabyChat. On for new installs, off if you update from 1.0.0.
- New setting: Notifications, Sound for Pinned Friends. Plays a sound with notifications about pinned friends.
- New notification: a friend joined the server you're playing on. Can be turned off.
- New command: /statustag friends lists your online LabyChat friends, pinned first, with their status and server. Click a name to open their laby.net profile.
- New setting: Disabled Servers. StatusTag hides its dots and stops lookups on the servers you add. Subdomains are included, so hypixel.net also covers mc.hypixel.net.
- New setting: Players Without LabyMod. Shows a dark gray dot for players without LabyMod or with an unknown status. Off for new installs, on if you update from 1.0.0.
- Status Colors are laid out in two groups of two per row, so the names are readable again.
- New installs start with notifications turned off and a narrower tab list badge. Settings you already have are kept.
- StatusTag is listed under Player in the Mods menu.
- Notifications use your custom status colors.
- The status cache is written in the background in a compact format and keeps at most 5,000 players. Your old cache is migrated automatically.
- The debug log is written in the background and costs nothing while it's off.
- Name tag dots are cheaper to draw.
- Built with LabyGradle 0.11.0 and Gradle 9.7.1.
- Declares the permissions it uses: file system access for the status cache and the debug log, and external server access for laby.net status lookups.

## 1.0.0

Initial release.

- Colored status dots in the tab list, on name tags and in chat.
- Live statuses for LabyChat friends.
- StatusTag Sync: users of the addon share their status with each other.
- Notifications when a friend comes online, goes offline or becomes away or busy.
- Customizable colors for all eight status kinds, including chroma.
- Eight languages: English, German, Arabic, French, Spanish, Portuguese, Russian, Turkish.
- Supports every Minecraft version LabyMod 4 supports.
