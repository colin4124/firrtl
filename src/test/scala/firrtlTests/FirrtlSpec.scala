// See LICENSE for license details.

package firrtlTests

import java.io._

import com.typesafe.scalalogging.LazyLogging
import scala.sys.process._
import org.scalatest._
import org.scalatest.prop._
import scala.io.Source

import firrtl._
import firrtl.Parser.IgnoreInfo
import firrtl.annotations
import firrtl.util.BackendCompilationUtilities

trait FirrtlRunners extends BackendCompilationUtilities {
  lazy val cppHarness = new File(s"/top.cpp")
  /** Compiles input Firrtl to Verilog */
  def compileToVerilog(input: String, annotations: AnnotationMap = AnnotationMap(Seq.empty)): String = {
    val circuit = Parser.parse(input.split("\n").toIterator)
    val compiler = new VerilogCompiler
    val res = compiler.compileAndEmit(CircuitState(circuit, HighForm, Some(annotations)))
    res.getEmittedCircuit.value
  }
  /** Compile a Firrtl file
    *
    * @param prefix is the name of the Firrtl file without path or file extension
    * @param srcDir directory where all Resources for this test are located
    * @param annotations Optional Firrtl annotations
    */
  def compileFirrtlTest(
      prefix: String,
      srcDir: String,
      customTransforms: Seq[Transform] = Seq.empty,
      annotations: AnnotationMap = new AnnotationMap(Seq.empty)): File = {
    val testDir = createTestDirectory(prefix)
    copyResourceToFile(s"${srcDir}/${prefix}.fir", new File(testDir, s"${prefix}.fir"))

    val optionsManager = new ExecutionOptionsManager(prefix) with HasFirrtlOptions {
      commonOptions = CommonOptions(topName = prefix, targetDirName = testDir.getPath)
      firrtlOptions = FirrtlExecutionOptions(
                        infoModeName = "ignore",
                        customTransforms = customTransforms,
                        annotations = annotations.annotations.toList)
    }
    firrtl.Driver.execute(optionsManager)

    testDir
  }
  /** Execute a Firrtl Test
    *
    * @param prefix is the name of the Firrtl file without path or file extension
    * @param srcDir directory where all Resources for this test are located
    * @param verilogPrefixes names of option Verilog resources without path or file extension
    * @param annotations Optional Firrtl annotations
    */
  def runFirrtlTest(
      prefix: String,
      srcDir: String,
      verilogPrefixes: Seq[String] = Seq.empty,
      customTransforms: Seq[Transform] = Seq.empty,
      annotations: AnnotationMap = new AnnotationMap(Seq.empty)) = {
    val testDir = compileFirrtlTest(prefix, srcDir, customTransforms, annotations)
    val harness = new File(testDir, s"top.cpp")
    copyResourceToFile(cppHarness.toString, harness)

    // Note file copying side effect
    val verilogFiles = verilogPrefixes map { vprefix =>
      val file = new File(testDir, s"$vprefix.v")
      copyResourceToFile(s"$srcDir/$vprefix.v", file)
      file
    }

    verilogToCpp(prefix, testDir, verilogFiles, harness).!
    cppToExe(prefix, testDir).!
    assert(executeExpectingSuccess(prefix, testDir))
  }
}

trait FirrtlMatchers extends Matchers {
  // Replace all whitespace with a single space and remove leading and
  //   trailing whitespace
  // Note this is intended for single-line strings, no newlines
  def normalized(s: String): String = {
    require(!s.contains("\n"))
    s.replaceAll("\\s+", " ").trim
  }
  def parse(str: String) = Parser.parse(str.split("\n").toIterator, IgnoreInfo)
  /** Helper for executing tests
    * compiler will be run on input then emitted result will each be split into
    * lines and normalized.
    */
  def executeTest(input: String, expected: Seq[String], compiler: Compiler) = {
    val finalState = compiler.compileAndEmit(CircuitState(parse(input), ChirrtlForm))
    val lines = finalState.getEmittedCircuit.value split "\n" map normalized
    for (e <- expected) {
      lines should contain (e)
    }
  }
}

abstract class FirrtlPropSpec extends PropSpec with PropertyChecks with FirrtlRunners with LazyLogging

abstract class FirrtlFlatSpec extends FlatSpec with FirrtlRunners with FirrtlMatchers with LazyLogging

/** Super class for execution driven Firrtl tests */
abstract class ExecutionTest(name: String, dir: String, vFiles: Seq[String] = Seq.empty) extends FirrtlPropSpec {
  property(s"$name should execute correctly") {
    runFirrtlTest(name, dir, vFiles)
  }
}
/** Super class for compilation driven Firrtl tests */
abstract class CompilationTest(name: String, dir: String) extends FirrtlPropSpec {
  property(s"$name should compile correctly") {
    compileFirrtlTest(name, dir)
  }
}


