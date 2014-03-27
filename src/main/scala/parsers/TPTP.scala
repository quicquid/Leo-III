package parsers

import util.parsing.combinator._
import tptp._
import scala.Some

/**
 * Provides a parsing interface for TPTP files and single formulae.
 * The parser obeys the rules of the TPTP Syntax BNF.
 */
object TPTP {
  // Delegate object, so that no instance of parsers needs to created manually

  /**
   * Parses a complete tptp file yielding a [[Commons.TPTPInput]] value if succeeded.
   *
   * @param input A string containing a tptp file
   * @return A representation of the in file in [[Commons.TPTPInput]] format
   */
  def parseFile(input: String): Option[Commons.TPTPInput] =  parser.exec(input)

  def parseFormula(input: String): Option[Commons.AnnotatedFormula] =  parser.exec(input, parser.annotatedFormula)

  def parseFOF(input: String): Option[Commons.FOFAnnotated] = parser.exec(input, parser.fofAnnotated)
  def parseTHF(input: String): Option[Commons.THFAnnotated] = parser.exec(input, parser.thfAnnotated)
  def parseTFF(input: String): Option[Commons.TFFAnnotated] = parser.exec(input, parser.tffAnnotated)
  def parseCNF(input: String): Option[Commons.CNFAnnotated] = parser.exec(input, parser.cnfAnnotated)
  def parseTPI(input: String): Option[Commons.TPIAnnotated] = parser.exec(input, parser.tpiAnnotated)
  //def parseTFA(input: String): Option[TFA] = parser.exec(input, parser.tfaFormula)

  val parser = new TPTPParser
}

class TPTPParser extends PExec with PackratParsers with JavaTokenParsers {
  override type Target = Commons.TPTPInput
  override def target = tptpFile

  // Defining lexical tokens
  // Character classes
  def doChar: Parser[String] = """[\040-\041\043-\0133\0135-\0176]|[\\]["\\]""".r
  def sqChar: Parser[String] = """[\040-\046\050-\0133\0135-\0176]|[\\]['\\]""".r
  def alphaNumeric: Parser[String] = """[a-zA-Z0-9\_]""".r

  // Other tokens
  def commentLine: Parser[String] = """%.*""".r

  def singleQuoted: Parser[String] = "'" ~> rep1(sqChar) <~ "'" ^^ {_.fold("")((a,b) => a++b)}
  def distinctObject: Parser[String] = "\"" ~> rep(doChar) <~ "\"" ^^ {_.fold("")((a,b) => a++b)}

  def lowerWord: Parser[String] = """[a-z][A-Za-z0-9_]*""".r
  def upperWord: Parser[String] = """[A-Z][A-Za-z0-9_]*""".r
  def dollarWord: Parser[String] ="""\$[a-z][A-Za-z0-9_]*""".r
  def dollarDollarWord: Parser[String] ="""\$\$[a-z][A-Za-z0-9_]*""".r

  def integer: Parser[Int] = wholeNumber ^^ {_.toInt}
  def real: Parser[Double] = floatingPointNumber ^^ {_.toDouble}

  /*
   * Parsing rules
   */

  // Files
  def tptpFile: Parser[Commons.TPTPInput] = rep(tptpInput) ^^ {tptp.Commons.TPTPInput(_)}

  def tptpInput: Parser[Either[Commons.AnnotatedFormula, Commons.Include]] = (annotatedFormula ||| include) ^^ {
    case e1: Commons.AnnotatedFormula => Left(e1)
    case e2: Commons.Include  => Right(e2)
  }

  // Formula records
  def annotatedFormula: Parser[Commons.AnnotatedFormula] =
    tpiAnnotated ||| thfAnnotated ||| tffAnnotated ||| fofAnnotated ||| cnfAnnotated

