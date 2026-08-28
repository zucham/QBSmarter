# QBSmarter

> Note: The QBSmarter app strives to be intuitive and all of its features should be easy to discover.
> It is likely that reading the following text will not be particularly useful to you, or that it won't tell you anything new.
>

The **QBSmarter** app is made for speedcubers who use Gan smart Bluetooth cubes
and are looking for a free/libre alternative to the official Gan Cube Station app on their Android phones.

With QBSmarter, you can train timed solves undisturbed in a snappy native UI with style customisation,
simple data management, and other handy features. And, most importantly, QBSmarter does all this without spying
on its users, GPS being turned on, or any need for an internet connection.

> Note - the app is aimed mainly at intermediate and advanced speedcubers who can read scramble notation
> without graphical aids. Scramble visualisation is not yet supported.

## Feedback and improvement suggestions

The app is in an early stage of development. If you have found a bug or thought of a way to make the app better,
you can send your suggestion to [zucham@duck.com](mailto:zucham@duck.com) with the subject containing the word **`QBSmarter`**, or
directly via the project's page in the **[Issues](https://codeberg.org/zucham/QBSmarter/issues)** section on Codeberg.

## Supported devices

First, make sure your smart cube is on the list of supported devices, found here:

**Full support**:
- GAN Mini ui FreePlay
- GAN12 ui FreePlay
- GAN12 ui
- GAN356 i Carry S
- GAN356 i Carry
- GAN356 i 3
- Monster Go 3Ai

**Experimental support** (untested, should work):
- GAN356 i Carry 2
- GAN12 ui Maglev
- GAN14 ui FreePlay

In the future, the list of supported devices is going to be extended; everything depends mainly on the availability
of cubes for testing and feedback from users.

If a cube is currently supported by the [cstimer.net](https://cstimer.net) program,
adding support for it to this app shouldn't be a problem either.

### Gyroscope support

Since no cube with gyroscope support was available for testing during the development of the app, the gyroscope functionality
itself is _experimental_ and untested. If you encounter any issues or have observations, don't hesitate to use
the contact methods described above and provide feedback.

---

# Getting started

Now we'll go step by step through the most important parts of the QBSmarter app.
Apart from the pairing description below, the guides are split by the screen / section they belong to.

## Pairing a smart cube with the app

Here are the basic steps for getting the app working with a smart cube:

1. After launching the app, the main screen titled **Solve** appears, where you'll find the timer, cube visualisation, and other main elements.
2. Using the **`Connect a cube`** button or via the side panel navigation (button in the top-left corner), go to the **My cubes** screen.
3. Wake your smart cube up into pairing mode (usually it's enough to turn the cube's faces a few times).
    - The cube should start to glow or blink, if it has a built-in indicator LED.
4. On the **My cubes** screen tap the **`Pair`** button.
    - You must allow the app's Bluetooth permission (older Android versions don't differentiate between Bluetooth access and device location access) - this is requested when the app starts.
    - Bluetooth must be turned on; the app will warn you if it isn't and may offer a shortcut to your phone's settings for quick activation.
5. At the top of the screen, a list of nearby Bluetooth devices with discoverability enabled should appear.
    - All Gan cubes should automatically appear at the top of the list highlighted by color.
6. Tap the appropriate cube in the list to select it, which starts the pairing process.
7. After a moment the cube should connect to the phone, indicated by green **Connected** text below the name of the paired cube on its device card.
    - Every paired cube stays stored in the app. To reconnect an already-stored cube, just tap the **`Connect`** button on the cube's device card.
8. Go back to the **Solve** screen either via the **`GO SOLVE`** button (which appears in the top-left corner after a successful cube connection) or via the side panel as in step 2.
9. The 3D cube visualisation on the screen should now react to the physical movements of the smart cube's faces.
10. We can move on to the section describing the timer; the cube has been successfully connected.

> Note: The app intentionally does not hide Bluetooth devices based on their manufacturer, so the list of available devices
> in the area will include other devices besides your smart cube. Trying to pair them with the app
> will lead to undefined behaviour. It is recommended to use the app only with officially supported devices.
>

## The **Solve** screen

The main screen of the app, where all of the solving happens. At its top there's a label with the currently connected cube - a green dot and the cube's name mean
that the app is ready to receive moves. If no cube is connected, the label is grey, the cube is dimmed in the background, and instead of the color label there's
a **`Connect a cube`** button which leads you to pairing it, as per the description above.

Below the label is the 3D cube visualisation. Drag your finger across the visualisation to freely rotate the cube; after you lift your finger, the cube automatically aligns to the nearest viewing orientation.
If you have the gyroscope feature enabled, the visualisation will instead automatically mirror the physical orientation of the cube in space.

Beneath the cube is a row of action buttons:

