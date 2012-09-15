# Overview

scala-libs-for-android-emulator provides you an easy way to preinstall the scala libraries on your android emulator, so
to speed up your development as you do not need to include the scala libs into your debug builds then. I've tested this
also on current emulator images (ICS and Jelly Bean, either ARM or x86 with hardware accelerated emulation).

**Disclaimer:** The script will currently only run on Windows development systems, but as it is written in scala it
should be easy to adjust the few Windows specific code lines. If someone has solved this, just let me know.

## The Problem

If you are -- like me -- tired of writing verbose Java code, but want to develop for the Android platform, then Scala is
a great choice. Unfortunately using the normal build process in conjunction with Scala is no good choice, as in each
build the full scala libraries need to be processed, what takes quite a few minutes: You either have to feed the whole
scala library to the `dx` tool (which converts java byte code to dalvik vm code) or the other choice is to use pro guard
to remove all unreferenced artifacts, but this analysis requires some time.

## The Solution

But there is a solution to bring down the turn around times to the same level as if you were using java: Preinstall
the scala libraries on your emulator, so that you can exclude them from your debug build (you will still need to include
them into your release!).

I've found a few tutorials of how to modify the emulator's ramdisk and to include the scala libs into the `BOOTCLASSPATH`.
This worked fine for emulators up to API level 10 (android 2.3.3), but when I tried it on API level 15 (ICS) or
16 (Jelly Bean) I've got a boot loop and the emulator never came up.

While searching around in the web for a solution I've stumbled upon
[jbrechtel's Android-Scala-Installer](https://github.com/jbrechtel/Android-Scala-Installer) which is an android app that
preinstalls the scala libs on a rooted android device and uses another idea: Instead of modifying the `BOOTCLASSPATH`
it installs the scala libs as system libraries which can then be used by your app using `<uses-library />` statements
in the `AndroidManifest.xml`. I've tried this on the emulator and voila: It was working.

To easily path an emulator I then simply created this scala script which does everything for us -- from starting up
the emulator to installing the libraries, creating a new system image and putting it into the right AVD directory.
All you need to do is to start the script and afterwards restart the emulator (cannot be automated on Windows).

If you are intersted in the details, of how this works, simply take a look into the scala script...

# Usage

Using the script is easy:
* Download the whole directory tree of this project to your system (either by cloning the repository or downloading it a ZIP)
* Ensure that your `PATH` contains the scala compiler and the android tools
* call the script without any parameters to get usage help and a list of available scala versions and AVDs:
```batch
scala install.scala
```

On my system the output looks like this:
```batch
USAGE: scala install.scala <avd> <scala-version>

Installs the scala libraries in the specified emulator to reduce turnaround
times when developing with scala for android.

<avd>            the name of the android virtual device to install the libs on
                 possible values:
                 2.3.3_HVGA, 4.0.3_HVGA, 4.1_HVGA
<scala-version>  the scala version to install. possible values:
                 2.10.0-M7, 2.9.2
```

* Call the script with the adequate parameters and wait until it's done (what may take a few minutes)
* Add the `<uses-library />` statements outputed at the end of the script run to your `AndroidManifest.xml`
* Enjoy developing android with scala!


# Supported Scala Versions

Currently the script supports the following scala versions:
* 2.9.2
* 2.10.0-M7

It is quite easy to support additional versions:
1. Copy your `scala-library.jar` and split it up into several smaller jars based on a package level, so that they can
 be processed using the `dx` tool.
2. Use the `dx` tool to convert each of the resulting JVM-JARs into Dalvik-JARs as followed:
```batch
dx -JXmx1024M -JXms1024M -JXss4M --no-optimize --debug --dex
    --output=\tmp\scala\android\scala-library.jar \tmp\scala\jvm\scala-library.jar
```
2. Clone an existing version directory inside the `scala` folder
3. Replace the JARs with your newly generated ones
4. Adjust the permission files for the version accordingly

That's it. **And please don't forget to provide me the new version, so that I can include it into the distribution here
at GitHub**