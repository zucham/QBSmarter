# QBSmarter screenshots

Here are some more screenshots of the QBSmarter app. You can see all the color variants
as well as the differences between dark mode and light mode.

[Go back to README](../../README.md).

---

## Scramble

<p align="center">
  <img src="../Screenshots/scramble.png" alt="Scramble generated and ready to apply" width="320">
</p>

The Solve screen at the start of a session: a fresh scramble is generated and
displayed both as standard cube notation and as a live 3D preview rendered with
[korender](https://github.com/zakgof/korender). As you apply moves on the
physical cube, the preview updates in real time and indicates progress along
the scramble &ndash; including small deviation corrections when you wander off
the planned sequence.

---

## Solving

<p align="center">
  <img src="../Screenshots/solving.png" alt="Mid-solve with the timer running" width="320">
</p>

Once the scramble is fully applied, the timer starts (after the optional
inspection countdown). The 3D cube view continues mirroring the physical cube
through the solve so you can see exactly what the cube reports.

---

## Solve finished

<p align="center">
  <img src="../Screenshots/solve-finished.png" alt="Solve completed with stats summary" width="320">
</p>

When the cube reaches the solved state, the timer stops and the post-solve
summary appears: final time, ao5 snapshot, fluency (turns per second), and a
quick row of action buttons for **+2**, **DNF**, and starting the next solve.
A personal-best celebration is shown when the new effective time strictly beats
the previous best for the active profile.

---

## Sidebar navigation

<p align="center">
  <img src="../Screenshots/sidebar-navigation.png" alt="Navigation drawer with all screens" width="320">
</p>

The navigation drawer lists all five screens: Solve, Devices, History, Settings,
and Guide. The active screen is highlighted; switching screens preserves
per-screen state via the underlying NavHost's save/restore mechanism.

---

## Settings (dark and light themes)

<p align="center">
  <img src="../Screenshots/settings-dark-light.png" alt="Settings shown in both dark and light themes" width="320">
</p>

Eight hand-picked colour seeds (Blue, Green, Purple, Orange, Red, Pink, Yellow,
Mono) combined with light, dark, and system theme modes give 24 total palettes.
The Settings screen also handles profile management (create / rename / switch /
delete / per-profile JSON import and export), language selection (English and
Czech), and the inspection / keep-screen-on toggles.

---

## Solve history

<p align="center">
  <img src="../Screenshots/solve-history.png" alt="Paged solve history with sort options" width="320">
</p>

Every completed solve is persisted to a per-profile SQLite database entry and listed
on the History screen. The list is paged for performance, sortable by
newest / oldest / best / worst, and supports swipe-to-delete with confirmation.
Tapping a row opens a detail dialog with the date, scramble, fluency, and turn
count for that solve.

---

## Cube pairing

<p align="center">
  <img src="../Screenshots/cube-pairing.png" alt="Pairing a smart cube over Bluetooth" width="320">
</p>

The Devices screen on first launch. Tap **Pair** to scan for nearby smart cubes.
GAN cubes (identified by their MAC OUI prefix) are sorted to the top of the
results so the cube you actually want to connect to is the one drawing the eye.

---

## Device management

<p align="center">
  <img src="../Screenshots/device-management.png" alt="Managing paired cubes" width="320">
</p>

Once paired, a cube appears in the **Paired cubes** section with its battery
level, hardware/software version (via the Info button), and per-cube actions
for Disconnect and Forget. The active cube gets a colored border and a green
indicator dot.

---

## Usage guide

<p align="center">
  <img src="../Screenshots/usage-guide.png" alt="In-app usage guide rendered as Markdown" width="320">
</p>

The Guide screen renders a bundled Markdown file localized to the current
language. It explains every screen, every setting, and includes the contact
information for reporting bugs or suggesting features.