  def tpiAnnotated: Parser[Commons.TPIAnnotated] =
    "tpi(" ~> name ~ ("," ~> formulaRole <~ ",") ~ fofFormula ~ annotations <~ ")." ^^ {
      case name ~ role ~ formula ~ annotations => Commons.TPIAnnotated(name,role,formula,annotations)
    }
  def thfAnnotated: Parser[Commons.THFAnnotated] =
    "thf(" ~> name ~ ("," ~> formulaRole <~ ",") ~ thfFormula ~ annotations <~ ")." ^^ {
      case name ~ role ~ formula ~ annotations => Commons.THFAnnotated(name,role,formula,annotations)
    }
  def tffAnnotated: Parser[Commons.TFFAnnotated] =
    "tff(" ~> name ~ ("," ~> formulaRole <~ ",") ~ tffFormula ~ annotations <~ ")." ^^ {
      case name ~ role ~ formula ~ annotations => Commons.TFFAnnotated(name,role,formula,annotations)
    }
  def fofAnnotated: Parser[Commons.FOFAnnotated] =
    "fof(" ~> name ~ ("," ~> formulaRole <~ ",") ~ fofFormula ~ annotations <~ ")." ^^ {
      case name ~ role ~ formula ~ annotations => Commons.FOFAnnotated(name,role,formula,annotations)
    }
  def cnfAnnotated: Parser[Commons.CNFAnnotated] =
    "cnf(" ~> name ~ ("," ~> formulaRole <~ ",") ~ cnfFormula ~ annotations <~ ")." ^^ {
      case name ~ role ~ formula ~ annotations => Commons.CNFAnnotated(name,role,formula,annotations)
    }

  def annotations: Parser[tptp.Commons.Annotations] =
    opt("," ~> source ~ optionalInfo) ^^ {
      case None => None
      case Some(src ~ info) => Some((src,info))
    }
  def formulaRole: Parser[String] = lowerWord

  // special formulae
  // futher...

  def folInfixUnary: Parser[Commons.Term ~ Commons.Term] =
    term ~ "!=" ~ term ^^ {
      case l ~ _ ~ r => this.~(l,r)
    }

  // Connectives THF
  def thfQuantifier: Parser[String] =
    folQuantifier ||| "^" ||| "!>" ||| "?*" ||| "@+" ||| "@-"
  def thfPairConnective: Parser[String] =
    "=" ||| "!=" ||| binaryConnective
  def thfUnaryConnective: Parser[String] = unaryConnective ||| "!!" ||| "??"

  // Connectives TFF and THF
  def subtypeSign: Parser[String] = "<<"

  // Connectives FOF
  def folQuantifier: Parser[String] = "!" | "?"
  def binaryConnective: Parser[String] =
    "<=>" | "=>" | "<=" | "<~>" | "~|" | "~&"
  def assocConnective: Parser[String] =
    "|" | "&"
  def unaryConnective: Parser[String] = "~"

  // Gentzen arrow
  def gentzenArrow: Parser[String] = "-->"

  // Types for tff and thf
  def definedType: Parser[String] = atomicDefinedWord
  def systemType: Parser[String] = atomicSystemWord

  // First-order atoms
  def atomicFormula: Parser[Commons.AtomicFormula] =
    plainAtomicFormula ||| definedPlainFormula ||| definedInfixFormula ||| systemAtomicFormula

  def plainAtomicFormula: Parser[Commons.Plain] = plainTerm ^^ {Commons.Plain(_)}
  def definedPlainFormula: Parser[Commons.DefinedPlain] = definedPlainTerm ^^ {Commons.DefinedPlain(_)}
  def definedInfixFormula: Parser[Commons.Equality] =
    term ~ "=" ~ term ^^ {
      case t1 ~ "=" ~ t2 => Commons.Equality(t1,t2)
    }
  def systemAtomicFormula: Parser[Commons.SystemPlain] = systemTerm ^^ {Commons.SystemPlain(_)}

  // First-order terms
  def term: Parser[Commons.Term] = functionTerm |||
    variable ^^ {Commons.Var(_)} |||
    conditionalTerm |||
    letTerm

  def functionTerm: Parser[Commons.Term] =
    plainTerm |||
    definedPlainTerm |||
    systemTerm |||
    number ^^ {Commons.Number(_)} |||
    distinctObject ^^ {Commons.Distinct(_)}

  def plainTerm: Parser[Commons.Func] =
    constant ~ opt("(" ~> arguments <~ ")") ^^ {
      case c ~ Some(x) => Commons.Func(c,x)
      case c ~ _       => Commons.Func(c,List())
    }

  def constant: Parser[String] = atomicWord
  def definedPlainTerm: Parser[Commons.DefinedFunc] =
    atomicDefinedWord ~ opt("(" ~> arguments <~ ")") ^^ {
      case c ~ Some(x) => Commons.DefinedFunc(c,x)
      case c ~ _       => Commons.DefinedFunc(c,List())
    }
  def systemTerm: Parser[Commons.SystemFunc] =
    atomicSystemWord ~ opt("(" ~> arguments <~ ")") ^^ {
      case c ~ Some(x) => Commons.SystemFunc(c,x)
      case c ~ _       => Commons.SystemFunc(c,List())
    }

