package leo.datastructures.term

import leo.datastructures._
import leo.datastructures.impl.Signature

import scala.language.implicitConversions


/**
 * Abstract interface for terms and operations on them that can be
 * done in the internal language.
 * Terms are generated by
 *
 * {{{s,t ::= i (bound symbol)
 *       | c (constant symbol)
 *       | λ:tau.s (term abstraction)
 *       | s t (term application)
 *       | Λs (type abstraction)
 *       | s tau (type application)}}}
 *
 * where `c` is some symbol (constant) and `tau` is a type (see `Type`).
 *
 * @author Alexander Steen
 * @since 21.05.2014
 * @note Updated 02.06.2014 Cleaned up method set, lambda terms always have types
 * @note Updated 09.06.2014 Added pattern matcher for terms, added definition expansion
 */
abstract class Term extends Ordered[Term] with Pretty {

  protected var _locality: Locality = LOCAL
  protected[term] var _indexing: Indexing = PLAIN  // INDEXED implies GLOBAL

  def locality = _locality
  def indexing = _indexing
  def makeGlobal: Term = ???
  def makeLocal: Term = ???

  def compare(that: Term): Int = SenselessOrdering.compare(this, that)

  // Predicates on terms
  def isAtom: Boolean
  def isTermAbs: Boolean
  def isTypeAbs: Boolean
  def isApp: Boolean

  // Handling def. expansion
  def δ_expandable: Boolean
  def partial_δ_expand(rep: Int): Term
  def full_δ_expand: Term

  def head_δ_expandable: Boolean
  def head_δ_expand: Term

