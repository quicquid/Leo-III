package leo.datastructures.impl

import leo.datastructures.Position.HeadPos
import leo.datastructures.Type._
import leo.datastructures._

import scala.annotation.tailrec
import scala.language.implicitConversions


///////////////////////////////////////////////
// Shared-implementation based specialization
// of term interface
///////////////////////////////////////////////

/**
 * Abstract implementation class for DAG-based shared terms
 * in spine notation.
 *
 * @author Alexander Steen
 * @since 04.08.2014
 */
protected[datastructures] sealed abstract class TermImpl(protected[TermImpl] var normal: Boolean = false,
                                                         protected[TermImpl] var etanormal: Boolean = false) extends Term {
  // Predicates on terms
  protected[TermImpl] var _sharing: Term#Sharing = false
  final def sharing: Sharing = _sharing

  final def flexHead: Boolean = flexHead0(0)
  protected[impl] def flexHead0(depth: Int): Boolean

  protected[datastructures] def normalize(termSubst: Subst, typeSubst: Subst): TermImpl
  final def betaNormalize: Term = {
    if (normal) this
    else {
      val erg = normalize(Subst.id, Subst.id)
      erg.markBetaNormal()
      if (sharing)
        TermImpl.insert0(erg)
      else
        erg
    }
  }

  final def etaExpand: Term = {
    if (etanormal) this
    else {
      val betanf = this.betaNormalize.asInstanceOf[TermImpl]
      val res = betanf.etaExpand0
      res.markBetaEtaNormal()
      res
    }
  }
  protected[datastructures] def etaExpand0: TermImpl

  final def isBetaNormal: Boolean = normal
  protected[impl] def markBetaNormal(): Unit
  protected[impl] def markBetaEtaNormal(): Unit

  final def closure(termSubst: Subst, typeSubst: Subst) = TermClos(this, (termSubst, typeSubst))
  final def termClosure(subst: Subst) = TermClos(this, (subst, Subst.id))
  final def typeClosure(tySubst: Subst) = TermClos(this, (Subst.id, tySubst))
//    this.normalize(subst, Subst.id)

  // Substitutions

  // Other
  final lazy val symbols: Multiset[Signature#Key] = Multiset.fromMap(symbolMap.mapValues(_._1))

  // FV Indexing utility
  type Count = Int
  type Depth = Int
  protected[impl] def symbolMap: Map[Signature#Key, (Count, Depth)]
  @inline final private def fuseSymbolMapFunction(a: (Count, Depth), b: (Count, Depth)) = (a._1 + b._1, Math.max(a._2, b._2))
  final protected[impl] def fuseSymbolMap(map1: Map[Signature#Key, (Count, Depth)], map2: Map[Signature#Key, (Count, Depth)]): Map[Signature#Key, (Count, Depth)] = mergeMapsBy(map1,map2, fuseSymbolMapFunction)(0,0)

  @inline final def fvi_symbolFreqOf(symbol: Signature#Key): Int = symbolMap.getOrElse(symbol, (0,0))._1
  @inline final def fvi_symbolDepthOf(symbol: Signature#Key): Int = symbolMap.getOrElse(symbol, (0,0))._2
}

/////////////////////////////////////////////////
// Implementation of specific term constructors
/////////////////////////////////////////////////

/** Representation of terms that are in (weak) head normal form. */
protected[impl] case class Root(hd: Head, args: Spine) extends TermImpl {
  import TermImpl.{headToTerm, mkRedex, mkRoot}

  final protected[impl] def markBetaNormal(): Unit = {
    this.normal = true
    args.markBetaNormal()
  }
  final protected[impl] def markBetaEtaNormal(): Unit = {
    this.normal = true; this.etanormal = true
    args.markBetaEtaNormal()
  }

  // Predicates on terms
  final val isAtom = args == SNil
  final val isConstant = isAtom && hd.isConstant
  final val isVariable = isAtom && !hd.isConstant
  final val isTermAbs = false
  final val isTypeAbs = false
  final val isApp = args != SNil

  final protected[impl] def flexHead0(depth: Int): Boolean = hd match {
    case BoundIndex(_, scope) => scope > depth
    case _ => false
  }

  // Handling def. expansion
  final def δ_expandable(implicit sig: Signature) = hd.δ_expandable(sig) || args.δ_expandable(sig)
  final def δ_expand(rep: Int)(implicit sig: Signature) = mkRedex(hd.δ_expand(rep)(sig), args.δ_expand(rep)(sig))
  final def δ_expand(implicit sig: Signature) = mkRedex(hd.δ_expand(sig), args.δ_expand(sig))
  final def δ_expand_upTo(symbs: Set[Signature#Key])(implicit sig: Signature): Term = hd match {
    case Atom(key,_) if !symbs.contains(key) => {
      val meta = sig(key)
      if (meta.hasDefn) {
        mkRedex(meta._defn.δ_expand_upTo(symbs)(sig), args.δ_expand_upTo(symbs)(sig))
      } else {
        mkRoot(hd, args.δ_expand_upTo(symbs)(sig))
      }
    }
    case _ => mkRoot(hd, args.δ_expand_upTo(symbs)(sig))
  }

  // Queries on terms
  lazy val ty = ty0(hd.ty, args)
  private def ty0(funty: Type, s: Spine): Type = s match {
    case SNil => funty
    case App(s0,tail) => funty match {
      case (t -> out) if t.isProdType => ty0(out, s.drop(t.numberOfComponents))
      case (_ -> out) => ty0(out, tail)
      case _ => throw NotWellTypedException(this) // this should not happen if well-typed
    }
    case TyApp(s0,tail) => funty match {
      case tt@(∀(body)) => ty0(tt.instantiate(s0), tail)
      case _ => throw NotWellTypedException(this) // this should not happen if well-typed
    }
    case _ => throw new IllegalArgumentException("closure occured in term")// other cases do not apply
  }
  lazy val fv: Set[(Int, Type)] = hd match {
    case BoundIndex(ty,i) => args.fv + ((i, ty))
    case _ => args.fv
  }
  lazy val tyFV: Set[Int] = args.tyFV

  lazy val symbolMap: Map[Signature#Key, (Count, Depth)] = {
    hd match {
      case BoundIndex(_,_) => Map()
      case Atom(key,_)             =>  fuseSymbolMap(Map(key -> (1,1)), args.symbolMap.mapValues {case (c,d) => (c,d+1)})
      case HeadClosure(Atom(key,_), _) => fuseSymbolMap(Map(key -> (1,1)), args.symbolMap.mapValues {case (c,d) => (c,d+1)})
      case HeadClosure(BoundIndex(_, scope), subs) => subs._1.substBndIdx(scope) match {
        case BoundFront(_) => Map()
        case TermFront(t) => fuseSymbolMap(t.asInstanceOf[TermImpl].symbolMap, args.symbolMap.mapValues {case (c,d) => (c,d+1)})
        case TypeFront(_) => throw new IllegalArgumentException("Type substitute found in term substition") // This should never happen
      }
      case HeadClosure(HeadClosure(h, s2), s1) => fuseSymbolMap(HeadClosure(h, (s2._1 o s1._1, s2._2 o s1._2)).symbolMap, args.symbolMap.mapValues {case (c,d) => (c,d+1)})
      case _ => Map()
    }
  }

  lazy val headSymbol = Root(hd, SNil)
  val headSymbolDepth = 0
  lazy val feasibleOccurrences = if (args.length == 0)
    Map(this.asInstanceOf[Term] -> Set(Position.root))
  else
    fuseMaps(Map(this.asInstanceOf[Term] -> Set(Position.root), headToTerm(hd) -> Set(Position.root.headPos)), args.feasibleOccurences)
  lazy val size = 2 + args.size

  // Other operations
  lazy val etaExpand0: TermImpl = {
    if (hd.ty.isFunType) {
      val hdFunParamTypes = hd.ty.funParamTypes
      if (args.length < hdFunParamTypes.length) {
        // Introduce new lambda binders, number = missing #args
        var missing = hdFunParamTypes.length - args.length
        // Lift head if it is a variable (lift by number of new lambdas)
        val newHead = hd match {
          case BoundIndex(t, sc) => BoundIndex(t, sc + missing)
          case _ => hd
        }
        // Lift arguments and eta expand recursively
        val liftedArgs = args.normalize(Subst.shift(missing), Subst.id).etaExpand
        // produce new list of bound variables (decreasing index)
        val newTypes = hdFunParamTypes.drop(args.length)
        val newSpineSuffix: Spine = newTypes.foldLeft(SNil.asInstanceOf[Spine]){case (s, t) => {val r = s ++ App(Root(BoundIndex(t, missing), SNil).etaExpand,SNil); missing = missing - 1; r}}
        val newSpine = liftedArgs ++ newSpineSuffix
        // combine and prefix with lambdas
        val liftedBody: TermImpl = Root(newHead, newSpine)
        newTypes.foldRight(liftedBody){case (ty, t) => TermAbstr(ty, t)}
      } else {
        Root(hd, args.etaExpand)
      }
    }
    else if (hd.ty.isPolyType) {
      // drop all prefix-type arguments, get rest
      val (tyArgs, restArgs) = getFirstNTyArgs(args, hd.ty.polyPrefixArgsCount)
      assert(tyArgs.size == hd.ty.polyPrefixArgsCount)
      // Now do the same as above for function types, but with typeBody as functional type
      // and with restArgs as Args to count/expand
      val typeBody = hd.ty.instantiate(tyArgs)
      val hdFunParamTypes = typeBody.funParamTypes
      if (restArgs.length < hdFunParamTypes.length) {
        // Introduce new lambda binders, number = missing #args
        var missing = hdFunParamTypes.length - restArgs.length
        // Lift head if it is a variable (lift by number of new lambdas)
        val newHead = hd match {
          case BoundIndex(t, sc) => BoundIndex(t, sc + missing)
          case _ => hd
        }
        // Lift arguments and eta expand recursively
        val liftedArgs = args.normalize(Subst.shift(missing), Subst.id).etaExpand
        // produce new list of bound variables (decreasing index)
        val newTypes = hdFunParamTypes.drop(restArgs.length)
        val newSpineSuffix: Spine = newTypes.foldLeft(SNil.asInstanceOf[Spine]){case (s, t) => {val r = s ++ App(Root(BoundIndex(t, missing), SNil),SNil); missing = missing - 1; r}}
        val newSpine = liftedArgs ++ newSpineSuffix
        // combine and prefix with lambdas
        val liftedBody: TermImpl = Root(newHead, newSpine)
        newTypes.foldRight(liftedBody){case (ty, t) => TermAbstr(ty, t)}
      } else
        Root(hd, args.etaExpand)
    } else this
  }
  private final def getFirstNTyArgs(sp: Spine, n: Int): (Seq[Type], Spine) = getFirstNTyArgs0(sp, n, Seq())
  private final def getFirstNTyArgs0(sp: Spine, n: Int, acc: Seq[Type]): (Seq[Type], Spine) = n match {
    case 0 => (acc.reverse, sp)
    case _ => sp match {
      case TyApp(typ, tail) => getFirstNTyArgs0(tail, n-1, typ +: acc)
      case _ => throw new IllegalArgumentException
    }
  }

  final def replace(what: Term, by: Term): Term = if (this == what)
                                              by
                                            else
                                              hd.replace(what, by) match {
                                                case Some(repl) => Redex(repl, args.replace(what, by))
                                                case None => Root(hd, args.replace(what, by))
                                              }
  final def replaceAt(at: Position, by: Term): Term = if (at == Position.root)
                                                  by
                                                else
                                                  at match {
                                                    case HeadPos() => Redex(by, args)
                                                    case _ => Root(hd, args.replaceAt(at, by))
                                                  }

  final def normalize(termSubst: Subst, typeSubst: Subst) = {
    val termSubstNF = termSubst //.normalize
    val typeSubstNF = typeSubst //.normalize

    hd match {
      case Atom(_,_)  => Root(hd, normalizeSpine(args,termSubstNF, typeSubstNF))
      case b@BoundIndex(t, scope) => b.substitute(termSubstNF) match {
        case BoundFront(j) => Root(BoundIndex(t.substitute(typeSubstNF), j), normalizeSpine(args,termSubstNF, typeSubstNF))
        case TermFront(t) => Redex(t, args).normalize0(Subst.id,Subst.id, termSubstNF, typeSubstNF)
        case _ => throw new IllegalArgumentException("type front found where it was not expected")
      }
      case HeadClosure(h2, (termSubst2, typeSubst2)) => h2 match {
        case Atom(_,_) => Root(h2, normalizeSpine(args,termSubst, typeSubst))
        case b@BoundIndex(t, scope) => b.substitute(termSubst2.comp(termSubst)) match {
          case BoundFront(j) => Root(BoundIndex(t.substitute(typeSubst2 o typeSubst), j), args.normalize(termSubst, typeSubst))
          case TermFront(t) => Redex(t, args).normalize0(Subst.id, Subst.id, termSubst, typeSubst)
          case _ => throw new IllegalArgumentException("type front found where it was not expected")
        }
        case HeadClosure(h3, (termSubst3, typeSubst3)) => Root(HeadClosure(h3, (termSubst3 o termSubst2, typeSubst3 o typeSubst2)), args).asInstanceOf[TermImpl].normalize(termSubst, typeSubst)
      }
    }
  }

  private def normalizeSpine(sp: Spine, termSubst: Subst, typeSubst: Subst): Spine = sp.normalize(termSubst, typeSubst)

  /** Pretty */
  final def pretty = s"${hd.pretty} ⋅ (${args.pretty})"
  final def pretty(sig: Signature): String =  s"${hd.pretty(sig)} ⋅ (${args.pretty(sig)})"
}

// For all terms that have not been normalized, assume they are a redex, represented
// by this term instance
protected[impl] case class Redex(body: Term, args: Spine) extends TermImpl {
  import TermImpl.mkRedex

  final protected[impl] def markBetaNormal(): Unit = {
    throw new IllegalArgumentException("Cannot mark Redex as Beta-Normal")
  }
  final protected[impl] def markBetaEtaNormal(): Unit = {
    throw new IllegalArgumentException("Cannot mark Redex as Beta-Normal")
  }

  // Predicates on terms
  @inline final val isAtom = false
  @inline final val isConstant = false
  @inline final val isVariable = false
  @inline final val isTermAbs = false
  @inline final val isTypeAbs = false
  @inline final val isApp = true
  protected[impl] def flexHead0(depth: Int): Boolean = body.asInstanceOf[TermImpl].flexHead0(depth)

  // Handling def. expansion
  final def δ_expandable(implicit sig: Signature) = body.δ_expandable(sig) || args.δ_expandable(sig)
  final def δ_expand(rep: Int)(implicit sig: Signature) = mkRedex(body.δ_expand(rep)(sig), args.δ_expand(rep)(sig))
  final def δ_expand(implicit sig: Signature) = mkRedex(body.δ_expand(sig), args.δ_expand(sig))
  final def δ_expand_upTo(symbs: Set[Signature#Key])(implicit sig: Signature): Term = mkRedex(body.δ_expand_upTo(symbs)(sig), args.δ_expand_upTo(symbs)(sig))

  // Queries on terms
  lazy val ty = ty0(body.ty, args)
  private def ty0(funty: Type, s: Spine): Type = s match {
    case SNil => funty
    case App(s0,tail) => funty match {
      case (_ -> out) => ty0(out, tail)
      case _ => throw NotWellTypedException(this) // this should not happen if well-typed
    }
    case TyApp(s0,tail) => funty match {
      case tt@(∀(_)) => ty0(tt.instantiate(s0), tail)
      case _ => throw NotWellTypedException(this) // this should not happen if well-typed
    }
    case _ => throw new IllegalArgumentException("closure occured in term")// other cases do not apply
  }
  lazy val fv: Set[(Int, Type)] = body.fv union args.fv
  lazy val tyFV: Set[Int] = body.tyFV union args.tyFV
  lazy val symbolMap: Map[Signature#Key, (Count, Depth)] = fuseSymbolMap(body.asInstanceOf[TermImpl].symbolMap, args.symbolMap.mapValues{case (c,d) => (c,d+1)})
  lazy val headSymbol = body.headSymbol
  lazy val headSymbolDepth = 1 + body.headSymbolDepth
  lazy val size = 1 + body.size + args.size
  lazy val feasibleOccurrences = fuseMaps(fuseMaps(Map(this.asInstanceOf[Term] -> Set(Position.root)), body.feasibleOccurrences.mapValues(_.map(_.prependHeadPos))), args.feasibleOccurences)
  // Other operations
  def etaExpand0: TermImpl = throw new IllegalArgumentException("this should not have happend. calling eta expand on not beta normalized term")

  final def replace(what: Term, by: Term): Term = if (this == what)
                                              by
                                            else
                                              Redex(body.replace(what, by), args.replace(what, by))
  final def replaceAt(at: Position, by: Term): Term = if (at == Position.root)
                                                  by
                                                else
                                                  at match {
                                                    case HeadPos() => Redex(by, args)
                                                    case _ => Redex(body, args.replaceAt(at, by))
                                                  }

  final def normalize(termSubst: Subst, typeSubst: Subst) = normalize0(termSubst, typeSubst, termSubst, typeSubst)

  @tailrec
  protected[impl] final def normalize0(headTermSubst: Subst, headTypeSubst: Subst, spineTermSubst: Subst, spineTypeSubst: Subst): TermImpl = args match {
    case SNil => body.asInstanceOf[TermImpl].normalize(headTermSubst, headTypeSubst)
    case SpineClos(sp2, (spTermSubst, spTypeSubst)) => Redex(body, sp2).normalize0(headTermSubst, headTypeSubst, spTermSubst o spineTermSubst, spTypeSubst o spineTypeSubst)
    case other => body match {
      case TermAbstr(t,b) => other match {
        case App(s0, tail) => Redex(b, tail).normalize0(TermFront(TermClos(s0, (spineTermSubst, spineTypeSubst))) +: headTermSubst, headTypeSubst, spineTermSubst, spineTypeSubst)
        case _ => throw new IllegalArgumentException("malformed expression")
      }
      case TypeAbstr(b)   => other match {
        case TyApp(t, tail) => Redex(b, tail).normalize0(headTermSubst, TypeFront(t.substitute(spineTypeSubst)) +: headTypeSubst, spineTermSubst, spineTypeSubst)
        case _ => throw new IllegalArgumentException("malformed expression")
      }
      case Root(h,s) => Root(HeadClosure(h, (headTermSubst, headTypeSubst)), s.merge((headTermSubst, headTypeSubst),args,(spineTermSubst, spineTypeSubst))).normalize(Subst.id, Subst.id)
      case Redex(b,args2) => Redex(b, args2.merge((headTermSubst, headTypeSubst),args,(spineTermSubst, spineTypeSubst))).normalize0(headTermSubst, headTypeSubst, Subst.id, Subst.id)
      case TermClos(t, (termSubst2, typeSubst2)) => Redex(t, args).normalize0(termSubst2 o headTermSubst, typeSubst2 o headTypeSubst, spineTermSubst, spineTypeSubst)
    }
  }

  /** Pretty */
  final def pretty = s"[${body.pretty}] ⋅ (${args.pretty})"
  final def pretty(sig: Signature): String =  s"[${body.pretty(sig)}] ⋅ (${args.pretty(sig)})"
}

protected[impl] case class TermAbstr(typ: Type, body: Term) extends TermImpl {
  import TermImpl.mkTermAbstr

  final protected[impl] def markBetaNormal(): Unit = {
    this.normal = true
    body.asInstanceOf[TermImpl].markBetaNormal()
  }
  final protected[impl] def markBetaEtaNormal(): Unit = {
    this.normal = true; this.etanormal = true
    body.asInstanceOf[TermImpl].markBetaEtaNormal()
  }

  // Predicates on terms
  @inline final val isAtom = false
  @inline final val isConstant = false
  @inline final val isVariable = false
  @inline final val isTermAbs = true
  @inline final val isTypeAbs = false
  @inline final val isApp = false
  protected[impl] def flexHead0(depth: Int): Boolean = body.asInstanceOf[TermImpl].flexHead0(depth+1)

  // Handling def. expansion
  final def δ_expandable(implicit sig: Signature) = body.δ_expandable(sig)
  final def δ_expand(rep: Int)(implicit sig: Signature) = mkTermAbstr(typ, body.δ_expand(rep)(sig))
  final def δ_expand(implicit sig: Signature) = mkTermAbstr(typ, body.δ_expand(sig))
  final def δ_expand_upTo(symbs: Set[Signature#Key])(implicit sig: Signature): Term = mkTermAbstr(typ, body.δ_expand_upTo(symbs)(sig))

  // Queries on terms
  lazy val ty = typ ->: body.ty
  lazy val fv: Set[(Int, Type)] = body.fv.map{case (i,t) => (i-1,t)}.filter(_._1 > 0)
  lazy val tyFV: Set[Int] = body.tyFV
  lazy val symbolMap: Map[Signature#Key, (Count, Depth)] = body.asInstanceOf[TermImpl].symbolMap.mapValues {case (c,d) => (c,d+1)}
  lazy val headSymbol = body.headSymbol
  lazy val headSymbolDepth = 1 + body.headSymbolDepth
  lazy val size = 1 + body.size
  lazy val feasibleOccurrences = {
    val bodyOccurrences = body.feasibleOccurrences
    var filteredOccurrences: Map[Term, Set[Position]] = Map()
    val bodyOccIt = bodyOccurrences.iterator
    while (bodyOccIt.hasNext) {
      val (subterm, positions) = bodyOccIt.next()
      val newPositions = positions.filterNot(p => subterm.looseBounds.contains(1+p.abstractionCount))
      if (newPositions.nonEmpty) {
        filteredOccurrences = filteredOccurrences + (subterm -> newPositions.map(_.prependAbstrPos))
      }
    }
    fuseMaps(
      Map(this.asInstanceOf[Term] -> Set(Position.root)),
      filteredOccurrences
    )
  }
//  lazy val feasibleOccurrences = fuseMaps(Map(this.asInstanceOf[Term] -> Set(Position.root)),body.feasibleOccurrences.filterNot { oc => oc._1.looseBounds.contains(1)}.mapValues(_.map(_.prependAbstrPos)))

  // Other operations
  lazy val etaExpand0: TermImpl = TermAbstr(typ, body.asInstanceOf[TermImpl].etaExpand0)

  final def replace(what: Term, by: Term): Term = if (this == what)
                                              by
                                            else
                                              TermAbstr(typ, body.replace(what, by))
  final def replaceAt(at: Position, by: Term): Term = if (at == Position.root)
                                                  by
                                                else
                                                  TermAbstr(typ, body.replaceAt(at.tail, by))

  final def normalize(termSubst: Subst, typeSubst: Subst) =
    TermAbstr(typ.substitute(typeSubst), body.asInstanceOf[TermImpl].normalize(termSubst.sink, typeSubst))

  /** Pretty */
  final def pretty = s"λ[${typ.pretty}]. (${body.pretty})"
  final def pretty(sig: Signature): String =  s"λ[${typ.pretty(sig)}]. (${body.pretty(sig)})"
}

protected[impl] case class TypeAbstr(body: Term) extends TermImpl {
  import TermImpl.mkTypeAbstr
  import Type.∀

  final protected[impl] def markBetaNormal(): Unit = {
    this.normal = true
    body.asInstanceOf[TermImpl].markBetaNormal()
  }
  final protected[impl] def markBetaEtaNormal(): Unit = {
    this.normal = true; this.etanormal = true
    body.asInstanceOf[TermImpl].markBetaEtaNormal()
  }

  // Predicates on terms
  @inline final val isAtom = false
  @inline final val isConstant = false
  @inline final val isVariable = false
  @inline final val isTermAbs = false
  @inline final val isTypeAbs = true
  @inline final val isApp = false
  protected[impl] def flexHead0(depth: Int): Boolean = body.asInstanceOf[TermImpl].flexHead0(depth)

  // Handling def. expansion
  final def δ_expandable(implicit sig: Signature) = body.δ_expandable(sig)
  final def δ_expand(rep: Int)(implicit sig: Signature) = mkTypeAbstr(body.δ_expand(rep)(sig))
  final def δ_expand(implicit sig: Signature) = mkTypeAbstr(body.δ_expand(sig))
  final def δ_expand_upTo(symbs: Set[Signature#Key])(implicit sig: Signature): Term = mkTypeAbstr(body.δ_expand_upTo(symbs)(sig))

  // Queries on terms
  lazy val ty = ∀(body.ty)
  lazy val fv: Set[(Int, Type)] = body.fv
  lazy val tyFV: Set[Int] = body.tyFV.map(_ - 1).filter(_ > 0)
  lazy val symbolMap: Map[Signature#Key, (Count, Depth)] = body.asInstanceOf[TermImpl].symbolMap.mapValues {case (c,d) => (c,d+1)}
  lazy val headSymbol = body.headSymbol
  lazy val headSymbolDepth = 1 + body.headSymbolDepth

  lazy val size = 1 + body.size
  lazy val feasibleOccurrences = body.feasibleOccurrences // FIXME

  // Other operations
  lazy val etaExpand0: TermImpl = TypeAbstr(body.asInstanceOf[TermImpl].etaExpand0)

  final def replace(what: Term, by: Term): Term = if (this == what)
                                              by
                                            else
                                              TypeAbstr(body.replace(what, by))
  final def replaceAt(at: Position, by: Term): Term = if (at == Position.root)
                                                  by
                                                else
                                                  TypeAbstr(body.replaceAt(at.tail, by))

  final def normalize(termSubst: Subst, typeSubst: Subst) =
    TypeAbstr(body.asInstanceOf[TermImpl].normalize(termSubst, typeSubst.sink))

  /** Pretty */
  final def pretty = s"Λ. (${body.pretty})"
  final def pretty(sig: Signature): String =   s"Λ. (${body.pretty(sig)})"
}

protected[impl] case class TermClos(term: Term, σ: (Subst, Subst)) extends TermImpl {
  // Closure should never be handed to the outside

  final protected[impl] def markBetaNormal(): Unit = {
    throw new IllegalArgumentException("Cannot mark closure as beta-normal")
  }
  final protected[impl] def markBetaEtaNormal(): Unit = {
    throw new IllegalArgumentException("Cannot mark closure as beta-eta-normal")
  }

  // Predicates on terms
  @inline final val isAtom = false
  @inline final val isConstant = false
  @inline final val isVariable = false
  @inline final val isTermAbs = false
  @inline final val isTypeAbs = false
  @inline final val isApp = false
  protected[impl] def flexHead0(depth: Int): Boolean = this.betaNormalize.asInstanceOf[TermImpl].flexHead0(depth)

  // Handling def. expansion
  def δ_expandable(implicit sig: Signature) = false // TODO
  def δ_expand(rep: Int)(implicit sig: Signature) = ???
  def δ_expand(implicit sig: Signature) = ???
  def δ_expand_upTo(symbs: Set[Signature#Key])(implicit sig: Signature): Term = ???

  // Queries on terms
  lazy val ty = term.ty
  final def fv: Set[(Int, Type)] = betaNormalize.fv
  final def tyFV: Set[Int] = betaNormalize.tyFV
  final def symbolMap: Map[Signature#Key, (Count, Depth)] = betaNormalize.asInstanceOf[TermImpl].symbolMap
  final def headSymbol = betaNormalize.headSymbol
  final def headSymbolDepth = 1 + term.headSymbolDepth
  final def feasibleOccurrences = betaNormalize.feasibleOccurrences
  lazy val size = term.size // this might not be reasonable, but will never occur when used properly

  // Other operations
  final def etaExpand0: TermImpl = betaNormalize.asInstanceOf[TermImpl].etaExpand0

  final def replace(what: Term, by: Term): Term = betaNormalize.replace(what, by)
  final def replaceAt(at: Position, by: Term): Term = betaNormalize.replaceAt(at, by)

  final def normalize(termSubst: Subst, typeSubst: Subst) =
    term.asInstanceOf[TermImpl].normalize(σ._1 o termSubst, σ._2 o typeSubst)

  /** Pretty */
  final def pretty = s"${term.pretty}[${σ._1.pretty}/${σ._2.pretty}]"
  final def pretty(sig: Signature): String =   s"${term.pretty(sig)}[${σ._1.pretty}/${σ._2.pretty}]"
}

/////////////////////////////////////////////////
// Implementation of head symbols
/////////////////////////////////////////////////

protected[impl] sealed abstract class Head extends Pretty with Prettier {
  // Predicates
  def isBound: Boolean
  def isConstant: Boolean

  // Queries
  def ty: Type
  final def replace(what: Term, by: Term): Option[Term] = if (TermImpl.headToTerm(this) == what)
                                              Some(by)
                                            else
                                              None

  // Handling def. expansion
  def δ_expandable(sig: Signature): Boolean
  def δ_expand(rep: Int)(sig: Signature): Term
  def δ_expand(sig: Signature): Term
}

protected[impl] case class BoundIndex(ty: Type, scope: Int) extends Head {
  // Predicates
  @inline final val isBound = true
  @inline final val isConstant = false

  // Handling def. expansion
  @inline final def δ_expandable(sig: Signature) = false
  @inline final def δ_expand(rep: Int)(sig: Signature) = δ_expand(sig)
  @inline final def δ_expand(sig: Signature) = TermImpl.headToTerm(this)

  // Pretty printing
  override lazy val pretty = s"$scope:${ty.pretty}"
  final def pretty(sig: Signature) = s"$scope:${ty.pretty(sig)}"

  // Local definitions
  final def substitute(s: Subst) = s.substBndIdx(scope)
}


protected[impl] case class Atom(id: Signature#Key, ty: Type) extends Head {

  // Predicates
  @inline final val isBound = false
  @inline final val isConstant = true

  // Handling def. expansion
  @inline final def δ_expandable(sig: Signature) = sig(id).hasDefn
  final def δ_expand(rep: Int)(sig: Signature) = if (rep == 0) TermImpl.headToTerm(this)
  else if (rep > 0) {
    val meta = sig(id)
    if (meta.hasDefn) meta._defn.δ_expand(rep-1)(sig)
    else TermImpl.headToTerm(this)
  } else {
    //rep  < 0
    val meta = sig(id)
    if (meta.hasDefn) meta._defn.δ_expand(rep)(sig)
    else TermImpl.headToTerm(this)
  }
  @inline final def δ_expand(sig: Signature) = δ_expand(-1)(sig)

  // Pretty printing
  override lazy val pretty = s"const($id)"
  final def pretty(sig: Signature) = sig(id).name
}


protected[impl] case class HeadClosure(hd: Head, subst: (Subst, Subst)) extends Head {
  // Predicates
  @inline final val isBound = false
  @inline final val isConstant = false

  // Queries
  final def ty = hd.ty

  // Handling def. expansion
  final def δ_expandable(sig: Signature) = ???
  final def δ_expand(rep: Int)(sig: Signature) = ???
  final def δ_expand(sig: Signature) = ???

  // Pretty printing
  final def pretty = s"${hd.pretty}[${subst._1.pretty}/${subst._2.pretty}}]"
  final def pretty(sig: Signature) = s"${hd.pretty(sig)}[${subst._1.pretty}/${subst._2.pretty}}]"
}


/////////////////////////////////////////////////
// Implementation of spines
/////////////////////////////////////////////////

/**
 * // TODO Documentation
 */
protected[impl] sealed abstract class Spine extends Pretty with Prettier {
  import TermImpl.{mkSpineCons => cons}

  def normalize(termSubst: Subst, typeSubst: Subst): Spine
  def etaExpand: Spine

  // Handling def. expansion
  def δ_expandable(sig: Signature): Boolean
  def δ_expand(rep: Int)(sig: Signature): Spine
  def δ_expand(sig: Signature): Spine
  def δ_expand_upTo(symbs: Set[Signature#Key])(sig: Signature): Spine

  // Queries
  def length: Int
  def fv: Set[(Int, Type)]
  def tyFV: Set[Int]
  def symbolMap: Map[Signature#Key, (Int, Int)]
  def asTerms: Seq[Either[Term, Type]]
  def size: Int
  lazy val feasibleOccurences: Map[Term, Set[Position]] =  feasibleOccurrences0(1)
  def feasibleOccurrences0(pos: Int): Map[Term, Set[Position]]
  // Misc
  def merge(subst: (Subst, Subst), sp: Spine, spSubst: (Subst, Subst)): Spine

  def ++(sp: Spine): Spine
  def +(t: TermImpl): Spine = ++(cons(Left(t),SNil))
  def +(t: Type): Spine = ++(cons(Right(t),SNil))

  /** Drop n arguments from spine, fails with IllegalArgumentException if n > length */
  def drop(n: Int): Spine

  def last: Either[Term, Type]
  def init: Spine

  def replace(what: Term, by: Term): Spine
  def replaceAt(at: Position, by: Term): Spine = replaceAt0(at.posHead, at.tail, by)

  def substitute(subst: Subst): Spine

  protected[impl] def replaceAt0(pos: Int, tail: Position, by: Term): Spine
  protected[impl] def markBetaNormal(): Unit
  protected[impl] def markBetaEtaNormal(): Unit
}

protected[impl] case object SNil extends Spine {
  final def normalize(termSubst: Subst, typeSubst: Subst) = SNil
  final val etaExpand: Spine = this

  final protected[impl] def markBetaNormal(): Unit = {}
  final protected[impl] def markBetaEtaNormal(): Unit = {}

  // Handling def. expansion
  @inline final def δ_expandable(sig: Signature) = false
  @inline final def δ_expand(rep: Int)(sig: Signature) = SNil
  @inline final def δ_expand(sig: Signature) = SNil
  @inline final def δ_expand_upTo(symbs: Set[Signature#Key])(sig: Signature) = SNil

  // Queries
  final val fv: Set[(Int, Type)] = Set()
  final val tyFV: Set[Int] = Set()
  final val symbolMap: Map[Signature#Key, (Int, Int)] = Map.empty
  final val length = 0
  final val asTerms = Seq()
  final val size = 1
  final def feasibleOccurrences0(pos: Int) = Map()

  // Misc
  final def merge(subst: (Subst, Subst), sp: Spine, spSubst: (Subst, Subst)) = SpineClos(sp, spSubst)
  final def ++(sp: Spine) = sp

  final def drop(n: Int) = n match {
    case 0 => SNil
    case _ => throw new IllegalArgumentException("Trying to drop elements from nil spine.")
  }

  final def last = throw new IllegalArgumentException("Trying to access last element of Nil")
  final def init = throw new IllegalArgumentException("Trying to access init of Nil")
  final def replace(what: Term, by: Term): Spine = SNil
  final def replaceAt0(pos: Int, tail: Position, by: Term): Spine = SNil

  final def substitute(subst: Subst): Spine = SNil

  // Pretty printing
  final val pretty = "⊥"
  final def pretty(sig: Signature) = "⊥"
}

protected[impl] case class App(hd: Term, tail: Spine) extends Spine {
  import TermImpl.{mkSpineCons => cons}

  protected[impl] def markBetaNormal(): Unit = {
    hd.asInstanceOf[TermImpl].markBetaNormal()
    tail.markBetaNormal()
  }
  protected[impl] def markBetaEtaNormal(): Unit = {
    hd.asInstanceOf[TermImpl].markBetaEtaNormal()
    tail.markBetaEtaNormal()
  }

  def normalize(termSubst: Subst, typeSubst: Subst) = {
//    if (isIndexed) { // TODO: maybe optimize re-normalization if term is indexed?
//      ???
//    } else {
    cons(Left(hd.asInstanceOf[TermImpl].normalize((termSubst), (typeSubst))), tail.normalize(termSubst, typeSubst))
//    }
  }

  lazy val etaExpand: Spine = App(hd.etaExpand,  tail.etaExpand)

  // Handling def. expansion
  @inline final def δ_expandable(sig: Signature) = hd.δ_expandable(sig) || tail.δ_expandable(sig)
  @inline final def δ_expand(rep: Int)(sig: Signature) = cons(Left(hd.δ_expand(rep)(sig)), tail.δ_expand(rep)(sig))
  @inline final def δ_expand(sig: Signature) = cons(Left(hd.δ_expand(sig)), tail.δ_expand(sig))
  @inline final def δ_expand_upTo(symbs: Set[Signature#Key])(sig: Signature) = cons(Left(hd.δ_expand_upTo(symbs)(sig)), tail.δ_expand_upTo(symbs)(sig))

  // Queries
  lazy val fv: Set[(Int, Type)] = hd.fv union tail.fv
  lazy val tyFV: Set[Int] = hd.tyFV union tail.tyFV
  lazy val symbolMap: Map[Signature#Key, (Int, Int)] = hd.asInstanceOf[TermImpl].fuseSymbolMap(hd.asInstanceOf[TermImpl].symbolMap, tail.symbolMap)
  lazy val length = 1 + tail.length
  lazy val asTerms = Left(hd) +: tail.asTerms
  lazy val size = 1+ hd.size + tail.size
  def feasibleOccurrences0(pos: Int) = fuseMaps(hd.feasibleOccurrences.mapValues(_.map(_.preprendArgPos(pos))), tail.feasibleOccurrences0(pos+1))

  // Misc
  def merge(subst: (Subst, Subst), sp: Spine, spSubst: (Subst, Subst)) = App(TermClos(hd, subst), tail.merge(subst, sp, spSubst))
  def ++(sp: Spine) = cons(Left(hd), tail ++ sp)

  final def drop(n: Int) = n match {
    case 0 => this
    case _ => tail.drop(n-1)
  }
  lazy val last = tail match {
    case SNil => Left(hd)
    case _ => tail.last
  }
  lazy val init = tail match {
    case App(_,SNil) => App(hd, SNil)
    case TyApp(_,SNil) => App(hd, SNil)
    case _ => App(hd, tail.init)
  }

  final def replace(what: Term, by: Term): Spine = if (hd == what)
                                              App(by, tail.replace(what,by))
                                             else
                                              App(hd.replace(what,by), tail.replace(what,by))

  final def replaceAt0(pos: Int, posTail: Position, by: Term): Spine = pos match {
    case 1 if posTail == Position.root => App(by, tail)
    case 1 => App(hd.replaceAt(posTail, by), tail)
    case _ => App(hd, tail.replaceAt0(pos-1, posTail, by))
  }

  final def substitute(subst: Subst): Spine = App(hd.substitute(subst), tail.substitute(subst))

  // Pretty printing
  final def pretty = s"${hd.pretty};${tail.pretty}"
  final def pretty(sig: Signature) = s"${hd.pretty(sig)};${tail.pretty(sig)}"
}

protected[impl] case class TyApp(hd: Type, tail: Spine) extends Spine {
  import TermImpl.{mkSpineCons => cons}

  final protected[impl] def markBetaNormal(): Unit = {
    tail.markBetaNormal()
  }
  final protected[impl] def markBetaEtaNormal(): Unit = {
    tail.markBetaEtaNormal()
  }

  def normalize(termSubst: Subst, typeSubst: Subst) = {
    cons(Right(hd.substitute(typeSubst)), tail.normalize(termSubst, typeSubst))
  }
  lazy val etaExpand: Spine = TyApp(hd,  tail.etaExpand)

  // Handling def. expansion
  @inline final def δ_expandable(sig: Signature) = tail.δ_expandable(sig)
  @inline final def δ_expand(rep: Int)(sig: Signature) = cons(Right(hd), tail.δ_expand(rep)(sig))
  @inline final def δ_expand(sig: Signature) = cons(Right(hd), tail.δ_expand(sig))
  @inline final def δ_expand_upTo(symbs: Set[Signature#Key])(sig: Signature) = cons(Right(hd), tail.δ_expand_upTo(symbs)(sig))

  // Queries
  lazy val fv: Set[(Int, Type)] = tail.fv
  lazy val tyFV: Set[Int] = tail.tyFV union hd.typeVars.map(BoundType.unapply(_).get)
  lazy val symbolMap: Map[Signature#Key, (Int, Int)] = tail.symbolMap
  lazy val length = 1 + tail.length
  lazy val asTerms = Right(hd) +: tail.asTerms
  lazy val size = 1 + tail.size
  final def feasibleOccurrences0(pos: Int) = tail.feasibleOccurrences0(pos+1)

  // Misc
  def merge(subst: (Subst, Subst), sp: Spine, spSubst: (Subst, Subst)) = TyApp(hd.substitute(subst._2), tail.merge(subst, sp, spSubst))
  def ++(sp: Spine) = cons(Right(hd), tail ++ sp)

  final def drop(n: Int) = n match {
    case 0 => this
    case _ => tail.drop(n-1)
  }
  lazy val last = tail match {
    case SNil => Right(hd)
    case _ => tail.last
  }

  lazy val init = tail match {
    case App(_,SNil) => TyApp(hd, SNil)
    case TyApp(_,SNil) => TyApp(hd, SNil)
    case _ => TyApp(hd, tail.init)
  }
  final def replace(what: Term, by: Term): Spine = TyApp(hd, tail.replace(what,by))
  final def replaceAt0(pos: Int, posTail: Position, by: Term): Spine = pos match {
    case 1 => throw new IllegalArgumentException("Trying to replace term inside of type.")
    case _ => TyApp(hd, tail.replaceAt0(pos-1, posTail, by))
  }

  final def substitute(subst: Subst): Spine = TyApp(hd, tail.substitute(subst))

  // Pretty printing
  final def pretty = s"${hd.pretty};${tail.pretty}"
  final def pretty(sig: Signature) = s"${hd.pretty(sig)};${tail.pretty(sig)}"
}


protected[impl] case class SpineClos(sp: Spine, s: (Subst, Subst)) extends Spine {
  import TermImpl.{mkSpineCons => cons}

  protected[impl] def markBetaNormal(): Unit = {
    throw new IllegalArgumentException("Marked Spine closure as beta-normal.")
  }
  protected[impl] def markBetaEtaNormal(): Unit = {
    throw new IllegalArgumentException("Marked Spine closure as beta-eta-normal.")
  }

  def normalize(termSubst: Subst, typeSubst: Subst) = {
    sp.normalize(s._1 o termSubst, s._2 o typeSubst)
  }
  lazy val etaExpand: Spine = ???

  // Handling def. expansion
  final def δ_expandable(sig: Signature) = false // TODO
  final def δ_expand(rep: Int)(sig: Signature) = ???
  final def δ_expand(sig: Signature) = ???
  final def δ_expand_upTo(symbs: Set[Signature#Key])(sig: Signature) = ???
  // Queries
  lazy val fv: Set[(Int, Type)] = ???
  lazy val tyFV: Set[Int] = ???
  lazy val symbolMap: Map[Signature#Key, (Int, Int)] = normalize(s._1,s._2).symbolMap
  lazy val length = sp.length
  lazy val asTerms = ???
  lazy val size = sp.size // todo: properly implement
  def feasibleOccurrences0(pos: Int) = Map.empty

  // Misc
  def merge(subst: (Subst, Subst), sp: Spine, spSubst: (Subst, Subst)) = sp.merge((s._1 o subst._1,s._2 o subst._2),sp, spSubst)
  def ++(sp: Spine) = ???

  def drop(n: Int) = ???
  def last = ???
  def init = ???

  def replace(what: Term, by: Term): Spine = ???
  def replaceAt0(pos: Int, posTail: Position, by: Term): Spine = ???

  def substitute(subst: Subst): Spine = ???

  // Pretty printing
  final def pretty = s"(${sp.pretty}[${s._1.pretty}/${s._2.pretty}])"
  final def pretty(sig: Signature) = s"(${sp.pretty(sig)}[${s._1.pretty}/${s._2.pretty}])"
}


/////////////////////////////////////////////////
// Companion object for constructors, DAG caching etc.
/////////////////////////////////////////////////
object TermImpl extends TermBank {

  /////////////////////////////////////////////
  // Hash tables for DAG representation of perfectly
  // shared terms
  /////////////////////////////////////////////
  protected[TermImpl] var terms: Set[Term] = Set.empty

  // atomic symbols (heads)
  protected[TermImpl] var boundAtoms: Map[Type, Map[Int, Head]] = Map.empty
  protected[TermImpl] var symbolAtoms: Map[Signature#Key, Head] = Map.empty

  // composite terms
  protected[TermImpl] var termAbstractions: Map[Term, Map[Type, TermImpl]] = Map.empty
  protected[TermImpl] var typeAbstractions: Map[Term, TermImpl] = Map.empty
  protected[TermImpl] var roots: Map[Head, Map[Spine, TermImpl]] = Map.empty
  protected[TermImpl] var redexes: Map[Term, Map[Spine, Redex]] = Map.empty

  // Spines
  protected[TermImpl] var spines: Map[Either[Term, Type], Map[Spine, Spine]] = Map.empty

  /////////////////////////////////////////////
  // Implementation based constructor methods
  /////////////////////////////////////////////

  // primitive symbols (heads)
  final protected[impl] def mkAtom0(id: Signature#Key, ty: Type): Head = // Atom(id)
    symbolAtoms.get(id) match {
      case Some(hd) => hd
      case None     => val hd = Atom(id, ty)
                       symbolAtoms += ((id, hd))
                       hd
  }

  final protected[impl] def mkBoundAtom(t: Type, scope: Int): Head = //BoundIndex(t,scope)
    boundAtoms.get(t) match {
    case Some(inner) => inner.get(scope) match {
      case Some(hd)   => hd
      case None       => val hd = BoundIndex(t, scope)
                         boundAtoms += ((t,inner.+((scope, hd))))
                         hd
    }
    case None        => val hd = BoundIndex(t, scope)
                        boundAtoms += ((t, Map((scope, hd))))
                        hd
  }

  // composite terms
  final protected[impl] def mkRoot(hd: Head, args: Spine): TermImpl = //Root(hd, args)
    global(roots.get(hd) match {
    case Some(inner) => inner.get(args) match {
      case Some(root)  => root
      case None        => val root = Root(hd, args)
                          roots += ((hd,inner.+((args, root))))
                          root
    }
    case None        => val root = Root(hd, args)
                        roots += ((hd, Map((args, root))))
                        root
  })
  final protected[impl] def mkRedex(left: Term, args: Spine): Redex = //Redex(left, args)
    global(redexes.get(left) match {
    case Some(inner) => inner.get(args) match {
      case Some(redex) => redex
      case None        => val redex = Redex(left, args)
                          redexes += ((left,inner.+((args, redex))))
                          redex
    }
    case None        => val redex = Redex(left, args)
                        redexes += ((left, Map((args, redex))))
                        redex
  })
  final protected[impl] def mkTermAbstr(t: Type, body: Term): TermImpl = //TermAbstr(t, body)
    global(termAbstractions.get(body) match {
    case Some(inner) => inner.get(t) match {
      case Some(abs)   => abs
      case None        => val abs = TermAbstr(t, body)
                          termAbstractions += ((body,inner.+((t, abs))))
                          abs
    }
    case None        => val abs = TermAbstr(t, body)
                        termAbstractions += ((body, Map((t, abs))))
                        abs
  })
  final protected[impl] def mkTypeAbstr(body: Term): TermImpl = //TypeAbstr(body)
    global(typeAbstractions.get(body) match {
    case Some(abs) => abs
    case None      => val abs = TypeAbstr(body)
                      typeAbstractions += ((body, abs))
                      abs
  })

  // Spines
  final protected[impl] def mkSpineNil: Spine = SNil
  final protected[impl] def mkSpineCons(term: Either[Term, Type], tail: Spine): Spine = //term.fold(App(_, tail),TyApp(_, tail))
    spines.get(term) match {
    case Some(inner) => inner.get(tail) match {
      case Some(sp)   => sp
      case None       => val sp = term.fold(App(_, tail),TyApp(_, tail))
                         spines += ((term,inner.+((tail, sp))))
                         sp
    }
    case None       => val sp = term.fold(App(_, tail),TyApp(_, tail))
                       spines += ((term, Map((tail, sp))))
                       sp
  }

  final private def global[A <: TermImpl](t: A): A = {
    t._sharing = true
    t
  }

  @inline final private def mkSpine(args: Seq[Term]): Spine = args.foldRight[Spine](SNil)({case (t,sp) => mkSpineCons(Left(t),sp)})
  @inline final private def mkTySpine(args: Seq[Type]): Spine = args.foldRight[Spine](SNil)({case (t,sp) => mkSpineCons(Right(t),sp)})
  @inline final private def mkGenSpine(args: Seq[Either[Term,Type]]): Spine = args.foldRight[Spine](SNil)({case (t,sp) => mkSpineCons(t,sp)})

  /////////////////////////////////////////////
  // Public visible term constructors
  /////////////////////////////////////////////
  final val local = new TermFactory {
    @inline final private def mkSpine(args: Seq[Term]): Spine = args.foldRight[Spine](SNil)({case (t,sp) => App(t, sp)})
    @inline final private def mkTySpine(args: Seq[Type]): Spine = args.foldRight[Spine](SNil)({case (t,sp) => TyApp(t, sp)})
    @inline final private def mkGenSpine(args: Seq[Either[Term, Type]]): Spine = args.foldRight(mkSpineNil)((termOrTy,sp) => termOrTy.fold(App(_,sp),TyApp(_,sp)))

    final def mkAtom(id: Signature#Key)(implicit sig: Signature): Term = Root(Atom(id, sig(id)._ty), SNil)
    final def mkAtom(id: Signature#Key, ty: Type): Term = Root(Atom(id, ty), SNil)
    final def mkBound(t: Type, scope: Int): Term = Root(BoundIndex(t, scope), SNil)

    final def mkTermApp(func: Term, arg: Term): Term = mkTermApp(func, Vector(arg))
    final def mkTermApp(func: Term, args: Seq[Term]): Term = if (args.isEmpty)
      func else func match {
      case Root(h, SNil) => Root(h, mkSpine(args.toVector))
      case Root(h,sp)  => Root(h,sp ++ mkSpine(args.toVector))
      case Redex(r,sp) => Redex(r, sp ++ mkSpine(args.toVector))
      case other       => Redex(other, mkSpine(args.toVector))
    }
    final def mkTermAbs(t: Type, body: Term): Term = TermAbstr(t, body)

    final def mkTypeApp(func: Term, arg: Type): Term = mkTypeApp(func, Vector(arg))
    final def mkTypeApp(func: Term, args: Seq[Type]): Term = if (args.isEmpty)
      func else func match {
      case Root(h, SNil) => Root(h, mkTySpine(args.toVector))
      case Root(h,sp)  => Root(h,sp ++ mkTySpine(args.toVector))
      case Redex(r,sp) => Redex(r, sp ++ mkTySpine(args.toVector))
      case other       => Redex(other, mkTySpine(args.toVector))
    }
    final def mkTypeAbs(body: Term): Term = TypeAbstr(body)

    final def mkApp(func: Term, args: Seq[Either[Term, Type]]): Term = if (args.isEmpty)
      func else func match {
        case Root(h, SNil) => Root(h, mkGenSpine(args.toVector))
        case Root(h,sp)  => Root(h,sp ++ mkGenSpine(args.toVector))
        case Redex(r,sp) => Redex(r, sp ++ mkGenSpine(args.toVector))
        case other       => Redex(other, mkGenSpine(args.toVector))
      }
  }

  final def mkAtom(id: Signature#Key)(implicit sig: Signature): TermImpl = mkRoot(mkAtom0(id, sig(id)._ty), SNil)
  final def mkAtom(id: Signature#Key, ty: Type): TermImpl = mkRoot(mkAtom0(id, ty), SNil)
  final def mkBound(typ: Type, scope: Int): TermImpl = mkRoot(mkBoundAtom(typ, scope), SNil)

  final def mkTermApp(func: Term, arg: Term): Term = mkTermApp(func, Vector(arg))
  final def mkTermApp(func: Term, args: Seq[Term]): Term = if (args.isEmpty)
    func else func match {
      case Root(h, SNil) => mkRoot(h, mkSpine(args.toVector))
      case Root(h,sp)  => mkRoot(h,sp ++ mkSpine(args.toVector))
      case Redex(r,sp) => mkRedex(r, sp ++ mkSpine(args.toVector))
      case other       => mkRedex(other, mkSpine(args.toVector))
    }
  final def mkTermAbs(typ: Type, body: Term): TermImpl = mkTermAbstr(typ, body)

  final def mkTypeApp(func: Term, arg: Type): TermImpl = mkTypeApp(func, Vector(arg))
  final def mkTypeApp(func: Term, args: Seq[Type]): TermImpl  = if (args.isEmpty) func.asInstanceOf[TermImpl] else func match {
      case Root(h, SNil) => mkRoot(h, mkTySpine(args.toVector))
      case Root(h,sp)  => mkRoot(h,sp ++ mkTySpine(args.toVector))
      case Redex(r,sp) => mkRedex(r, sp ++ mkTySpine(args.toVector))
      case other       => mkRedex(other, mkTySpine(args.toVector))
    }

  final def mkTypeAbs(body: Term): TermImpl = mkTypeAbstr(body)

  final def mkApp(func: Term, args: Seq[Either[Term, Type]]): Term  = if (args.isEmpty)
    func else func match {
      case Root(h, SNil) => mkRoot(h, mkGenSpine(args.toVector))
      case Root(h,sp)  => mkRoot(h,sp ++ mkGenSpine(args.toVector))
      case Redex(r,sp) => mkRedex(r, sp ++ mkGenSpine(args.toVector))
      case other       => mkRedex(other, mkGenSpine(args.toVector))
    }

  /////////////////////////////////////////////
  // Further TermBank methods
  /////////////////////////////////////////////

  final def contains(term: Term): Boolean = terms.contains(term)

  final def insert(term: Term): Term = {
    val t = if (Term.isLocal(term))
      insert0(term)
    else
      term

    terms = terms + t
    t
  }

  final protected[TermImpl] def insert0(localTerm: Term): TermImpl = {
    localTerm match {
      case Root(h, sp) => val sp2 = insertSpine0(sp)
        val h2 = h match {
          case BoundIndex(ty, scope) => mkBoundAtom(ty, scope)
          case Atom(id,ty) => mkAtom0(id, ty)
          case hc@HeadClosure(chd, s) => hc // TODO: do we need closures in bank?
        }
        global(mkRoot(h2, sp2))

      case Redex(rx, sp) => val sp2 = insertSpine0(sp)
        val rx2 = insert0(rx)
        global(mkRedex(rx2, sp2))

      case TermAbstr(ty, body) => val body2 = insert0(body)
        global(mkTermAbstr(ty, body2))

      case TypeAbstr(body) => val body2 = insert0(body)
        global(mkTypeAbstr(body2))
      case tc@TermClos(ct, s) => global(tc)
      case _ => throw new IllegalArgumentException("trying to insert a non-spine term to spine termbank")
    }
  }

  final protected def insertSpine0(sp: Spine): Spine = {
    sp match {
      case SNil => mkSpineNil
      case App(hd, tail) => val hd2 = insert0(hd)
                            val tail2 = insertSpine0(tail)
                            mkSpineCons(Left(hd2), tail2)
      case TyApp(ty, tail) => val tail2 = insertSpine0(tail)
                              mkSpineCons(Right(ty), tail2)
      case sc@SpineClos(csp, s) => sc
    }
  }

  final def reset() = {
    boundAtoms = Map.empty
    symbolAtoms = Map.empty

    // composite terms
    termAbstractions = Map.empty
    typeAbstractions = Map.empty
    roots = Map.empty
    redexes = Map.empty

    // Spines
   spines = Map.empty
  }

  /////////////////////////////////////////////
  // type checking
  /////////////////////////////////////////////
  /** Checks if a term is well-typed. Does does check whether free variables
    * are consistently typed. */
  @inline final protected[datastructures] def wellTyped(t: TermImpl): Boolean = wellTyped0(t, Map())

  @tailrec
  final private def wellTyped0(t: TermImpl, boundVars: Map[Int, Type]): Boolean = {
    t match {
      case Root(hd, args) => hd match {
        case BoundIndex(typ0, scope) => if (boundVars.isDefinedAt(scope)) {
          if (boundVars(scope) == typ0) {
            // Bound indices cannot be polymorphic, and we assume that if they
            // contain type variables, they have been instantiated further above
            wellTypedArgCheck(t, typ0, args, boundVars, false)
          } else {leo.Out.trace(s"Application ${t.pretty} is ill-typed: The bound variable's type at head position does not correspond" +
            s"to its type declaration of the original binder."); false}
        } else true // assume free variables are consistently typed.
        case Atom(key, ty) => // atoms type can be polymorphic
          wellTypedArgCheck(t, ty, args, boundVars, true)
        case _ => throw new IllegalArgumentException("wellTyped0 on this head type currently not supported.")
      }
      case Redex(hd, args) => wellTypedArgCheck(hd, hd.ty, args,boundVars, true) && wellTyped0(hd.asInstanceOf[TermImpl], boundVars)
      case TermAbstr(ty, body) => wellTyped0(body.asInstanceOf[TermImpl],
        boundVars.map {case (scope, ty0) => (scope + 1, ty0) } + ((1, ty)))
      case TypeAbstr(body) => wellTyped0(body.asInstanceOf[TermImpl], boundVars)
      case TermClos(body, sub) => wellTyped0(t.betaNormalize.asInstanceOf[TermImpl], boundVars)
    }
  }

  @tailrec
  final private def wellTypedArgCheck(term: Term, functionType: Type, args: Spine, boundVars: Map[Int, Type], canBePolyFunc: Boolean): Boolean = args match {
    case SNil => true
    case App(hd,tail) if functionType.isFunType =>
      if (functionType._funDomainType == hd.ty) {
        if (wellTyped0(hd.asInstanceOf[TermImpl], boundVars)) {
          wellTypedArgCheck(term, functionType.codomainType, tail, boundVars, canBePolyFunc)
        } else {
          leo.Out.trace(s"Application ${term.pretty} is ill-typed: The argument ${hd.pretty} is ill-typed.")
          false
        }
      } else {
        leo.Out.trace(s"Application ${term.pretty} is ill-typed: The head's parameter type ${functionType._funDomainType.pretty}" +
          s"does not correspond to the applied argument's type ${hd.ty.pretty}.")
        false
      }
    case App(_,_) => leo.Out.trace(s"Application ${term.pretty} is ill-typed: The head does not take" +
      s"parameters, but arguments are applied."); false
    case TyApp(hd,tail) if functionType.isPolyType =>
      if (canBePolyFunc) {
        wellTypedArgCheck(term, functionType.instantiate(hd), tail, boundVars, canBePolyFunc)
      } else false
    case TyApp(_,_) => leo.Out.trace(s"Application ${term.pretty} is ill-typed: The head does not take type" +
      s"parameters, but type arguments are applied."); false
    case _ => leo.Out.trace(s"Application ${term.pretty} is ill-typed."); false
  }


  ////////////////////////////////////////////
  // TermImpl Deconstructors
  ////////////////////////////////////////////
  // we use these functions for referencing approriate unapply methods
  // for the extractor objects in class Term

  final protected[datastructures] def boundMatcher(t: Term): Option[(Type, Int)] = t match {
    case Root(BoundIndex(ty, scope), SNil) => Some((ty, scope))
    case _ => None
  }
  final protected[datastructures] def symbolMatcher(t: Term): Option[Signature#Key] = t match {
    case Root(Atom(k,_),SNil) => Some(k)
    case _ => None
  }
  final protected[datastructures] def appMatcher(t: Term): Option[(Term, Seq[Either[Term, Type]])] = t match {
    case Root(h, sp) => Some((headToTerm(h), sp.asTerms))
    case Redex(expr, sp) => Some((expr, sp.asTerms))
    case _ => None
  }
  final protected[datastructures] def termAbstrMatcher(t: Term): Option[(Type,Term)] = t match {
    case TermAbstr(ty, body)      => Some((ty, body))
    case _ => None
  }
  final protected[datastructures] def typeAbstrMatcher(t: Term): Option[Term] = t match {
    case TypeAbstr(body)      => Some(body)
    case _ => None
  }


  ////////////////////////////////////////////
  // Utility, night be removed in the future
  ////////////////////////////////////////////
  implicit final def headToTerm(hd: Head): TermImpl = mkRoot(hd, mkSpineNil)

  /**
   * Statistics type. Components and meanings:
   * comp 1: number of terms, Int
   * comp 2: avg. size of terms, Int
   * comp 3: min. size of terms, Int
   * comp 4: max. size of terms, Int
   * comp 5: number of nodes, Int
   * comp 6: number of edges, Int
   * comp 6: # of parents to count map, Map[Int, Int]
   */
  type TermBankStatistics = (Int, Int, Int, Int, Int, Int, Map[Int, Int])

  final def statistics: TermBankStatistics = {
    val numberOfTerms = terms.size +1

    val parentNodeCountMap: Map[Int, Int] = Map.empty

    // Term sizes
    // start element (min, max, X)
    val intermediate = terms.foldRight((-1,-1,-1))((t,acc) => {val s = t.size
                                            val min = Math.min(acc._1, s)
                                            val max = Math.max(acc._2, s)
                                            (min, max, acc._3  + s)
                                           })
    val minSizeOfTerms = intermediate._1
    val maxSizeOfTerms = intermediate._2
    val avgSizeOfTerms = intermediate._3 / numberOfTerms

    var termAbstractionsSize = 0
    termAbstractions.foreach({ case (term, map) =>
      termAbstractionsSize += map.size
    })
    var rootsSize = 0
    roots.foreach({ case (term, map) =>
      rootsSize += map.size
    })
    var redexesSize = 0
    redexes.foreach({ case (term, map) =>
      redexesSize += map.size
    })
    var spinesSize = 0
    spines.foreach({ case (_, map) =>
      spinesSize += map.size
    })

    val numberOfNodes = { boundAtoms.size + symbolAtoms.size
                        + termAbstractionsSize + typeAbstractions.size
                        + rootsSize + redexesSize
                        + spinesSize }

    // edges:
    // for each Root(h,sp) we have 2 edges
    // for each Redex(rx, sp) we have 2 edges
    // for each TermAbstr(ty, body) we have one edge (types are not counted)
    // for each TypeAbstr(body) we have one edge
    // for each TermCons(t, tail) we have two edges
    // for each TypeCons(ty,tail) we have two edges
    val numberOfEdges = {
      termAbstractionsSize
      +typeAbstractions.size
      +2 * (
        rootsSize
          + redexesSize
          + spinesSize
        )
    }

    (numberOfTerms,avgSizeOfTerms,minSizeOfTerms,maxSizeOfTerms,numberOfNodes,numberOfEdges,parentNodeCountMap)
  }

}
