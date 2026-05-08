# QBSmarter screenshots

Here are some more screenshots of the QBSmarter app. You can see all the color variants
as well as the differences between dark mode and light mode.

[Go back to README](../../README.md).

---

<div align="center">

## Solve screen: Scrambling phase

  <img src="../Screenshots/scramble.png" alt="Scramble generated and ready to apply" width="320">

</div>

The Solve screen at the start of a session: a fresh scramble is generated and
displayed both as standard cube notation and as a live 3D preview. As you apply moves on the
physical cube, the preview updates in real time and indicates progress along
the scramble &ndash; it also indicates any wrong moves you did that deviate from the scramble,
and immediately gives you the moves to return to the correct scramble path.

---


<div align="center">

## Solve screen: Solving phase

  <img src="../Screenshots/solving.png" alt="Mid-solve with the timer running" width="320">

</div>

Once the scramble is fully applied, the timer starts (after the optional 15-second
inspection countdown). The 3D cube view continues mirroring the physical cube
through the solve so you can see exactly what the cube reports. You can stop the solve by pressing
the Reset state button as well.

---

<div align="center">

## Solve screen: Solving finished

  <img src="../Screenshots/solve-finished.png" alt="Solve completed with stats summary" width="320">

</div>

When the cube reaches the solved state, the timer stops and the post-solve
summary appears: final time, Ao5 snapshot, fluency (turns per second), and a
quick row of action buttons for **+2**, **DNF**, and starting the next solve.
A personal-best celebration is shown when the new effective time strictly beats
the previous best for the active profile. There is a quality of life feature to start a new solve
simply by performing a quick `U U'` move sequence.

>Note: this quick back-and-forth move sequence is detected regardless of the face it is perfomed on &ndash;
>this feature exists to ease up the whole process, with as minimal friction with your muscle memory as possible. 

---

<div align="center">

## Sidebar navigation

  <img src="../Screenshots/sidebar-navigation.png" alt="Navigation drawer with all screens" width="320">

</div>

The navigation drawer lists all five screens: Solve, Devices, History, Settings,
and Guide. The active screen is highlighted; switching screens preserves
per-screen state via the underlying NavHost's save/restore mechanism. That means you can press
the Back button or perform the system Back gesture to get to the Solve screen quickly from anywhere.

---

<div align="center">

## Settings screen

  <img src="../Screenshots/settings-dark-light.png" alt="Settings shown in both dark and light themes" width="320">

</div>

The Settings screen handles profile management (create / rename / switch /
delete / per-profile JSON import and export), language selection (English and
Czech), and the inspection / keep-screen-on toggles.
You can also choose from eight hand-picked color seeds (Blue, Green, Purple, Orange, Red, Pink, Yellow,
Mono) combined with light and dark theme modes to give you 16 total palettes. The app respects your
system's configured theme mode by default.

---

<div align="center">

## History screen

  <img src="../Screenshots/solve-history.png" alt="Paged solve history with sort options" width="320">

</div>

Every completed solve is persisted to a per-profile SQLite database entry and listed
on the History screen. The list is paged for performance, sortable by
newest / oldest / best / worst, and supports swipe-to-delete with confirmation.
Tapping a row opens a detail dialog with the date, scramble, fluency, and turn
count for that solve.

---

<div align="center">

## My cubes screen: New cube pairing

  <img src="../Screenshots/cube-pairing.png" alt="Pairing a smart cube over Bluetooth" width="320">

</div>

The My cubes screen upon first launch. Tap **Pair** to scan for nearby smart cubes.
GAN cubes (identified by their MAC OUI prefix) are sorted to the top of the
results so the cube you actually want to connect to is the one drawing the eye.

---

<div align="center">

## My cubes screen: Device management

  <img src="../Screenshots/device-management.png" alt="Managing paired cubes" width="320">

</div>

Once paired, a cube appears in the **Paired cubes** section with its battery
level, hardware/software version (via the Info button), and per-cube actions
for Disconnect and Forget. The active cube gets a colored border and a green
indicator dot, which helps when you have multiple devices paired. Only one device
may be connected at once.

---

<div align="center">

## Usage guide screen

  <img src="../Screenshots/usage-guide.png" alt="In-app usage guide rendered as Markdown" width="320">

</div>

The Guide screen renders a bundled Markdown file localized to the current
language. It explains every screen, every setting, and includes the contact
information for reporting bugs or suggesting features. It should be enough to
serve any new user with all the useful information.
