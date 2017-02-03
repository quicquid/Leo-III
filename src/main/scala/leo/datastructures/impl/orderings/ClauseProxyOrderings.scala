package leo.datastructures.impl.orderings

import leo._
import leo.datastructures.ClauseProxy


  object CPO_FIFO extends ClauseProxyOrdering {
    def compare(x: ClauseProxy, y: ClauseProxy): Int = x.id compare y.id
  }

  object CPO_WeightAge extends ClauseProxyOrdering {
    def compare(a: ClauseProxy, b: ClauseProxy) = implicitly[Ordering[Tuple2[Int,Long]]].compare((a.weight, a.id),(b.weight, b.id))
  }

  object CPO_GoalsFirst extends ClauseProxyOrdering {
    def compare(a: ClauseProxy, b: ClauseProxy) = implicitly[Ordering[Tuple2[Double, Int]]].compare((1 - ((1+a.cl.negLits.size)/(1+a.cl.lits.size)), a.weight), (1 - ((1+b.cl.negLits.size)/(b.cl.lits.size+1)), b.weight))
  }

  object CPO_NonGoalsFirst extends ClauseProxyOrdering {
    def compare(a: ClauseProxy, b: ClauseProxy) = implicitly[Ordering[Tuple2[Double, Int]]].compare((1 - ((1+a.cl.posLits.size)/(1+a.cl.lits.size)), a.weight), (1 - ((1+b.cl.posLits.size)/(b.cl.lits.size+1)), b.weight))
  }

import leo.datastructures.Signature
class CPO_ConjRelativeSymbolWeight(conjSymbols: Set[Signature#Key], conjSymbolFactor: Float, varWeight: Int, symbWeight: Int) extends ClauseProxyOrdering {
  import leo.datastructures.Clause
  final def compare(a: ClauseProxy, b: ClauseProxy) = {
    val aWeight = computeWeight(a.cl)
    val bWeight = computeWeight(b.cl)
    -aWeight.compare(bWeight)
  }

  private[this] final def computeWeight(cl: Clause): Float = {
    val symbols = Clause.symbols(cl)
    var weight: Float = 0f
    weight += cl.implicitlyBound.size * varWeight
    val it = symbols.distinctIterator
    while(it.hasNext) {
      val symb = it.next()
      if (conjSymbols.contains(symb))
        weight += symbols.multiplicity(symb) * symbWeight * conjSymbolFactor
      else
        weight += symbols.multiplicity(symb) * symbWeight
    }
    weight
  }
}

class CPO_SymbolWeight(varWeight: Int, symbWeight: Int) extends ClauseProxyOrdering {
  import leo.datastructures.Clause
  final def compare(a: ClauseProxy, b: ClauseProxy) = {
    val aWeight = computeWeight(a.cl)
    val bWeight = computeWeight(b.cl)
    aWeight.compare(bWeight)
  }
  private[this] final def computeWeight(cl: Clause): Int = {
    val symbols = Clause.symbols(cl)
    var weight: Int = 0
    weight += cl.implicitlyBound.size * varWeight
    val it = symbols.distinctIterator
    while(it.hasNext) {
      val symb = it.next()
      weight += symbols.multiplicity(symb) * symbWeight
    }
    weight
  }
}

object CPO_SmallerFirst extends ClauseProxyOrdering {
  final def compare(a: ClauseProxy, b: ClauseProxy) = {
    val aLitsCounts = a.cl.lits.size; val bLitsCounts = b.cl.lits.size
    aLitsCounts.compareTo(bLitsCounts)
  }
}

object CPO_OldestFirst extends ClauseProxyOrdering {
  final def compare(a: ClauseProxy, b: ClauseProxy) = {
    val aAge = a.id; val bAge = b.id
    aAge.compareTo(bAge)
  }
}

object CPO_GoalsFirst2 extends ClauseProxyOrdering {
  def compare(a: ClauseProxy, b: ClauseProxy) = {
    val aGoalRatio: Float = if (a.cl.lits.isEmpty) 1 else a.cl.negLits.size.toFloat/a.cl.lits.size.toFloat
    val bGoalRatio: Float = if (b.cl.lits.isEmpty) 1 else b.cl.negLits.size.toFloat/b.cl.lits.size.toFloat
    aGoalRatio.compareTo(bGoalRatio)
  }
}

object CPO_NonGoalsFirst2 extends ClauseProxyOrdering {
  def compare(a: ClauseProxy, b: ClauseProxy) = {
    val aGoalRatio: Float = if (a.cl.lits.isEmpty) 1 else a.cl.posLits.size.toFloat/a.cl.lits.size.toFloat
    val bGoalRatio: Float = if (b.cl.lits.isEmpty) 1 else b.cl.posLits.size.toFloat/b.cl.lits.size.toFloat
    aGoalRatio.compareTo(bGoalRatio)
  }
}

object CPO_SOSFirst extends ClauseProxyOrdering {
  final def compare(a: ClauseProxy, b: ClauseProxy) = {
    import leo.datastructures.isPropSet
    import leo.datastructures.ClauseAnnotation.PropSOS
    val aProp = a.properties; val bProp = b.properties
    if (isPropSet(PropSOS, aProp)) {
      if (isPropSet(PropSOS, bProp)) 0
      else 1
    } else {
      if (isPropSet(PropSOS, bProp)) -1
      else 0
    }
  }
}