- **`Reset orientation`** - returns the cube view to its default orientation (white face up, green face forward). The button is only visible when the cube is rotated away from this default orientation.
- **`Gyro`** - turns gyroscope-driven visualisation on or off (only shown while a cube that supports the feature is connected). Turning it off settles the cube back onto a straight-on view.
- **`Reset state`** - returns the logical cube state to the solved position and generates a new scramble. This button is red because it overwrites all data about the current cube state and time measurement.

Below the action row is a card with the scramble and a **`New`** button for generating a new scramble. Below that, in the lower part of the screen, is the timer and finally a quick overview of statistics (personal best, averages, total solve count, and so on).

### Using the timer

The main purpose of the app is automatic time measurement during solving, scramble generation, and saving the solve history. Here is a description of the timer's phases:

1. After the cube is connected, the timer is in the **scrambling** phase. It is necessary to follow the instructions to scramble the cube.
    - The app automatically tracks both the cube's state and the scrambling progress; just stick with the visualisation and notation of moves already performed.
    - If you make a mistake during scrambling, the app guides you back to the right path with steps highlighted in red.
    - Following WCA rules, the cube's default position is always green face forward and white face up.
2. After the scrambling is complete, the timer switches to **inspection** mode (unless you have disabled `15s inspection time` in Settings). The app waits for the first cube turn or for the timer to run out.
3. After the first cube turn or after the inspection timer runs out, the timer enters **measurement** mode. It measures time until the cube is successfully solved, or until interrupted by the Reset button or device disconnection.
4. After the solve is finished, the timer stays in the **waiting** phase. The completed solve can now be assigned either `+2` or `DNF`.
5. To start a new solve, just turn any face of the cube, or tap the New button in the scramble section.
    - The turn you make is carried over into the new scramble: if it happens to be the scramble's first move you are already one move in, otherwise it shows up in red as a move to undo, exactly like any other scrambling mistake.
    - If you would rather your cube stayed solved between solves, turn off `Any turn starts a new solve` in Settings. A quick back-and-forth motion with the top face (i.e. `U U'`) then starts the new solve instead.

> If you manage to beat your personal best for the active profile, the app congratulates you with a popup dialog **New personal best!**. If you then decide to apply a `+2` or `DNF` penalty, the app will automatically retract the congratulation if the solve is no longer a record.

## The **My cubes** screen

The **My cubes** screen is used to manage all smart cubes you've paired with the app. It has two main sections:

- **Paired cubes** - a list of all cubes you've previously added to the app. For each cube, you can see its name and connection status (green dot for a connected cube, grey for a disconnected one).
  The currently connected cube also shows its battery level next to the name. The active cube is highlighted with a colored border.
- **Available devices** - a list of nearby Bluetooth-enabled devices (shown only during scanning). GAN cubes are automatically sorted to the top of the list and visually highlighted by color.

For each paired cube you'll find these buttons:

