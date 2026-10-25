# freej2me

![Java CI](https://github.com/hex007/freej2me/workflows/Java%20CI/badge.svg)

A free J2ME emulator with libretro, awt, sdl2 and sdl3 frontends.

Authors :
- David Richardson [Recompile@retropie]
- Saket Dandawate  [Hex@retropie]

## Implementations by @loddv

The following changes cover all 17 commits authored by [@loddv](https://github.com/loddv) on the `dev` branch:

- [`2d436ea`](https://github.com/loddv/freej2me/commit/2d436ea39c60395dc377b8fbb603524fa4d8532c) — expanded the **Display Size** menu. Available sizes: `96x65`, `96x96`, `104x80`, `128x128`, `132x176`, `128x160`, `176x208`, `176x220`, `208x208`, `240x320`, `320x240`, `240x400`, `352x416`, `360x640`, `360x480`, `640x360`, `640x480`, `480x360`, `480x800`, `800x360`, `800x480`, `540x960`, and `960x540`.
- [`b503b29`](https://github.com/loddv/freej2me/commit/b503b29aa7fa70cc6a2f95577bcaee02c76a45e9) — refactored `WavImaAdpcmDecoder`, restructuring methods and adding explanatory comments.
- [`60a88c9`](https://github.com/loddv/freej2me/commit/60a88c919055a483fcb8fa148cb012202ee5547c) — added a **Max MIDI Players** configuration setting and connected it to the player logic.
- [`00b4b3f`](https://github.com/loddv/freej2me/commit/00b4b3f4e2f6eb5487d9e3367194a8ffe4c43513) — reordered settings for usability and refactored exit handling.
- [`c050f68`](https://github.com/loddv/freej2me/commit/c050f6809ed204d957da6f44fa46af688b790056) — refactored menu item rendering and added formatted setting values.
- [`0d87528`](https://github.com/loddv/freej2me/commit/0d87528bcc61003490004fdc8e9fca28a2a57814) — introduced `WavMixer` for low-latency audio mixing and refactored audio player logic.
- [`1e158ad`](https://github.com/loddv/freej2me/commit/1e158ad284f71b85b177e6e13b71a5761d1607dd) — added the `540x960` and `960x540` screen resolutions.
- [`892328b`](https://github.com/loddv/freej2me/commit/892328b0e7f6eac1e68d23b54cd392fc53be3198) — added CI artifact uploads for SDL3 and other builds.
- [`5a9f5af`](https://github.com/loddv/freej2me/commit/5a9f5af2bfd7160bc1068c893bb8f43f841d291c) — added the SDL3 frontend and its interface implementation.
- [`91d2081`](https://github.com/loddv/freej2me/commit/91d20812e5eea28ac58e0941640bb3e3368566ab) — improved game restart handling and MIDlet lifecycle management.
- [`c41b9cb`](https://github.com/loddv/freej2me/commit/c41b9cbb8f651955448f6275781a73b3ed0b2ebe) — updated the settings menu presentation, including its background and selection colors, title wording, and back-key label; also ignored the local `config/` directory.
- [`ab954f6`](https://github.com/loddv/freej2me/commit/ab954f6d41e9de1462bcc7d948d869e617d3e968) — implemented game restart.
- [`5b9cd78`](https://github.com/loddv/freej2me/commit/5b9cd78c209b47e9040bbbcb3892bd680c77a7cf) — fixed the LCD background and excluded IDE-generated output from version control.
- [`a3afcaa`](https://github.com/loddv/freej2me/commit/a3afcaacbfee1ec52420adcbb1a2cb382fdc0590) — exposed accessors for inverted screen dimensions.
- [`93eb29b`](https://github.com/loddv/freej2me/commit/93eb29be0eb90cb0b3797739427f04791a97e55e) — mapped desktop mouse input correctly when the screen is rotated.
- [`194c2f0`](https://github.com/loddv/freej2me/commit/194c2f041747320bcaa8dafe7b0ed3f6c02a1553) — implemented configurable screen rotation.
- [`b22b631`](https://github.com/loddv/freej2me/commit/b22b6316925cc761dbd995ff2e8a8f2d0b893189) — published the project source and updates to build files, game configurations, packaging, and license.

---

## Controls

* `Q` and `W` for left and right softkeys.
* Arrow keys for nav, unless phone is set to "Standard", when arrow keys become 2, 4, 6, and 8.
* Numbers work as expected, the number pad is inverted (123 swap with 789, like a phone)
* `E` and `R` are alternatives to `*` and `#`.
* Enter functions as the Fire key or `5` on "Standard" mode
* ESC brings up the settings menu
* In the AWT frontend (freej2me.jar) `Ctrl+C` takes a screenshot and `+`/`-` can be used to control the window scaling factor

Click [here](KEYMAP.md) for information about more keybindings

## Links
Screenshots:
  https://imgur.com/a/2vAeC

Discussion/Support thread:
  https://retropie.org.uk/forum/topic/13084/freej2me-support-thread

Development thread:
  https://retropie.org.uk/forum/topic/11441/would-you-like-to-play-nokia-j2me-games-on-retropie/

----
**FreeJ2ME Jar Compilation:**

>From the root directory, running the following commands:
>```
> > cd freej2me/
> > ant
>```
> Will create three different jar files inside `build/`:
>
> `freej2me.jar` -> Standalone AWT jar executable
> 
> `freej2me-lr.jar` -> Libretro executable (has to be placed on the frontend's `system/` folder, since it acts as a BIOS for the libretro core and runs J2ME jars)
>
>`freej2me-sdl.jar` -> Jar executable meant to be used in conjunction with SDL2
>
>`freej2me-sdl3.jar` -> Jar executable meant to be used in conjunction with SDL3
>
>The Libretro, SDL2, and SDL3 frontends need their corresponding native binaries to be compiled before use. Look at the additional steps below.

**Building the SDL2 binary:**
>
>To build the SDL2 binary, run the following commands from the root directory:
> ```
> # SDL2 binary compilation
> > cd src/sdl2
> > make
> > make install
> ```
>
> SDL2 allows FreeJ2ME to run on a Raspberry Pi.

**Building the SDL3 frontend:**
>
>Install SDL3 (version 3.2 or newer), FreeImage, a C++ compiler, `pkg-config`, and Ant. From the repository root, run:
> ```
> ant
> cd src/sdl3
> make
> sudo make install
> ```
> This installs `sdl3_interface` at `/usr/local/bin/sdl3_interface`. Launch a MIDlet with `java -jar build/freej2me-sdl3.jar 'file:///path/to/midlet.jar' [width] [height]`.
> The SDL3 frontend is an additional Linux frontend; the SDL2 frontend remains available.

**Building the Libretro core**

> **For linux:**
>To build the libretro core, open a terminal in freej2me's folder run the following commands from there:
>```
># libretro core compilation
> > cd src/libretro
> > make
>```
>This will build `freej2me_libretro.so` on `src/libretro/`, which is the core libretro will use to interface with `freej2me-lr.jar`.
>
>Move it to your libretro frontend's `cores/` folder, with freej2me-lr.jar on `system/` and the frontend should be able to load j2me files afterwards.
>
>NOTE: The core DOES NOT WORK on containerized/sandboxed environments unless it can call a java runtime that also resides in the same sandbox or container, keep that in mind if you're running a libretro frontend through something like flatpak or snap for example.
>

> **For windows:**
>To build the libretro core for windows, first you'll need mingw, or MSYS2 64. **`This guide uses MSYS2`** as it's easier to set up and works closer to linux syntax.
>
> Download MSYS2-x86_64 and install it on your computer. By default it will create a linux-like 'home' folder on C:\msys64\home\ and will put a folder with your username in there. This is where you have to move the freej2me folder to, so: `C:\msys64\home\USERNAME\freej2mefolder` for example.
>
> With the folder placed in there you can build the core, open the MSYS2 UCRT64 terminal from your pc's start menu, and run the following commands:
>```
> # Installing 'mingw-w64' and 'make' on msys2
> > pacman -S mingw-w64-ucrt-x86_64-gcc
> > pacman -S make
>
> # libretro core compilation
> > cd freej2mefolder/src/libretro
> > make
>```
>This will build `freej2me_libretro.dll` on `freej2mefolder/src/libretro/`, which is the core libretro will use to interface with `freej2me-lr.jar`.
>
>Move it to your libretro frontend's `cores/` folder, with freej2me-lr.jar on `system/` and the frontend should be able to load j2me files afterwards.
>
>NOTE: The windows core has only been tested on Windows 10 x64.

----
**Usage (applies to AWT and SDL):**

Launching the AWT frontend (freej2me.jar) will bring up a filepicker to select the MIDlet to run.

Alternatively it can be launched from the command line: `java -jar freej2me.jar 'file:///path/to/midlet.jar' [width] [height] [scale]`
Where _width_, _height_ (dimensions of the simulated screen) and _scale_ (initial scale factor of the window) are optional arguments.

The SDL2 frontend (freej2me-sdl.jar) accepts the same command-line arguments format, aside from the _scale_ option which is unavailable.

When running under Microsoft Windows please do note paths require an additional `/` prefixed. For example, `C:\path\to\midlet.jar` should be passed as `file:///C:\path\to\midlet.jar`

FreeJ2ME keeps savedata and config at the working directory it is run from. Currently any resolution specified at the config file takes precedence over the values passed via command-line.

---
**How to contribute as a developer:**
  1) Open an Issue
  2) Try solving that issue
  3) Post on the Issue if you have a possible solution
  4) Submit a PR implementing the solution

**If you are not a developer:**
  1) Post on discussion thread only

**Roadmap:**
  - Get as many games as possible to work well.
  - Document games that work well in the wiki
  - Reduce as many bugs as possible
