import java.io.File
import sys.process.Process

/**
 * Possible target platform
 */
private object TargetPlatform extends Enumeration {
  type Platform = Value
  val arm, x86 = Value
}

/**
 * Represents a device specification
 * @param name the name of the device as used by android's `emulator` command
 * @param platform the platform of the device
 */
private sealed case class Device(name: String, platform: TargetPlatform.Platform) {
  lazy val dir = System.getProperty("user.home") + """\.android\avd\""" + name + """.avd"""
}

/**
 * Supports easy creation of `Device`s based on the specification printed out by `android list avd`.
 */
private object Device {
  private val SpecPattern = """(?s).*Name: (\S+).*ABI: (\S+).*""".r
  private val Arm = """arm.*""".r
  private val X86 = "x86"

  /**
   * Creates a `Device`.
   * @param spec specification of a device as printed out by `android list avd`
   * @return the created device
   * @throws IllegalArgumentException if the platform of the device is unknown
   */
  def apply(spec: String) = {
    spec match {
      case SpecPattern(name, platform) => new Device(name, platform match {
        case Arm() => TargetPlatform.arm
        case X86 => TargetPlatform.x86
        case abi => throw new IllegalArgumentException("Unknown target platform '" + abi + "' for device '" + name + "'")
      })
    }
  }
}

/**
 * Create an instance and call `install` to install the specified scala version on the specified emulator device.
 * @param device the name of the `Device` to install scala on
 * @param scalaVersion the version number of the scala version to be installed
 */
private class Installer(
  private val device: Device,
  private val scalaVersion: String) {

  /**
   * Indicates a failed command line execution (e.g. the process returned an exit code != 0).
   * @param command the command that failed
   * @param exitCode the exit code returned by the command
   */
  private class ExecutionFailedException(val command: String, val exitCode: Int)
    extends Exception("Command '" + command + "' failed with exit code " + exitCode)

  /**
   * Performs the installation.
   */
  def install() {
    println("Installing Scala " + scalaVersion + " for " + device.name + " (" + device.platform + ")")
    val emulatorProcess = startEmulator()
    waitForEmulator()
    makeSystemPartitionWritable()
    pushScalaLibrary()
    pushPermissions()
    createSystemImage()
    copySystemImage()
    done()
    emulatorProcess.destroy()
  }

  //
  // install
  //

  private def startEmulator() = {
    val command = "emulator -avd " + device.name + " -partition-size 1024 -no-boot-anim -no-snapshot"
    printProgressHint("starting emulator ...")
    printCommand(command)
    Process(command).run()
  }

  private def waitForEmulator() {
    adb("wait-for-device", "waiting for emulator ...")
  }

  private def makeSystemPartitionWritable() {
    adbShell("mount -o remount,rw /system", "making system partition writable")
  }

  private def pushScalaLibrary() {
    val targetDir = "/data/framework/scala/" + scalaVersion + "/"
    adbShell("rm -r " + targetDir, "removing existing Scala library")
    adbShell("mkdir -p " + targetDir, "creating Scala library directory")

    printProgressHint("pushing Scala " + scalaVersion + " library")
    new File("scala/" + scalaVersion + "/lib").listFiles().filter(!_.isDirectory).foreach(pushFile(_, targetDir))
  }

  private def permissionFiles = new File("scala/" + scalaVersion + "/permissions").listFiles()

  private def pushPermissions() {
    val targetDir = "/system/etc/permissions/"
    printProgressHint("creating permission files")
    permissionFiles.foreach(pushFile(_, targetDir))
  }

  private def createSystemImage() {
    printProgressHint("creating system image")

    val yaff = "/data/yaff"
    pushFile(new File("tools/mkfs.yaffs2." + device.platform), yaff)
    adbShell("chmod 777 " + yaff)
    adbShell(yaff + " /system /data/system.img")
    adbShell("ls -l /data/system.img")

    printProgressHint("downloading system image from emulator. This will take *several* minutes ...")
    pullFile("/data/system.img")
    adbShell("rm /data/system.img")
  }

  private def copySystemImage() {
    execute("cmd /c copy system.img " + device.dir + """\""", "copying system image to AVD directory")
    execute("cmd /c del system.img")
  }

  private def done() {
    println(
      """
        |You are done now!
        |
        |Restart your emulator and the Scala libraries will be available.
        |
        |Add the following library imports to your AndroidManifest.xml:
      """.stripMargin
    )
    permissionFiles.map(file => file.getName.substring(0, file.getName.lastIndexOf('.'))).foreach{fileName =>
      println("""<uses-library android:name="""" + fileName + """" android:required="true"/>""")
    }
  }

  //
  // helper methods
  //

  /**
   * Executes the specified command line and optionally prints a hint to the console.
   * @param command the command line to be executed
   * @param output the hint to be presented to the user or an empty string if no hint should be shown
   * @throws ExecutionFailedException if the command's return code is != 0
   */
  private def execute(command: String, output: String = "") {
    if (!output.isEmpty)
      printProgressHint(output)
    printCommand(command)

    val exitCode = Process(command).!
    if (exitCode != 0)
      throw new ExecutionFailedException(command, exitCode)
  }

  /**
   * Executes the specified adb command and optionally prints a hint to the console.
   * @param command the adb command to be executed
   * @param output the hint to be presented to the user or an empty string if no hint should be shown
   * @throws ExecutionFailedException if the command's return code is != 0
   */
  private def adb(command: String, output: String = "") {
    execute("adb " + command, output)
  }

  /**
   * Executes the specified command line in the emulator's shell and optionally prints a hint to the console.
   * @param command the command to be executed in the emulator's shell
   * @param output the hint to be presented to the user or an empty string if no hint should be shown
   * @throws ExecutionFailedException if the command's return code is != 0
   */
  private def adbShell(command: String, output: String = "") {
    adb("shell " + command, output)
  }

  /**
   * Pushes the specified file into the emulator.
   * @param srcFile the local file to be pushed
   * @param target the target path of the file in the emulator's file system
   * @throws ExecutionFailedException if adb's return code is != 0
   */
  private def pushFile(srcFile: File, target: String) {
    adb("push \"" + srcFile.getAbsolutePath + "\" " + target)
  }

  /**
   * Pulls the specified file from the emulator. The file is placed into the current working directory.
   * @param path the path of the file in the emulator's file system
   * @throws ExecutionFailedException if adb's return code is != 0
   */
  private def pullFile(path: String) {
    adb("pull \"" + path + "\"")
  }

  /**
   * Prints a hint for the user into the console.
   * @param hint the hint to be presented to the user.
   */
  private def printProgressHint(hint: String) {
    println()
    println("==> " + hint)
  }

  /**
   * Prints the currently executed command into the console.
   * @param command the command to be shown to the user.
   */
  private def printCommand(command: String) {
    println("# " + command)
  }
}

