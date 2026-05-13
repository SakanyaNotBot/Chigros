# Chigros

A simulator for [Phigros](https://www.taptap.cn/app/165287) by Pigeon Games.

## Disclaimer

- This project is intended **only for study and research**. Commercial use or any form of illegal use is strictly prohibited.
- All assets under `app/src/main/assets/res/` and `app/src/main/res/raw/` belong to the original game.
- The font `phigros.ttf` is sourced from [qaqFei/phispler](https://github.com/qaqFei/phispler).

## Features

- Runs on Android (minSdk 24, targetSdk 36)
- Native audio engine built on [Oboe](https://github.com/google/oboe) and OpenSL ES
- Native C++ audio decoding (MP3 via [minimp3](https://github.com/lieff/minimp3), Ogg Vorbis via [stb_vorbis](https://github.com/nothings/stb))
- Chart format compatibility:
  - Official
  - RPE
  - PEC

## Build

Requirements:
- Android Studio (Ladybug or newer recommended)
- Android SDK 36
- NDK (for native C++ compilation)
- CMake 3.22.1+
- JDK 17

Steps:
1. Clone the repository
2. Create `app/keystore.properties` with your signing configuration:
   ```properties
   storeFile=keystore/your.keystore
   storePassword=your_store_password
   keyAlias=your_key_alias
   keyPassword=your_key_password
   ```
3. Open the project root in Android Studio and sync Gradle
4. Build with **Build → Make Project** or run `./gradlew assembleDebug`

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Graphics | OpenGL ES 2.0 |
| Audio | Oboe + OpenSL ES |
| Native decode | minimp3, stb_vorbis |
| JSON parsing | Jackson |
| Image format | android-gif-drawable |
| UI | AndroidX, Material Components |

## Contributing

Issues and pull requests are welcome. Before submitting a PR, please ensure changes are properly tested.

## Acknowledgements

- [TeamFlos/phira](https://github.com/TeamFlos/phira) — Render reference
- [lchzh3473/sim-phi](https://github.com/lchzh3473/sim-phi) — Render reference
- [qaqFei/phispler](https://github.com/qaqFei/phispler) — Font resource and render reference
- [PhiZone/player](https://github.com/PhiZone/player) — UI reference
- [Hxjjxg/phi_reversed](https://github.com/Hxjjxg/phi_reversed) — Judgement reversal research

## License

This project’s source code is open-source under the GPL-3.0 license. All rights to original game assets and IP belong to Pigeon Games.
