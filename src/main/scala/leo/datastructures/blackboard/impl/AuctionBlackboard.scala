package leo.datastructures.blackboard.impl


import leo.agents.{AgentController, Agent, Task}
import leo.datastructures.blackboard.scheduler.Scheduler
import leo.datastructures.blackboard._

import scala.collection.immutable.HashMap
import scala.collection.mutable

/**
 * This blackboard is a first reference implementation for the @see{Blackboard} interface.
 *
 * It utilizes no doubly added formulas and an auction implementation to access and organize tasks.
 *
 * @author Max Wisniewski <max.wisniewski@fu-berlin.de>
 * @since 19.08.2015
 */
protected[blackboard] class AuctionBlackboard extends Blackboard {

  /**
   * Register a new Handler for Formula adding Handlers.
 *
   * @param a - The Handler that is to register
   */
  override def registerAgent(a: Agent) : Unit = {
    TaskSet.addAgent(a)
    freshAgent(a)
  }

  override def unregisterAgent(a: Agent): Unit = {
    TaskSet.removeAgent(a)
  }

  /**
   * Blocking Method to get a fresh Task.
   *
   * @return Not yet executed Task
   */
  override def getTask: Iterable[(Agent,Task)] = TaskSet.getTask

  override def clear() : Unit = {
    dsset.foreach(_.clear())
    TaskSet.clear()
  }

  /**
   * Gives all agents the chance to react to an event
   * and adds the generated tasks.
   *
   * @param t - Function that generates for each agent a set of tasks.
   */
  override def filterAll(t: (Agent) => Unit): Unit = {
    TaskSet.agents.foreach{ a =>
      t(a)
    }
  }

  override def submitTasks(a: Agent, ts : Set[Task]) : Unit = {
    TaskSet.taskSet.submit(ts)  // TODO Synchronizing?
    signalTask()      // WHO HAS THE LOCK?=????
  }

  override def finishTask(t : Task) : Unit = {
    TaskSet.taskSet.finish(t)     // TODO synchronizing?
    LockSet.releaseTask(t)        // TODO Still necessary?
  }

  /**
   * Method that filters the whole Blackboard, if a new agent 'a' is added
   * to the context.
   *
   * @param a - New Agent.
   */
  override protected[blackboard] def freshAgent(a: Agent): Unit = {
    a.interest match {
      case None => ()
      case Some(xs) =>
        val ts = if(xs.nonEmpty) xs else dsmap.keys.toList
        ts.foreach{t =>
          dsmap.getOrElse(t, Set.empty).foreach{ds =>
            ds.all(t).foreach{d =>
              val ts : Iterable[Task] = a.filter(DataEvent(d,t))
              //ts.foreach(t => ActiveTracker.incAndGet(s"Inserted Task\n  ${t.pretty}"))
              submitTasks(a, ts.toSet)
            }
        }}
        forceCheck()
    }
  }

  override def signalTask() : Unit = TaskSet.signalTask()

  /**
   *
   * @return all registered agents
   */
  override def getAgents(): Iterable[(Agent,Double)] = TaskSet.regAgents.toSeq

  /**
   * Sends a message to an agent.
   *
   * TODO: Implement without loss of tasks through messages
   *
   * @param m    - The message to send
   * @param to   - The recipient
   */
  override def send(m: Message, to: Agent): Unit = {
//    println(s"Called send to ${to.name}: $m")
    val ts = to.filter(m)
//    println(s"Filtered message to ${to.name}: ${ts.map(_.pretty).mkString("\n")}")
    submitTasks(to, ts.toSet)
//    println(s"Done submitting")
  }

  /**
   * Allows a force check for new Tasks. Necessary for the DoneEvent to be
   * thrown correctly.
   */
  override protected[blackboard] def forceCheck(): Unit = TaskSet.synchronized(TaskSet.notifyAll())


  private val dsset : mutable.Set[DataStore] = new mutable.HashSet[DataStore]
  private val dsmap : mutable.Map[DataType, Set[DataStore]] = new mutable.HashMap[DataType, Set[DataStore]]

  /**
   * Adds a data structure to the blackboard.
   * After this method the data structure will be
   * manipulated by the action of the agents.
   *
   * @param ds is the data structure to be added.
   */
  override def addDS(ds: DataStore): Unit = if(dsset.add(ds)) ds.storedTypes.foreach{t => dsmap.put(t, dsmap.getOrElse(t, Set.empty) + ds)}

  /**
   * Adds a data structure to the blackboard.
   * After this method the data structure will
   * no longer be manipulated by the action of the agent.
   *
   * @param ds is the data structure to be added.
   */
  override def rmDS(ds: DataStore): Unit = if (dsset.remove(ds)) ds.storedTypes.foreach{t => dsmap.put(t, dsmap.getOrElse(t, Set.empty) - ds)}

  /**
   * For the update phase in the executor.
   * Returns a list of all data structures to
   * insert a given type.
   *
   * @param d is the type that we are interested in.
   * @return a list of all data structures, which store this type.
   */
  override protected[blackboard] def getDS(d: DataType): Seq[DataStore] = dsmap.getOrElse(d, Set.empty).toSeq
}





private object TaskSet {

