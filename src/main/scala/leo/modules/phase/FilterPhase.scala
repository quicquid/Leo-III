package leo.modules.phase

import leo.agents.TAgent
import leo.modules.agent.relevance_filter.RelevanceFilterAgent

/**
  * Created by mwisnie on 3/10/16.
  */
class FilterPhase extends CompletePhase {
  override def name: String = "relevance_filter_phase"
  override protected val agents: Seq[TAgent] = Seq(RelevanceFilterAgent)
}