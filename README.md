# XatTCP

A multi-user LAN chat written in Java (Swing + TCP sockets). One person hosts, everyone else joins. No server setup and no external libraries.

## Features

- Chat window with colored names, roles and timestamps
- Any number of users can join the same chat
- Live list of connected users
- Host admin panel:
  - **Change role**: Admin, Moderador, VIP… or type a custom one
  - **Change color** of any user
  - **Mute / unmute** a user
  - **Kick** a user
  - **Clear chat** for everyone
- Duplicate names are handled automatically (Pau, Pau2…)

## Download & run

1. Install **Java 11 or newer** ([Adoptium](https://adoptium.net/)).
2. Download `XatTCP.jar` from the [Releases](../../releases) page.
3. Double-click it, or run:
   ```
   java -jar XatTCP.jar
   ```

## How to use

**Host**
1. Choose *Hostejar un xat* and keep port `8080` (or pick another).
2. Your IP appears at the bottom of the window. Send it to your friends.

**Everyone else**
1. Choose *Unir-me a un xat*.
2. Enter the same port and the host's IP.

Everyone has to be on the same network (same Wi-Fi, LAN or phone hotspot).

## Troubleshooting

| Message | Cause | Fix |
|---|---|---|
| Timeout | Host firewall or different network | Host allows the port (below), or everyone joins one hotspot |
| Connexió rebutjada | Host not running, or wrong IP/port | Check the IP and port shown in the host's window |

On Windows, the **host** allows the port with this command (PowerShell as admin):
```powershell
New-NetFirewallRule -DisplayName "XatTCP" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

Some school or public Wi-Fi networks block device-to-device traffic. A phone hotspot works around that.

## Build from source

```
javac -encoding UTF-8 -d build XatTCP.java
jar cfe XatTCP.jar XatTCP -C build .
```

## Protocol

Plain text over TCP, one message per line, UTF-8.

| Direction | Message |
|---|---|
| Client → Host | `HELLO\|name`, `MSG\|text` |
| Host → Client | `WELCOME\|name`, `MSG\|name\|role\|#color\|time\|text`, `SYS\|text`, `USERS\|name,role,#color,muted;…`, `CLEAR`, `KICKED\|reason` |