/**
 * Processes and validates the command line arguments and -- if successful -- starts the installation.
 */
private object Installer {
  /**
   * Indicates an invalid command line argument. A user readable error message is provided via `getMessage`.
   * @param name the name of the invalid argument as known by the user
   * @param value the invalid value
   * @param possibleValues the list of allowed values
   */
  private class InvalidArgumentException(
                                          private val name: String,
                                          private val value: String,
                                          private val possibleValues: Set[_]
                                          )
    extends Exception(name + ": invalid value '" + value + "'. Possible values are: " + makePossibleValuesString(possibleValues))

  private lazy val availableScalaVersions = new File("scala").list().toSet
  private lazy val availableDevicesByName = getAvailableDevicesByName
  private lazy val availableDeviceNames = availableDevicesByName.keySet

  /**
   * Processes and validates the command line arguments and -- if successful -- starts the installation.
   * @param args the command line arguments
   */
  def main(args: Array[String]) {
    try {
      args match {
        case Array(deviceName, scalaVersion) =>
          new Installer(validateDevice(deviceName), validateScalaVersion(scalaVersion)).install()
        case _ => printUsageHelp()
      }
    } catch {
      case ex: InvalidArgumentException =>
        println()
        println("ERROR: " + ex.getMessage)
        printUsageHelp()
      case ex: Exception =>
        println(ex)
        printUsageHelp()
    }
  }

  private def validateArgument(name: String, value: String, possibleValues: Set[String]) =
    if (possibleValues.contains(value)) value else throw new InvalidArgumentException(name, value, possibleValues)
  private def validateScalaVersion(scalaVersion: String) =
    validateArgument("scala-version", scalaVersion, availableScalaVersions)
  private def validateDevice(deviceName: String) = availableDevicesByName.get(deviceName) match {
    case Some(device) => device
    case None => throw new InvalidArgumentException("avd", deviceName, availableDeviceNames)
  }

  /**
   * The available `Device`s mapped by their names as provided by the `android list avd` command.
   * @return available `Device`s mapped by their names
   */
  private def getAvailableDevicesByName = {
    val lines = Process("cmd /c android list avd").lines.seq.mkString("\n")
    val specs = lines.split("---------")
    specs.map(Device(_)).map{device => (device.name, device)}.toMap
  }

  /**
   * Formats a list of possible values for a command line argument, so that it can be presented to the user.
   * @param possibleValues the list of possible values.
   * @return a string representing the list of possible values.
   */
  private def makePossibleValuesString(possibleValues: Set[_]) = possibleValues.mkString(", ")

  /**
   * Prints out usage help.
   */
  private def printUsageHelp() {
    println((
      """
        |USAGE: scala install.scala <avd> <scala-version>
        |
        |Installs the scala libraries in the specified emulator to reduce turnaround
        |times when developing with scala for android.
        |
        |<avd>            the name of the android virtual device to install the libs on
        |                 possible values:
        |                 """ + makePossibleValuesString(availableDeviceNames) + """
        |<scala-version>  the scala version to install. possible values:
        |                 """ + makePossibleValuesString(availableScalaVersions) + """
      """).stripMargin)
  }

}

Installer.main(args)