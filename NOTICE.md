# Epsilon Third-Party Notices

Epsilon is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE) for the full license text.

This project contains code derived from or adapted from the following upstream
projects.

## Meteor Client

- Repository: [MeteorDevelopment/meteor-client](https://github.com/MeteorDevelopment/meteor-client)
- License: GNU General Public License v3.0
- Copyright: Copyright (c) 2021 Meteor Development.
- Used in Epsilon: ESP-related functionality.

The original code has been modified and adapted for Epsilon's module,
rendering, event, and multi-loader architecture.

## Orbit

- Repository: [MeteorDevelopment/orbit](https://github.com/MeteorDevelopment/orbit)
- License: MIT License
- Copyright: Copyright (c) 2021 Meteor Development
- Used in Epsilon: event bus implementation.

The original code has been modified and adapted for Epsilon's package
structure and event system.

### MIT License Notice For Orbit

```text
Copyright (c) 2021 Meteor Development

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## LeavesHack

- Repository: [MrBZBZ/LeavesHack](https://github.com/MrBZBZ/LeavesHack)
- License: GNU Affero General Public License v3.0
- Used in Epsilon: PacketMine module.

The original code has been modified and adapted for Epsilon's module,
setting, rendering, inventory, and event systems.

LeavesHack is licensed under the GNU Affero General Public License v3.0. The
GNU GPLv3 and GNU AGPLv3 include compatibility terms for combining GPLv3 and
AGPLv3 works; the AGPLv3 network-interaction source requirements apply where
required by that license.

## TrollHack

- Repository: [Luna5ama/TrollHack](https://github.com/Luna5ama/TrollHack)
- License: GNU General Public License v3.0
- Used in Epsilon: ZealotCrystalPlus module.

The original code has been modified and adapted for Epsilon's module,
setting, rotation, and event systems.

## SlimefunHelper

- Repository: [m1919810/SlimefunHelper](https://github.com/m1919810/SlimefunHelper)
- Reference revision: `1.21.11`
- License: Creative Commons Zero v1.0 Universal (CC0-1.0)
- Used in Epsilon: ElytraCombat target prediction, behavior state machines,
  elytra direction solving, local flight evasion, and kinetic weapon handling.

SlimefunHelper is distributed under CC0-1.0. Portions of its ElytraBot
behavior and related flight-control ideas were studied and adapted for
Epsilon's architecture, mappings, event bus, settings, and module lifecycle.
CC0-1.0 does not require attribution; this notice is included voluntarily.

License reference:
https://creativecommons.org/publicdomain/zero/1.0/

## RavenBS-Plus-Plus

- Repository: [OlziYT/RavenBS-Plus-Plus](https://github.com/OlziYT/RavenBS-Plus-Plus)
- Target: Minecraft 1.8.9 (Forge)
- Author: [@OlziYT](https://github.com/OlziYT)
- Used in Epsilon: the `Telly` module.

The telly bridging algorithm - edge detection, the 21 frame rotation script,
block candidate search and placement strategy - was ported from this project's
telly script to Epsilon's Minecraft 26.2 module, rotation and event systems.

## Leader-Lite

- Repository: [woshijiejue/Leader-Lite](https://github.com/woshijiejue/Leader-Lite)
- Target: Minecraft 1.8.9 (Forge)
- Author: [@woshijiejue](https://github.com/woshijiejue)
- Used in Epsilon: the `Legit` mode of the `Scaffold` module.

The sneak-rise bridging algorithm - edge state machine, rotation rate limiting
and the placement gate - was ported from this project's Legit mode to Epsilon's
Minecraft 26.2 module, rotation and event systems.

## OpenVape4.21

- Repository: [minecrafttzh/OpenVape4.21](https://github.com/minecrafttzh/OpenVape4.21)
- Author: [@minecrafttzh](https://github.com/minecrafttzh)
- Used in Epsilon: the `Ai` aim mode of the `KillAura` module.

The AI rotation mode - feeding a combat regression model's per-tick yaw/pitch
deltas into the rotation pipeline - was ported from this project to Epsilon's
Minecraft 26.2 module and rotation systems.
