This is the sauce for the [LiveBoot](https://play.google.com/store/apps/details?id=eu.chainfire.liveboot) app.

## License

Copyright &copy; 2011-2024 Jorrit *Chainfire* Jongma

This code is released under the GPLv3. [LICENSE](./LICENSE), [COPYING](./COPYING).

## Spaghetti Sauce Project

This app is part of the [Spaghetti Sauce Project](https://github.com/Chainfire/spaghetti_sauce_project).

## About

*LiveBoot* is an app for rooted devices that shows the logcat and
dmesg output on-screen during boot. It has been around since
2014, (then) supporting Android 4.x (or 2011 and Android 2.x if you
include its predecessors).

### Opening the source

It does some neat tricks, others could learn from it. It's not really
in active development anymore (and hasn't been for years), but now and
then people nag me to fix it when it breaks, and sometimes I do. Now
*you* can fix it too! A little joy as 2020 finally ends!

### Open source but in-app puchases?

Yes, please still buy it. Or press the *Freeload* button that has been
in most of my apps for many years that gives you all the features for
free. Or recompile it yourself so it does that.

### Download

You can grab actual releases from [Google Play](https://play.google.com/store/apps/details?id=eu.chainfire.liveboot)
or in the [XDA thread](https://forum.xda-developers.com/t/LiveBoot.2976189/).

### Feedback

It puts it in the [XDA thread](https://forum.xda-developers.com/t/LiveBoot.2976189/).

### Disclaimer

This hasn't been in active development for a while, and contains code
that has been copy/pasted and passed around throughout my projects
for many years. It wouldn't surprise me if there's code in here
somewhere that was already smelly back in 2011. This is not a
shining example of how to build apps in 2020.

Especially if you're primarily familiar with newer devices, there
may be some weird constructs with regards to root, but keep in mind
this app should still be working on Android's as old as 5.0, and
supporting several different root solutions and versions.

I did not go through the code to clean it up before dumping it here.
Comments could be outdated or just plain wrong.

If it breaks, you get to keep all the pieces.

### Dependencies

Primarily depends on [libRootJava](https://github.com/Chainfire/librootjava)
and [libCFSurface](https://github.com/Chainfire/libcfsurface).

Once the Dependabot configuration is on the default branch, it checks the
Gradle project's dependencies weekly and proposes updates as pull requests.

The dual-screen changes from LiveBoot PR 20 also require the separate
[libCFSurface PR 3](https://github.com/Chainfire/libcfsurface/pull/3) for
the rendering fix on affected devices. The dependency currently remains
at libCFSurface 1.1.6, which does not include that fix.

### Exit delay and diagnostics

The **Exit delay** setting keeps LiveBoot visible for up to 60,000 ms after
the normal exit condition; its default of 0 adds no delay. Diagnostic state
is written to `/cache/liveboot.diag.log`, or `/data/local/tmp/liveboot.diag.log`
if the first location is unavailable. The diagnostic file is restricted to
its root owner and does not include logcat or dmesg line contents; exception
details and boot state may still be recorded. Running the in-app test does not
overwrite the previous boot's diagnostics.

Magisk and KernelSU hooks poll quickly for five seconds and then once per
second for up to 180 seconds. When boot completes with the data partition
available but the launcher missing, the hooks remove themselves.

### Tests

Run `python3 -m unittest discover -s tests -v` with a JDK on `PATH`.
If ShellCheck is installed, the generated boot hooks are checked with it too.

### Enjoy!
Or not.

## Credits

- [LIghtJUNction](https://github.com/LIghtJUNction) — the bounded boot-hook
  wait and stale-hook cleanup in [LiveBoot PR 25](https://github.com/Chainfire/liveboot/pull/25).
- [AntiOblivionis](https://github.com/AntiOblivionis) — submitted
  [LiveBoot PR 20](https://github.com/Chainfire/liveboot/pull/20).
- [GoldenglowSusie (澄闪)](https://github.com/GoldenglowSusie) — authored
  the display detection, diagnostics, and exit-delay changes in PR 20.
