package parsers.combinators

import parsers.PExec
import scala.util.parsing.combinator.PackratParsers

/**
 * Created by lex on 3/23/14.
 */
object THF extends PExec with PackratParsers {
  override type Target = tptp.THF
  override def target = null
}