  def variable: Parser[Commons.Variable] = upperWord
  def arguments: Parser[List[Commons.Term]] = rep1sep(term, ",")
  def conditionalTerm: Parser[Commons.Cond] =
    "$ite_t(" ~> tffLogicFormula ~ "," ~ term ~ "," ~ term <~ ")" ^^ {
      case formula ~ "," ~ thn ~ "," ~ els => Commons.Cond(formula,thn,els)
    }
  def letTerm: Parser[Commons.Let] =
    "$let_ft(" ~> (tffLetFormulaDefn ||| tffLetTermDefn) ~ "," ~ term <~ ")" ^^ {
      case lets ~ "," ~ in => Commons.Let(lets,in)
    }

  // Formula sources and infos
  def source: Parser[Commons.GeneralTerm] = generalTerm
  def optionalInfo: Parser[List[Commons.GeneralTerm]] =
    "^$".r ^^ {_ => List.empty} ||| "," ~> usefulInfo

  def usefulInfo: Parser[List[Commons.GeneralTerm]] = generalList

  // Include directives
  def include: Parser[tptp.Commons.Include] =
    "include(" ~> singleQuoted ~ opt(",[" ~> repsep(name,",") <~"]") <~ ")" ^^ {
      case name ~ Some(names) => (name, names)
      case name ~ _           => (name, List.empty)
    }
  // Non-logical data (GeneralTerm, General data)
  def generalTerm: Parser[tptp.Commons.GeneralTerm] =
    generalList ^^ {x => Commons.GeneralTerm(List(Right(x)))} |||
    generalData ^^ {x => Commons.GeneralTerm(List(Left(x)))} |||
    generalData ~ ":" ~ generalTerm ^^ {
      case data ~ ":" ~ gterm => Commons.GeneralTerm(Left(data) :: gterm.term)
    }

  def generalData: Parser[tptp.Commons.GeneralData] =
    atomicWord ^^ {tptp.Commons.GWord(_)} |||
      generalFunction |||
      variable ^^ {tptp.Commons.GVar(_)} |||
      number ^^ {tptp.Commons.GNumber(_)} |||
      distinctObject ^^ {tptp.Commons.GDistinct(_)} |||
      formulaData ^^ {tptp.Commons.GFormulaData(_)}

  def generalFunction: Parser[tptp.Commons.GFunc] = atomicWord ~ "(" ~ generalTerms ~ ")" ^^ {
    case name ~ "(" ~ args ~ ")"  => tptp.Commons.GFunc(name,args)
  }

  def formulaData: Parser[tptp.Commons.FormulaData] =
    "$thf(" ~> thfFormula <~ ")" ^^ {tptp.Commons.THFData(_)} |||
      "$tff(" ~> tffFormula <~ ")" ^^ {tptp.Commons.TFFData(_)} |||
      "$fof(" ~> fofFormula <~ ")" ^^ {tptp.Commons.FOFData(_)} |||
      "$cnf(" ~> cnfFormula <~ ")" ^^ {tptp.Commons.CNFData(_)} |||
      "$fot(" ~> term <~ ")" ^^ {tptp.Commons.FOTData(_)}

  def generalList: Parser[List[tptp.Commons.GeneralTerm]] =
    "[" ~> opt(generalTerms) <~ "]" ^^ {
      case Some(gt)   => gt
      case _       => List.empty
    }
  def generalTerms: Parser[List[tptp.Commons.GeneralTerm]] = rep1sep(generalTerm, ",")

  // General purpose
  def name: Parser[Either[String, Int]] = atomicWord ^^ {Left(_)} ||| integer ^^ {Right(_)}
  def atomicWord: Parser[String] = lowerWord ||| singleQuoted
  def atomicDefinedWord: Parser[String] = dollarWord
  def atomicSystemWord: Parser[String] = dollarDollarWord
  def number: Parser[Double] = integer ^^ {_.toDouble} ||| real

  def fileName: Parser[String] = singleQuoted

  /**
   * THF BNFs
   */
  def thfFormula: Parser[thf.Formula] = ???