  val regAgents = mutable.HashMap[Agent,Double]()
  //protected[impl] val execTasks = new mutable.HashSet[Task]

  /**
    * The set containing all dependencies on agents
    */
  val taskSet : TaskSet = new SimpleTaskSet()
  private val AGENT_SALARY : Double = 5   // TODO changeable

  /**
   * Notifies process waiting in 'getTask', that there is a new task available.
   */
  protected[blackboard] def signalTask() : Unit = {
//    println("In signal. Before")
    this.synchronized{this.notifyAll()}
//    println("In signal. After")
  }

  def clear() : Unit = {
    this.synchronized {
      regAgents.clear()
      LockSet.clear()
      this.taskSet.clear()
    }
  }

  def addAgent(a: Agent) {
    this.synchronized(regAgents.put(a,AGENT_SALARY))
    this.synchronized(taskSet.addAgent(a))
  }

  def removeAgent(a: Agent): Unit = this.synchronized{
    this.synchronized(regAgents.remove(a))
    this.synchronized(taskSet.removeAgent(a))
  }

  def agents : List[Agent] = this.synchronized(regAgents.toList.map(_._1))

  private def sendDoneEvents() : Unit = {
    val agents = regAgents.toList.map(_._1).toIterator
    while(agents.hasNext){
      val a = agents.next()
      taskSet.submit(a.filter(DoneEvent()))
    }
  }


  /**
   * Gets from any active agent the set of tasks, he wants to execute with his current budget.
   *
   * If the set of tasks is empty he waits until something is added
   * (filter should call signalTask).
   *
   * Of this set we play
   *
   * @return
   */
  def getTask : Iterable[(Agent,Task)] = {

    while(!Scheduler().isTerminated) {
      try {
        this.synchronized {
          //        println("Beginning to get items for the auction.")

          //
          // 1. Get all Tasks the Agents want to bid on during the auction with their current money
          //
          var r: List[(Double, Agent, Task)] = Nil
          while (r.isEmpty) {
            val ts = taskSet.executableTasks    // TODO Filter if no one can execute (simple done)
//            println(s"ts = ${ts.map(_.pretty).mkString(", ")}")
            ts.foreach { case t =>
              val a = t.getAgent
              val budget = regAgents.getOrElse(a, 0.0)
              r = (t.bid * budget, a, t) :: r  }
//            println("Obtained and budgeted set.")
            if (r.isEmpty) {
              if (ActiveTracker.get <= 0) {
              //  if(!Scheduler.working() && LockSet.isEmpty && regAgents.forall{case (a,_) => if(!a.hasTasks) {leo.Out.comment(s"[Auction]: ${a.name} has no work");true} else {leo.Out.comment(s"[Auction]: ${a.name} has work");false}}) {
//                println("Send Done events.")
                // Problems with filter all due to race conditions
                sendDoneEvents()
//                println("Finished Done events.")
              }
              //leo.Out.comment("Going to wait for new Tasks.")
//              println(s"Waiting now")
              TaskSet.wait()
              regAgents.foreach { case (a, budget) => regAgents.update(a, math.max(budget, budget + AGENT_SALARY)) }
            }
          }

          // println("Got tasks and ready to auction.")
          //
          // 2. Bring the Items in Order (sqrt (m) - Approximate Combinatorical Auction, with m - amount of colliding writes).
          //
          // Sort them by their value (Approximate best Solution by : (value) / (sqrt |WriteSet|)).
          // Value should be positive, s.t. we can square the values without changing order
          //
          val queue: List[(Double, Agent, Task)] = r.sortBy { case (b, a, t) => b * b / (1+t.writeSet().size) }
          var taken : Map[Agent, Int] = HashMap[Agent, Int]()

          //        println("Sorted tasks.")

          // 3. Take from beginning to front only the non colliding tasks
          // Check the currenlty executing tasks too.
          var newTask: List[(Agent, Task)] = Nil
          for ((price, a, t) <- queue) {
            // Check if the agent can execute another task
            val open : Boolean = a.maxParTasks.fold(true)(n => n - taskSet.executingTasks(a) + taken.getOrElse(a, 0) > 0)
            if (open & LockSet.isExecutable(t)) {
              val budget = regAgents.getOrElse(a, 0.0)     //TODO Lock regAgents, got error during phase switch
              if (budget >= price) {
                // The task is not colliding with previous tasks and agent has enough money
                newTask = (a, t) :: newTask
                LockSet.lockTask(t)
                a.taskChoosen(t)
                regAgents.put(a, budget - price)
                taken = taken + (a -> (taken.getOrElse(a,0)+1))
              }
            }
          }

          taskSet.commit(newTask.map(_._2).toSet)

          //        println("Choose optimal.")

          //
          // 4. After work pay salary, tell colliding and return the tasks
          //
          for ((a, b) <- regAgents) {
            if (a.maxMoney - b > AGENT_SALARY) {
              regAgents.put(a, b + AGENT_SALARY)
            }
          }
          //        println("Sending "+newTask.size+" tasks to scheduler.")

          return newTask
        }
        //Lastly interrupt recovery
      } catch {
        case e : InterruptedException => Thread.currentThread().interrupt()
        case e : Exception => throw e
      }
    }
    return Nil
  }

}
