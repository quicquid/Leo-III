package leo.modules.relevance_filter

import leo.LeoTestSuite
import leo.modules.parsers.TPTP

/**
  * Created by mwisnie on 1/9/17.
  */
class RelevanceFilterTest extends LeoTestSuite {
  test("One Step Delta Expansion"){
    PreFilterSet.clear()
    val definition1 = TPTP.parseFormula("thf(d1_defn, definition, (d1 = (a & b))).")
    val definition2 = TPTP.parseFormula("thf(d2_defn, definition, (d2 = (c & d))).")

    val formula = TPTP.parseFormula("thf(c, conjecture, (d1 & d2)).")

    PreFilterSet.addNewFormula(definition1)
    PreFilterSet.addNewFormula(definition2)
    PreFilterSet.addNewFormula(formula)

    val symbs = PreFilterSet.useFormula(formula)

    assert(symbs.contains("a"))
    assert(symbs.contains("b"))
    assert(symbs.contains("c"))
    assert(symbs.contains("d"))
  }


  test("Two Step Delta Expansion"){
    PreFilterSet.clear()
    val definition1 = TPTP.parseFormula("thf(d1_defn, definition, (d1 = (a & b))).")
    val definition2 = TPTP.parseFormula("thf(d2_defn, definition, (d2 = (d1 & c))).")

    val formula = TPTP.parseFormula("thf(c, conjecture, (d & d2)).")

    PreFilterSet.addNewFormula(definition1)
    PreFilterSet.addNewFormula(definition2)
    PreFilterSet.addNewFormula(formula)

    val symbs = PreFilterSet.useFormula(formula)

    assert(symbs.contains("a"))
    assert(symbs.contains("b"))
    assert(symbs.contains("c"))
    assert(symbs.contains("d"))
  }
}