  /**
   * TFF BNFs
   */
  def tffFormula: Parser[tff.Formula] = ???
  def tffLogicFormula: Parser[tff.Formula] = ???

  def tffLetFormulaDefn: Parser[tff.Formula] = ???
  def tffLetTermDefn: Parser[tff.Formula] = ???

  /**
   * FOF BNFs
   */
  def fofFormula: Parser[fof.Formula] = fofLogicFormula ^^ {fof.Logical(_)} ||| fofSequent

  def fofLogicFormula: Parser[fof.LogicFormula] = fofBinaryFormula ||| fofUnitaryFormula

  def fofBinaryFormula: Parser[fof.Binary] = fofBinaryNonAssoc ||| fofBinaryAssoc
  def fofBinaryNonAssoc: Parser[fof.Binary] = fofUnitaryFormula ~ binaryConnective ~ fofUnitaryFormula ^^ {
    case left ~ "<=>" ~ right => fof.Binary(left,fof.<=>,right)
    case left ~ "=>" ~ right => fof.Binary(left,fof.Impl,right)
    case left ~ "<=" ~ right => fof.Binary(left,fof.<=,right)
    case left ~ "<~>" ~ right => fof.Binary(left,fof.<~>,right)
    case left ~ "~|" ~ right => fof.Binary(left,fof.~|,right)
    case left ~ "~&" ~ right => fof.Binary(left,fof.~&,right)
  }
  def fofBinaryAssoc: Parser[fof.Binary] = fofOrFormula ||| fofAndFormula
  lazy val fofOrFormula: PackratParser[fof.Binary] = fofUnitaryFormula ~ "|" ~ fofUnitaryFormula ^^ {
    case left ~ "|" ~ right => fof.Binary(left,fof.|,right)
  } |||
  fofOrFormula ~ "|" ~ fofUnitaryFormula ^^ {
    case left ~ "|" ~ right => fof.Binary(left,fof.|,right)
  }
  lazy val fofAndFormula: PackratParser[fof.Binary] = fofUnitaryFormula ~ "&" ~ fofUnitaryFormula ^^ {
    case left ~ "&" ~ right => fof.Binary(left,fof.&,right)
  } |||
  fofAndFormula ~ "&" ~ fofUnitaryFormula ^^ {
    case left ~ "&" ~ right => fof.Binary(left,fof.&,right)
  }

  def fofUnitaryFormula: Parser[fof.LogicFormula] = "(" ~> fofLogicFormula <~ ")" | fofQuantifiedFormula | fofUnaryFormula |
    atomicFormula ^^ {fof.Atomic(_)}

  def fofQuantifiedFormula: Parser[fof.Quantified] =
    folQuantifier ~ "[" ~ rep1sep(variable,",") ~ "]" ~ ":" ~ fofUnitaryFormula ^^ {
      case "!" ~ "[" ~ vars ~ "]" ~ ":" ~ matrix => fof.Quantified(fof.Forall,vars,matrix)
      case "?" ~ "[" ~ vars ~ "]" ~ ":" ~ matrix => fof.Quantified(fof.Exists,vars,matrix)
    }
  def fofUnaryFormula: Parser[fof.LogicFormula] = unaryConnective ~ fofUnitaryFormula ^^ {
    case "~" ~ formula => fof.Unary(fof.Not, formula)
  } | folInfixUnary ^^ {
    case left ~ right => fof.Inequality(left,right)
  }

  def fofSequent: Parser[fof.Sequent] =
    fofTuple ~ gentzenArrow ~ fofTuple ^^ {
      case t1 ~ _ ~ t2 => fof.Sequent(t1,t2)
    } ||| "(" ~> fofSequent <~ ")"
  def fofTuple: Parser[List[fof.LogicFormula]] =
    "[" ~> repsep(fofLogicFormula, ",") <~ "]"

  /**
   * CNF formula BNFs
   */
  def cnfFormula: Parser[cnf.Formula] =
    ("(" ~> disjunction <~ ")" ||| disjunction) ^^ {cnf.Formula(_)}
  def disjunction: Parser[List[cnf.Literal]] =
    rep1sep(literal, "|")
  def literal: Parser[cnf.Literal] =
    atomicFormula ^^ {cnf.Positive(_)} |||
    "~" ~> atomicFormula ^^ {cnf.Negative(_)} |||
    folInfixUnary ^^ {
      case left ~ right => cnf.Inequality(left,right)
    }

}
