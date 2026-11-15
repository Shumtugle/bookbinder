# Bookbinder

A simple audiobook player for Android. A book is a folder of sound files;
its chapters play in the order their numbers say, not the order they were
saved in.

- **Books on the phone.** Any folder of sound files under `Music/Audiobooks`
  or a top-level `Audiobooks` is a book. Folders of one's own — on the phone
  or a memory card — are added once in the system folder picker.
- **Books from public cloud folder links.** Paste or share a link: the book
  gets its own folder in `Music/Audiobooks` and comes in chapter by chapter,
  the chapter being heard first. Nothing is ever deleted by the app.
- **The place in every book** is kept: chapter and second. A book taken up
  after a pause steps back a few seconds (adjustable).
- **The place in a chapter** is a wide strip. Held or dragged, it raises a
  drum of five-second ticks under a needle, with the time it would land on:
  exact near the finger, quicker and quicker further off, still turning when
  held at an edge.
- Back 15 s, forward 30 s, chapters, speed, a sleep timer; headset keys and a
  notification with the same keys.
- **The shelf** as cards or as big covers, the book opened last on top, each
  with a wide bar of the whole book cut into its chapters.
- **Looks:** paper, a warm theme, or the wallpaper's own colours; light, dark
  or as the phone; sharp, soft or round corners — for the app and its
  widgets alike. English, Russian and Finnish, chosen inside the app.

**Download:** the APK is attached to every release of this repository.

Java, platform only, no build system: `./build.sh` with a platform jar and a
signing key in the environment. minSdk 26, targetSdk 35.

License: 0BSD.
