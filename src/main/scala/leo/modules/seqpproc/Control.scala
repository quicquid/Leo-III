package leo
package modules.seqpproc

import leo.datastructures.{Clause, Literal, Position, Term}
import Literal.Side

/**
  * Created by lex on 22.02.16.
  */
object ParamodControl {
  final def paramodSet(cl: ClauseWrapper, withset: Set[ClauseWrapper]): Set[ClauseWrapper] = {
    var results: Set[ClauseWrapper] = Set()
    val withsetIt = withset.iterator

    while (withsetIt.hasNext) {
      val other = withsetIt.next()
      Out.debug(s"Paramod on ${cl.id} and ${other.id}")

      results = results ++ allParamods(cl,other)
    }

    results
  }

  final def allParamods(cl: ClauseWrapper, other: ClauseWrapper): Set[ClauseWrapper] = {

    // Do paramod with cl into other
    val res = allParamods0(cl, other)
    if (cl.id != other.id) {
      // do paramod with other into cl
      res ++ allParamods0(other, cl)
    } else res
  }

  final private def allParamods0(withWrapper: ClauseWrapper, intoWrapper: ClauseWrapper): Set[ClauseWrapper] = {
    var results: Set[ClauseWrapper] = Set()

    val withClause = withWrapper.cl
    val intoClause = intoWrapper.cl

    val withConfigurationIt = withConfigurationIterator(withClause)
    while (withConfigurationIt.hasNext) {
      val (withIndex, withLit, withSide) = withConfigurationIt.next()
      val withTerm = if (withSide) withLit.left else withLit.right

      assert(withClause.lits(withIndex) == withLit, s"${withIndex} in ${withClause.pretty}\n lit = ${withLit.pretty}")
      assert(withClause.lits(withIndex).polarity)

      val intoConfigurationIt = intoConfigurationIterator(intoClause)
      while (intoConfigurationIt.hasNext) {
        val (intoIndex, intoLit, intoSide, intoPos, intoTerm) = intoConfigurationIt.next()

        if (!intoTerm.isVariable && leo.modules.calculus.mayUnify(withTerm, intoTerm)) {
          Out.trace(s"May unify: ${withTerm.pretty} with ${intoTerm.pretty} (subterm at ${intoPos.pretty})")
          val newCl = OrderedParamod(withClause, withIndex, withSide,
            intoClause, intoIndex, intoSide, intoPos, intoTerm)

          val newClWrapper = ClauseWrapper(newCl, InferredFrom(OrderedParamod, Set(withWrapper, intoWrapper)))
          Out.finest(s"Result: ${newClWrapper.pretty}")
          results = results + newClWrapper
        }

      }
    }

    results
  }

  type LiteralIndex = Int
  type WithConfiguration = (LiteralIndex, Literal, Side)
  type Subterm = Term
  type IntoConfiguration = (LiteralIndex, Literal, Side, Position, Subterm)

  final private def withConfigurationIterator(cl: Clause): Iterator[WithConfiguration] = new Iterator[WithConfiguration] {
    import Literal.{leftSide, rightSide}
    var maxLits = cl.maxLits
    var litIndex = 0
    var lits = cl.lits
    var side = leftSide

    final def hasNext: Boolean = {
//      Out.finest("hasNext")
      if (lits.isEmpty) false
      else {
//        Out.finest(s"lits not empty")
        val hd = lits.head
//        Out.finest(s"hd: ${hd.pretty}")
        if (hd.polarity && maxLits.contains(hd)) true
        else {
//          Out.finest(s"negative polarity, skip")
          litIndex = litIndex + 1
          lits = lits.tail
          hasNext
        }
      }
    }

    final def next(): WithConfiguration = {
      if (hasNext) {
        assert(lits.head.polarity)
        val res = (litIndex, lits.head, side)
        if (lits.head.oriented || side == rightSide) {
          litIndex += 1
          lits = lits.tail
          side = leftSide
        } else {
          side = rightSide
        }
        assert(res._2.polarity)
        res
      } else {
        throw new NoSuchElementException
      }
    }
  }


  final private def intoConfigurationIterator(cl: Clause): Iterator[IntoConfiguration] = new Iterator[IntoConfiguration] {
    import Literal.{leftSide, rightSide, selectSide}
    val maxLits = cl.maxLits
    var litIndex = 0
    var lits = cl.lits
    var side = leftSide
    var curSubterms: Set[Term] = null
    var curPositions: Set[Position] = null

    def hasNext: Boolean = if (lits.isEmpty) false
    else {
      val hd = lits.head
      if (!maxLits.contains(hd)) {
        lits = lits.tail
        litIndex += 1
        hasNext
      } else {
        if (curSubterms == null) {
          curSubterms = selectSide(hd,side).feasibleOccurences.keySet
          curPositions = selectSide(hd,side).feasibleOccurences(curSubterms.head)
          true
        } else {
          if (curPositions.isEmpty) {
            curSubterms = curSubterms.tail
            if (curSubterms.isEmpty) {
              if (hd.oriented || side == rightSide) {
                lits = lits.tail
                litIndex += 1
                side = leftSide
              } else {
                side = rightSide
              }
              curSubterms = null
              curPositions = null
              hasNext
            } else {
              curPositions = selectSide(hd,side).feasibleOccurences(curSubterms.head)
              assert(hasNext)
              true
            }
          } else {
            true
          }
        }
      }

    }

    def next(): IntoConfiguration = {
      if (hasNext) {
        val res = (litIndex, lits.head, side, curPositions.head, curSubterms.head)
        curPositions = curPositions.tail
        res
      } else {
        throw new NoSuchElementException
      }
    }
  }
}

object FactorizationControl {

}