  // Queries on terms
  def ty: Type
  def freeVars: Set[Term]
  def boundVars: Set[Term]
  def occurrences: Map[Term, Set[Position]]
  def symbols: Set[Signature#Key]
  def symbolsOfType(ty: Type) = {
    val sig = Signature.get
    symbols.filter({i => sig(i)._ty == ty})
  }
  def headSymbol: Term
  def scopeNumber: (Int,Int)
  def size: Int
  def langOrder: LangOrder

  // Substitutions
  /** Replace every occurrence of `what` in `this` by `by`. */
  def replace(what: Term, by: Term): Term
  def replaceAt(at: Position, by: Term): Term

  def substitute(what: Term, by: Term): Term
  def substitute(what: List[Term], by: List[Term]): Term = {
    require(what.length == by.length, "Substitution list do not match in length.")
    what.zip(by).foldRight(this)({case ((w,b), t:Term) => t.substitute(w,b)})
  }

  protected[datastructures] def instantiateBy(by: Type) = instantiate(1,by)
  protected[datastructures] def instantiate(scope: Int, by: Type): Term
//  protected[internal] def instantiateWith(subst: Subst): Term

  // Other operations
  /** Returns true iff the term is well-typed. */
  def typeCheck: Boolean

  /** Return the β-nf of the term */
  def betaNormalize: Term
  protected[term] def normalize(termSubst: Subst, typeSubst: Subst): Term

  /** Right-folding on terms. */
  def foldRight[A](symFunc: Signature#Key => A)
             (boundFunc: (Type, Int) => A)
             (absFunc: (Type, A) => A)
             (appFunc: (A,A) => A)
             (tAbsFunc: A => A)
             (tAppFunc: (A, Type) => A): A
//
//  def expandDefinitions(rep: Int): Term
//  def expandAllDefinitions = expandDefinitions(-1)

  protected[datastructures] def inc(scopeIndex: Int): Term
  def closure(subst: Subst): Term
//  protected[internal] def weakEtaContract(under: Subst, scope: Int): Term
}



/////////////////////////////
// Companion factory object
/////////////////////////////


/**
 * Term Factory object. Only this class is used to create new terms.
 *
 * Current default term implementation: [[leo.datastructures.term.spine.TermImpl]]
 */
object Term extends TermBank {
  import leo.datastructures.term.spine.TermImpl

  // Factory method delegation
  def mkAtom(id: Signature#Key): Term = TermImpl.mkAtom(id)
  def mkBound(t: Type, scope: Int): Term = TermImpl.mkBound(t,scope)
  def mkTermApp(func: Term, arg: Term): Term = TermImpl.mkTermApp(func, arg)
  def mkTermApp(func: Term, args: Seq[Term]): Term = TermImpl.mkTermApp(func, args)
  def mkTermAbs(t: Type, body: Term): Term = TermImpl.mkTermAbs(t, body)
  def mkTypeApp(func: Term, arg: Type): Term = TermImpl.mkTypeApp(func, arg)
  def mkTypeApp(func: Term, args: Seq[Type]): Term = TermImpl.mkTypeApp(func, args)
  def mkTypeAbs(body: Term): Term = TermImpl.mkTypeAbs(body)
  def mkApp(func: Term, args: Seq[Either[Term, Type]]): Term = TermImpl.mkApp(func, args)

  // Term index method delegation
  val local = TermImpl.local
  def insert0(localTerm: Term): Term = TermImpl.insert0(localTerm)
  def reset(): Unit = TermImpl.reset()

  // Further utility functions
  /** Convert tuple (i,ty) to according de-Bruijn index */
  implicit def intToBoundVar(in: (Int, Type)): Term = mkBound(in._2,in._1)
  /** Convert tuple (i,j) to according de-Bruijn index (where j is a type-de-Bruijn index) */
  implicit def intsToBoundVar(in: (Int, Int)): Term = mkBound(in._2,in._1)
  /** Convert a signature key to its corresponding atomic term representation */
  implicit def keyToAtom(in: Signature#Key): Term = mkAtom(in)


  type TermBankStatistics = (Int, Int, Int, Int, Int, Int, Map[Int, Int])
  def statistics: TermBankStatistics = TermImpl.statistics

  //////////////////////////////////////////
  // Patterns for term structural matching
  //////////////////////////////////////////

  import leo.datastructures.term.spine.Spine.{nil => SNil}
  import leo.datastructures.term.spine.{Atom, BoundIndex, Redex, Root}

  /**
   * Pattern for matching bound symbols in terms (i.e. De-Bruijn-Indices). Usage:
   * {{{
   * t match {
   *  case Bound(ty,scope) => println("Matched bound symbol of lambda-scope "
   *                                  + scope.toString + " with type "+ ty.pretty)
   *  case _               => println("something else")
   * }
   * }}}
   */
  object Bound {
    def unapply(t: Term): Option[(Type, Int)] = t match {
      case naive.BoundNode(ty,scope) => Some((ty,scope))
      case spine.Root(BoundIndex(ty, scope), SNil) => Some((ty, scope))
      case _ => None
    }
  }

  /**
   * Pattern for matching constant symbols in terms (i.e. symbols in signature). Usage:
   * {{{
   * t match {
   *  case Symbol(constantKey) => println("Matched constant symbol "+ constantKey.toString)
   *  case _                   => println("something else")
   * }
   * }}}
   */
  object Symbol {

    def unapply(t: Term): Option[Signature#Key] = t match {
      case naive.SymbolNode(k)         => Some(k)
      case spine.Root(Atom(k),SNil) => Some(k)
      case _ => None
    }
  }

  /**
   * Pattern for matching (term) applications in terms (i.e. terms of form `(s t)`). Usage:
   * {{{
   * t match {
   *  case s @@@ t => println("Matched application. Left: " + s.pretty
   *                                            + " Right: " + t.pretty)
   *  case _       => println("something else")
   * }
   * }}}
   */
  object @@@ extends HOLBinaryConnective {
    val key = Integer.MIN_VALUE // just for fun!
    override def unapply(t: Term): Option[(Term,Term)] = t match {
        case naive.ApplicationNode(l,r) => Some((l,r))
        case _ => None
      }
    override def apply(left: Term, right: Term): Term = Term.mkTermApp(left,right)
  }

  /**
   * Pattern for matching a root/redex term (i.e. terms of form `(f ∙ S)`). Usage:
   * {{{
   * t match {
   *  case s ∙ args => println("Matched application. Head: " + s.pretty
   *                                            + " Args: " + args.map.fold(_.pretty,_.pretty)).toString
   *  case _       => println("something else")
   * }
   * }}}
   */
  object ∙ {
    def unapply(t: Term): Option[(Term, Seq[Either[Term, Type]])] = t match {
      case Root(h, sp) => Some((spine.TermImpl.headToTerm(h), sp.asTerms))
      case Redex(expr, sp) => Some((expr, sp.asTerms))
      case _ => None
    }

    def apply(left: Term, right: Seq[Either[Term, Type]]): Term = spine.TermImpl.mkApp(left, right)
  }

  /**
   * Pattern for matching type applications in terms (i.e. terms of form `(s ty)` where `ty` is a type). Usage:
   * {{{
   * t match {
   *  case s :::: ty => println("Matched type application. Left: " + s.pretty
   *                                                  + " Right: " + ty.pretty)
   *  case _         => println("something else")
   * }
   * }}}
   */
  object @@@@ {

    def unapply(t: Term): Option[(Term,Type)] = t match {
      case naive.TypeApplicationNode(l,r) => Some((l,r))
      case _ => None
    }
  }

  /**
   * Pattern for matching (term) abstractions in terms (i.e. terms of form `(\(ty)(s))` where `ty` is a type). Usage:
   * {{{
   * t match {
   *  case ty :::> s => println("Matched abstraction. Type of parameter: " + ty.pretty
   *                                                           + " Body: " + s.pretty)
   *  case _         => println("something else")
   * }
   * }}}
   */
  object :::> extends Function2[Type, Term, Term] {

    def unapply(t: Term): Option[(Type,Term)] = t match {
      case naive.AbstractionNode(ty,body) => Some((ty,body))
      case spine.TermAbstr(ty, body)      => Some((ty, body))
      case _ => None
    }

    /** Construct abstraction λty.body */
    override def apply(ty: Type, body: Term): Term = Term.mkTermAbs(ty, body)
  }

  /**
   * Pattern for matching (type) abstractions in terms (i.e. terms of form `/\(s)`). Usage:
   * {{{
   * t match {
   *  case TypeLambda(s) => println("Matched type abstraction. Body: " + s.pretty)
   *  case _             => println("something else")
   * }
   * }}}
   */
  object TypeLambda {

    def unapply(t: Term): Option[Term] = t match {
      case naive.TypeAbstractionNode(body) => Some(body)
      case spine.TypeAbstr(body)           => Some(body)
      case _ => None
    }
  }
}

