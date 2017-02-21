package leo.modules.parsers

import java.io.{BufferedReader, InputStreamReader}
import java.nio.file.{Files, Path, Paths}

import leo.datastructures.tptp.Commons
import leo.datastructures.tptp.thf.{LogicFormula => THFFormula}
import leo.datastructures.{Role, Signature, Term}
import leo.modules.SZSException
import leo.modules.output.SZS_InputError

/**
  * This facade object publishes various methods for parsing/processing/reading
  * of TPTP inputs. The method names are as follows by convention:
  *
  * - parseX: Raw input (e.g. string) -> TPTP AST representation
  * - processX: TPTP AST representation -> Term
  * - readX: Raw input (e.g. string) -> Term
  *
  * @author Alexander Steen <a.steen@fu-berlin.de>
  * @since 29.04.2015
  * @note Updated February 2017: Overhaul
  * @see [[leo.datastructures.tptp]]
  * @see [[leo.datastructures.Term]]
 */
object Input {

  /** Reads the `TPTP` environment variable, e.g. used
    * for parsing objects under TPTP Home. */
  lazy val tptpHome: Path = {
    try {
      canonicalPath(System.getenv("TPTP"))
    } catch {
      case _: Exception => canonicalPath(".")
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // Functions that go from file/string to unprocessed internal TPTP-syntax
  // "String -> TPTP"
  ///////////////////////////////////////////////////////////////////////////

  /**
    * Reads the file located at `file` and parses it recursively using the `TPTP` parser.
    * Note that the return value is a sequence of [[Commons.AnnotatedFormula]] since
    * all includes are automatically parsed exhaustively.
    * If `file` is a relative path, it is assumed to be equivalent to the path
    * `user.dir`/file.
    *
    * @param file  The absolute or relative path to the problem file.
    * @param assumeRead Implicitly assume that the problem files in this parameter
    *                   have already been read. Hence, recursive parsing will skip this
    *                   includes.
    * @return The sequence of annotated TPTP formulae.
    */
  def parseProblem(file: String, assumeRead: Set[Path] = Set()): Seq[Commons.AnnotatedFormula] = {
    val canonicalFile = canonicalPath(file)
    if (!assumeRead.contains(canonicalFile)) {
      val p: Commons.TPTPInput = try {parseShallow(file)} catch {case e1 : Exception =>
        if (tptpHome != null){
//            try {
            val alt = tptpHome.resolve(file)
            parseShallow(alt.toString)
//            } catch {case e : Exception => throw new SZSException(SZS_InputError, s"${e.toString}")}
        } else throw e1
      }
      val includes = p.getIncludes

      // TODO Assume Read should be a shared between the calls (Dependencies between siblings not detected)

      val pIncludes = includes.map{case (inc, _) =>
        try {
          val next = canonicalFile.getParent.resolve(inc)
          parseProblem(next.toString, assumeRead + canonicalFile)
        } catch {
          case _ : Exception =>
            try {
              val tnext = tptpHome.resolve(inc)
              parseProblem(tnext.toString, assumeRead + canonicalFile)
            } catch {
              case _ : Exception => throw new SZSException(SZS_InputError, s"The file $inc does not exist.")
            }
        }
      }
      pIncludes.flatten ++ p.getFormulae
    } else {
      Seq()
    }
  }

  /**
    * Reads the file located at `file`  and shallowly parses it using the `TPTP` parser.
    * Note that include statements are NOT recursively parsed but returned in internal TPTP
    * syntax instead. For recursive parsing of include statements, use [[parseProblem()]].
    * If `file` is a relative path, it is assumed to be equivalent to the path
    * `user.dir`/file.
    *
    * @param file The absolute or relative path to the problem file.
    * @return The TPTP problem file in internal [[Commons.TPTPInput]] representation.
    */
  def parseShallow(file: String): Commons.TPTPInput = {
    TPTP.parseFile(read0(canonicalPath(file)))
  }

  /**
    * Parses the single TPTP syntax formula given by `formula` into internal
    * tptp syntax representation.
    *
    * @param formula The formula to be parsed
    * @return The input formula in internal TPTP syntax representation
    */
  def parseAnnotated(formula: String): Commons.AnnotatedFormula = {
    TPTP.annotatedFormula(formula)
  }

  /**
    * Parses the single THF logic formula (i.e. without annotations)
    * given by `formula` into internal
    * TPTP AST representation.
    *
    * @param formula The formula to be parsed
    * @return The input formula in internal TPTP syntax representation
    */
  def parseFormula(formula: String): THFFormula = {
    TPTP.apply(formula)
  }

  ///////////////////////////////////////////////////////////////////////////
  // Functions that go from internal TPTP syntax to processed internal representation (Term)
  // "TPTP -> Term"
  ///////////////////////////////////////////////////////////////////////////
  type FormulaId = String

  /**
    * Convert the problem given by the formulae in `problem` to internal term representation.
    * SIDE-EFFECTS: Type declarations and definitions within `problem` are added to the signature.
    *
    * Note that the parameter type is `Commons.AnnotatedFormula`, hence
    * all include statements need to be read externally before calling this function.
    *
    * @param problem The input problem in internal TPTP representation.
    * @return A triple `(Id, Clause, Role)` for each `AnnotatedFormula` within
    *         `problem`, such that `Id` is the identifier of the formula, `Clause` is the respective
    *         singleton clause in internal representation, and `Role` is the role of the formula as defined
    *         by the TPTP input.
    *         Note that formulae with role `definition` or `type` are returned as triples
    *         `(Id, Clause($true), Role)` with their respective identifier and role.
    */
  def processProblem(problem: Seq[Commons.AnnotatedFormula])(implicit sig: Signature): Seq[(FormulaId, Term, Role)] = {
    InputProcessing.processAll(sig)(problem)
  }
  /**
    * Convert the `formula` to internal term representation.
    * SIDE-EFFECTS: If `formula` is either a type declaration or a definition,
    * the respective declarations are added to the signature.
    *
    * @param formula The input formula in internal TPTP representation.
    * @return A triple `(Id, Term, Role)`,
    *         such that `Id` is the identifier of the formula, `Clause` is the respective
    *         singleton clause in internal representation, and `Role` is the role of the formula as defined
    *         by the TPTP input.
    *         Note that a formula with role `definition` or `type` is returned as a triple
    *         `(Id, Clause($true), Role)` with its respective identifier and role.
    */
  def processFormula(formula: Commons.AnnotatedFormula)(implicit sig: Signature): (FormulaId, Term, Role) = {
    InputProcessing.process(sig)(formula)
  }

  ///////////////////////////////////////////////////////////////////////////
  // Functions that go from file/string to processed internal representation (Term)
  // "String -> Term"
  ///////////////////////////////////////////////////////////////////////////
  /**
    * Reads and recursively parses the file located at `file` and converts its
    * formulae to internal term presentation.
    * Note that the return value is a sequence of `(FormulaId, Term, Role)` since
    * all includes are automatically parsed and converted exhaustively.
    * If `file` is a relative path, it is assumed to be equivalent to the path
    * `user.dir`/file.
    *
    * SIDE-EFFECTS: Type declarations and definitions within `problem` are added to the signature.
    *
    * @param file  The absolute or relative path to the problem file.
    * @param assumeProcessed Implicitly assume that the problem files in this parameter
    *                   have already been read and processed.
    *                   Hence, recursive parsing will skip this includes.
    * @return A triple `(Id, Term, Role)` for each formula within
    *         the problem, such that `Id` is the identifier of the formula, `Clause` is the respective
    *         singleton clause in internal representation, and `Role` is the role of the formula as defined
    *         by the TPTP input.
    *         Note that formulae with role `definition` or `type` are returned as triples
    *         `(Id, Clause($true), Role)` with their respective identifier and role.
    */
  def readProblem(file: String, assumeProcessed: Set[Path] = Set())(implicit sig: Signature): Seq[(FormulaId, Term, Role)] = {
    processProblem(parseProblem(file,assumeProcessed))(sig)
  }

  /**
    * Reads and parses `formula` and converts it to internal term representation.
    *
    * SIDE-EFFECTS: Type declarations and definitions within `problem` are added to the signature.
    *
    * @param formula The formula to be parsed and converted.
    * @return A triple `(Id, Term, Role)`,
    *         such that `Id` is the identifier of the formula, `Clause` is the respective
    *         singleton clause in internal representation, and `Role` is the role of the formula as defined
    *         by the TPTP input.
    *         Note that a formula with role `definition` or `type` is returned as a triple
    *         `(Id, Clause($true), Role)` with its respective identifier and role.
    */
  def readAnnotated(formula: String)(implicit sig: Signature): (FormulaId, Term, Role) = {
    processFormula(parseAnnotated(formula))(sig)
  }

  def readFormula(formula: String)(implicit sig: Signature): Term = {
    val parsed = TPTP(formula)
    val result = InputProcessing.processTHF(sig)(parsed)
    if (result.isLeft) result.left.get
    else throw new IllegalArgumentException
  }
  def apply(formula: String)(implicit sig: Signature): Term = readFormula(formula)

  final val urlStartRegex:String  = "(\\w+?:\\/\\/)(.*)"
  /** Converts the input path to a canonical path representation */
  def canonicalPath(path: String): Path = {
    if (path.matches(urlStartRegex)) {
      if (path.startsWith("file://")) {
        Paths.get(path.drop("file://".length)).toAbsolutePath.normalize()
      } else {
        Paths.get(path)
      }
    } else {
      Paths.get(path).toAbsolutePath.normalize()
    }
  }

  final val urlStartRegex0:String  = "(\\w+?:\\/)(.*)" // removed one slash because it gets removed by Paths.get(.)
  private def read0(absolutePath: Path): BufferedReader = {
    if (absolutePath.toString.matches(urlStartRegex0)) {
      // URL
      import java.net.URL
      val url = new URL(absolutePath.toString.replaceFirst(":/","://"))
      new BufferedReader(new InputStreamReader(url.openStream()))
    } else {
      if (!Files.exists(absolutePath)) { // It either does not exist or we cant access it
        throw new SZSException(SZS_InputError, s"The file ${absolutePath.toString} does not exist or cannot be read.")
      } else {
        Files.newBufferedReader(absolutePath)
      }
    }
  }

}