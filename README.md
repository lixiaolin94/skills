[中文版](README_CN.md)

# Skills

A small collection of reusable skills for AI coding agents.

## Included Skills

| Skill | Description |
|-------|-------------|
| **[android-background-test](android-background-test/)** | Test apps on a real Android phone over ADB on a hidden background display: launch, screenshot, tap, swipe, keys, and Chinese text without disturbing the user. |
| **[web-shader-extractor](web-shader-extractor/)** | Extract WebGL/Canvas/Shader effects from websites and port them into standalone native JS projects. |

## Install

Install the whole repository:

```bash
npx skills add https://github.com/lixiaolin94/skills
```

Install a specific skill:

```bash
npx skills add https://github.com/lixiaolin94/skills --skill web-shader-extractor
npx skills add https://github.com/lixiaolin94/skills --skill android-background-test
```

## android-background-test

**Status: beta (`0.2.0-dev`).** Verified on nubia NX888J (Android 17) and OPPO PLG110 (ColorOS, Android 17). Other devices and ROMs are untested; reports with model and ROM version are welcome.

Requirements:

- `adb` on the computer and Python 3.9+ (standard library only)
- USB debugging enabled and the computer authorized on the phone
- Android 12 (API 31) or newer is the build target; the lowest working version has not been tested
- The phone must stay awake and unlocked while testing; locking pauses the session
- Some ROMs (for example Xiaomi) may need an extra "USB debugging (security settings)" switch before taps work; untested

Nothing is installed on the phone. `runtime/server.jar` is a DEX built from the source in [`workspace/android-background-test`](workspace/android-background-test/); rebuild it with `python3 workspace/android-background-test/scripts/build.py` and compare the sha256 in `android-background-test/manifest.json`.

## Acknowledgments

| Contributor | Contributions |
|-------------|---------------|
| [Huazi](https://github.com/HeyHuazi) | **web-shader-extractor** — 2D Canvas extraction report that led to 2D Canvas identification, porting strategy, and framework shell-removal rules |

## License

MIT