- **`Connect`** / **`Disconnect`** - connects or disconnects the cube. While connecting, a loading indicator is shown on the button. Only one cube can be connected at a time - a new connection automatically disconnects the previous one.
- **`Info`** - opens a dialog with cube details: MAC address, hardware version, software version, gyro support, and current battery level. The dialog also has an **`Edit`** button for renaming the cube.
- **pencil icon** (next to the cube's name) - renames the cube. Leave the field blank and save to go back to the name the cube reports about itself.
- **`Forget`** - removes the cube from the paired devices list. The cube can of course be re-paired in the future.

At the top of the screen you'll find different context-dependent buttons depending on the current state:

- When no cube is connected, the **`Pair`** button is available, which starts scanning for new devices.
- When a cube is already connected, two buttons appear here: **`GO SOLVE`** (on the left, takes you back to the solve screen) and **`Pair new`** (on the right, allows pairing of another cube).
- During scanning, there's a **`Cancel`** button for ending the scan.

> If you have Bluetooth disabled, the app will inform you and offer the **`Enable Bluetooth`** button, which takes you directly to your phone's system settings to turn it on.

## The **History** screen

The **History** screen contains a complete overview of all your solves within the active profile. The records are presented as a list, and at the top of the screen you'll find the total solve count and sort buttons:

- **Newest** - newest solves on top (default sort).
- **Oldest** - oldest solves on top.
- **Best** - fastest solves on top (DNFs are filtered to the bottom).
- **Worst** - slowest solves on top (DNFs are treated as worst and put on top).

Each record in the list contains:

- The measured solve time (with a plus sign for `+2` solves, or "DNF" for invalid attempts).
- The solve's date and time.
- The Average of 5 (Ao5) at the moment of this solve, if available (i.e. there are at least 5 previous solves).

**Tap a record** to open a detail dialog containing:

- The solve's date and time.
- The scramble used.
- The Average of 5 (Ao5) at that moment.
- Fluency (TPS - turns per second) at that moment.
- The number of turns performed during the solve.

The detail dialog also contains a **Delete** button for removing the record (with a confirmation step).

**Deleting a record** is possible in two ways:

1. Tap the record, open the detail dialog, and tap **`Delete`**.
2. Swipe the record to the right - a red background with the "Delete" label appears, and after dragging further a confirmation dialog opens.

In both cases the app requires a confirmation before deleting, so you don't have to worry about accidentally erasing precious records.

## The **Settings** screen

The **Settings** screen is divided into several thematic sections:

### Profile

In this section, you manage the app's user profiles. The app supports **multiple profiles** - each one has its own solve history, its own list of paired cubes, its own statistics, and even its own appearance and language preferences. This is useful if you share the app with someone else, or you want to separate different training modes.

In the profile list, the active profile is always at the top and is color-highlighted with the **Active** label. Tap an inactive profile to switch to it. Each profile row offers these buttons:

- **Gear icon** (at the start of the row) - opens the profile settings dialog where you can:
    - edit the profile's display name,
    - see the total solve count for the profile,
    - export the profile's data to a JSON file.
- **Trash icon** (at the end of the row) - deletes the profile. Deletion is also possible by swiping the profile row to the right. Profile deletion is irreversible, and all related data (history, cubes, settings) is deleted with it.

> **Note**: The app guarantees that at least one profile always exists. If you delete the last existing profile, a new empty profile is automatically created.

Below the profile list you'll find two buttons:

- **`Create profile`** - creates a new profile. If you don't enter a name, the profile gets a default name. After creation the new profile is immediately activated.
- **`Import profile`** - loads a previously exported profile from a JSON file. More information about importing is below.

### Solving

- **`15s inspection time`** - after the scramble is finished, the app gives you 15 seconds (per WCA rules) to inspect the cube before the timer starts. If you turn this off, the timer starts automatically on the first turn after the scramble finishes.
- **`Keep screen on while solving`** - while solving, the app prevents the phone from putting the screen to sleep. After a solve finishes (or when navigating to a different screen) this mode is turned off again.
- **`Any turn starts a new solve`** - on by default. Once a solve is finished, the next turn you make brings up a new scramble and starts the next solve. Turn it off to require a quick `U U'` instead, which has the advantage of leaving the cube solved.

### Display

- **`Theme`** - choice between light, dark, and system theme.
- **`Color`** - choice of the app's primary color from 8 color palettes (blue, green, purple, orange, red, pink, yellow, monochrome).
- **`Language selection`** - choice of the app's language. The options are **By system** (the app takes its language from the operating system; if the system language isn't supported by the app, English is used) or **Manual**, where you pick a specific language from a dropdown menu (currently English and Čeština).

> The language change applies immediately - the app automatically refreshes the screen.

### Advanced

- **`Use caching`** - the app keeps frequently used data in memory for faster screen transitions. We recommend keeping this setting on for the best experience.

### About

Here you'll find information about the current version of the app and the unique identifier of the active profile, which can is useful when reporting bugs or debugging. The identifier can be selected and copied.

### Importing and exporting profile data

The app allows you to export your complete profile (solve history, paired cubes, and all settings) into a single JSON file, and import it back later - even on a different device. This functionality serves as both a backup and a means of transferring data.

**Export** is performed by tapping the gear icon next to the chosen profile and then the **`Export profile`** button in the dialog. The app will ask you to choose a file location. The file name is `qbsmarter-<profile_name>.json`.

**Import** is started by the **`Import profile`** button below the profile list. The app offers a JSON file picker. After the file is selected, the import proceeds as follows:

- **If the imported profile's ID matches an existing profile**, its data is merged with it:
    - Settings are overwritten with the imported values.
    - Paired cubes and solve history are appended, with duplicate records skipped.
    - This is an advantage - re-importing the same profile file will not duplicate the data. If you have the same profile on multiple devices and want to add data from another device, the merge keeps both sets.
- **If a profile with the given ID doesn't exist locally**, a new one is created with the ID and name from the backup.
- **Existing local profiles that are not in the import** remain untouched.

> **Important**: If you first **delete** a profile and then **create a new profile with the same name**, the new profile will have a **different internal ID** than the original one. If you then import a file that was exported from the original profile, the app will **create a third, new profile** (using the original ID from the backup) and the new profile will remain empty. So if you want to restore old history into an existing profile, import the backup **before** you delete the same profile - otherwise you cannot merge the data into it.

# Conclusion

If you have read the guide all the way down here, congratulations! Thank you for deciding to give QBSmarter a try. May it help you with your training and make working with smart cubes easier, both at home and on the go.

If you have ideas for improvements, encountered a bug, or have any other question, don't hesitate to write to [zucham@duck.com](mailto:zucham@duck.com) with the subject containing the word **`QBSmarter`**, or open an **[Issue](https://codeberg.org/zucham/QBSmarter/issues)** on the project's page.

Good luck with your solves!