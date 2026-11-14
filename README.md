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
- Back 15 s, forward 30 s, chapters, speed, a sleep timer; headset keys and a
  notification with the same keys.
- The shelf as cards or as big covers; light, dark or as the phone;
  English, Russian and Finnish, chosen inside the app.

Java, platform only, no build system: `./build.sh` with a platform jar and a
signing key in the environment. minSdk 26, targetSdk 35.

License: 0BSD.
