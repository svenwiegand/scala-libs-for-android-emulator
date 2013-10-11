import java.io.{FileReader, File}
import java.nio.file.Files
import scala.language.implicitConversions
import scala.io.Source
import sys.process.Process

/**
 * Create an instance and call `install` to install the specified scala version on the specified emulator device.
 * @param scalaVersion the version number of the scala version to be installed
 */
private class Installer(private val scalaVersion: String) {

  /**
   * Indicates a failed command line execution (e.g. the process returned an exit code != 0).
   * @param command the command that failed
   * @param exitCode the exit code returned by the command
   */
  private class ExecutionFailedException(val command: String, val exitCode: Int)
    extends Exception("Command '" + command + "' failed with exit code " + exitCode)

  implicit def file2Path(file: File) = file.toPath

  /**
   * Performs the installation.
   */
  def install() {
    println("Installing Scala " + scalaVersion + " for currently runny genymotion device")

    makeSystemPartitionWritable()
    pushScalaLibrary()
    pushPermissions()
    makeSystemPartitionReadable()
    done()
  }

  //
  // install
  //

  private def makeSystemPartitionWritable() {
    sudo("mount -o remount,rw /system", "making system partition writable")
  }

  private def makeSystemPartitionReadable() {
    sudo("mount -o remount,ro /system", "making system partition readable")
  }

  private def pushScalaLibrary() {
    val targetDir = "/system/framework/scala/" + scalaVersion + "/"
    sudo("rm -r " + targetDir, "removing existing Scala library")
    sudo("mkdir -p " + targetDir, "creating Scala library directory")

    printProgressHint("pushing Scala " + scalaVersion + " library")
    new File("scala/" + scalaVersion + "/lib").listFiles().filter(!_.isDirectory).foreach(pushFile(_, targetDir))
  }

  private def permissionFiles = new File("scala/" + scalaVersion + "/permissions").listFiles()

  private def pushPermissions() {
    val targetDir = "/system/etc/permissions/"
    printProgressHint("creating permission files")
    sudo("chmod 777 /system/etc/permissions", "Allowing write access to /system/etc/permissions")
    permissionFiles.foreach(pushFile(_, targetDir))
    sudo("chmod 755 /system/etc/permissions", "Permitting write access to /system/etc/permissions")
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
   * Executes the specified command line in the emulator's shell as a sudo command and optionally prints a hint to the console.
   * @param command the command to be executed in the emulator's shell
   * @param output the hint to be presented to the user or an empty string if no hint should be shown
   * @throws ExecutionFailedException if the command's return code is != 0
   */
  private def sudo(command: String, output: String = "") {
    adbShell("su -c \"" + command + "\"", output)
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

  /**
   * Processes and validates the command line arguments and -- if successful -- starts the installation.
   * @param args the command line arguments
   */
  def main(args: Array[String]) {
    try {
      args match {
        case Array(scalaVersion) =>
          new Installer(validateScalaVersion(scalaVersion)).install()
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
        |USAGE: scala install-genymotion.scala <scala-version>
        |
        |Installs the scala libraries in the currently running genymotion device to
        |reduce turnaround times when developing with scala for android.
        |
        |<scala-version>  the scala version to install. possible values:
        |                 """ + makePossibleValuesString(availableScalaVersions) + """
      """).stripMargin)
  }

}

Installer.main(args)