package tptp.thf

import tptp._

/**
 * Created by lex on 3/23/14.
 */
sealed abstract class Formula
case class Logical(formula: LogicFormula) extends Formula {
  override def toString = formula.toString
}
case class Sequent(tuple1: List[LogicFormula], tuple2: List[LogicFormula]) extends Formula {
  override def toString = "[" + tuple1.mkString(",") +"]" + " --> " + "[" + tuple2.mkString(",") + "]"
}

sealed abstract class LogicFormula
case class Typed(formula: LogicFormula, typ: LogicFormula) extends LogicFormula {
  override def toString = formula.toString + " : " + typ.toString
}
case class Binary(left: LogicFormula, connective: BinaryConnective, right: LogicFormula) extends LogicFormula {
  override def toString = "(" + left.toString + ") " + connective.toString + " (" + right.toString + ")"
}
case class Unary(connective: UnaryConnective, formula: LogicFormula) extends LogicFormula {
  override def toString = connective.toString + " (" + formula.toString + ")"
}
case class Quantified(quantifier: Quantifier, varList: List[(Commons.Variable,Option[LogicFormula])], matrix: LogicFormula) extends LogicFormula {
  override def toString = quantifier.toString + " [" + varList.mkString(",") + "] : (" + matrix.toString + ")"
}
case class Connective(c: Either[BinaryConnective, UnaryConnective]) extends LogicFormula
case class Term(t: Commons.Term) extends LogicFormula {
  override def toString = t.toString
}
case class BinType(t: BinaryType) extends LogicFormula {
  override def toString = t.toString
}
case class Subtype(left: String, right: String) extends LogicFormula {
  override def toString = left + " << " + right
}
case class Cond(cond: LogicFormula, thn: LogicFormula, els: LogicFormula) extends LogicFormula{
  override def toString = "$ite_f(" + List(cond,thn,els).mkString(",") + ")"
}
// Let omitted

sealed abstract class BinaryConnective
case object Eq extends BinaryConnective {
  override def toString = "="
}
case object Neq extends BinaryConnective {
  override def toString = "!="
}
case object <=> extends BinaryConnective
case object Impl extends BinaryConnective {
  override def toString = "=>"
}
case object <= extends BinaryConnective
case object <~> extends BinaryConnective
case object ~| extends BinaryConnective
case object ~& extends BinaryConnective
case object | extends BinaryConnective
case object & extends BinaryConnective
case object App extends BinaryConnective {
  override def toString = "@"
}

sealed abstract class UnaryConnective
case object ~ extends UnaryConnective
case object !! extends UnaryConnective
case object ?? extends UnaryConnective

sealed abstract class Quantifier
case object ! extends Quantifier  // All
case object ? extends Quantifier // Exists
case object ^ extends Quantifier // Lambda
case object !> extends Quantifier // Big pi
case object ?* extends Quantifier // Big sigma
case object @+ extends Quantifier // Choice
case object @- extends Quantifier // Description

// type TopType = LogicFormula
sealed abstract class BinaryType
case class ->(t: List[LogicFormula]) extends BinaryType {
  override def toString = "(" + t.mkString(" > ") + ")"
}
case class *(t: List[LogicFormula]) extends BinaryType {
  override def toString = "(" + t.mkString(" * ") + ")"
}
case class +(t: List[LogicFormula]) extends BinaryType {
  override def toString = "(" + t.mkString(" + ") + ")"
}

