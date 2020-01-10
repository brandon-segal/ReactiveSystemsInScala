/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester:ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor with ActorLogging {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root: ActorRef = createRoot

  // optional (used to stash incoming operations during garbage collection)
  var pendingQueue: Queue[Operation] = Queue.empty[Operation]

  // optional
  def receive:Receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case GC =>
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      garbageCollecting(newRoot)
    case operation:Operation => root ! operation
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case operation: Operation => pendingQueue.enqueue(operation)
    case GC => ()
    case CopyFinished =>
      pendingQueue.foreach(root ! _)
  }

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  /**
   * Acknowledges that a copy has been completed. This message should be sent
   * from a node to its parent, when this node and all its children nodes have
   * finished being copied.
   */
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(elem: Int, initiallyRemoved: Boolean) extends Actor with ActorLogging {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees:Map[Position,ActorRef] = Map[Position, ActorRef]()
  var removed:Boolean = initiallyRemoved

  // optional
  def receive:Receive = normal

  // Create a new node
  def createNode(newElem:Int):ActorRef=
    context.actorOf(BinaryTreeNode.props(elem,initiallyRemoved = false))

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case msg @ Contains(requester,id,queryElem)=>
      if (elem==queryElem){
        log.debug(s"Node with value $elem was found!")
        requester ! ContainsResult(id, !removed)
      }
      else if (subtrees.contains(child(queryElem))){
        log.debug(s"Passing $msg to $subtrees")
        subtrees(child(queryElem)) ! msg
      }
      else{
        log.debug(s"Tree does not contain $elem")
        requester ! ContainsResult(id,result = false)
      }

    case msg @ Insert(requester,id,queryElem)=>
      if (elem==queryElem) {
        log.debug(s"Reinserting the element $elem")
        removed = false
        requester ! OperationFinished(id)
      }
      else if (subtrees.contains(child(queryElem))){
        log.debug(s"Sending $msg to $subtrees")
        subtrees(child(queryElem)) ! msg
      }
      else{
        log.debug(s"Adding element $elem to $self subtree")
        subtrees += child(queryElem)->createNode(queryElem)
      }
    case msg @ Remove(requester,id,queryElem) =>
      if (elem==queryElem) {
        removed = true
        log.debug(s"Removed element $elem")
        requester ! OperationFinished(id)
      }
      else if (subtrees.contains(child(queryElem))){
        log.debug(s"Sending $msg to $subtrees")
        subtrees(child(queryElem)) ! msg
      }
      else{
        log.debug(s"Could not find $elem")
        requester ! OperationFinished(id)
      }

    case CopyTo(treeNode) =>
      val expected:Set[ActorRef] = subtrees.values.toSet
      if (expected.isEmpty && removed)
        treeNode ! CopyFinished
      else{
        if (!removed) treeNode ! Insert(self,0, elem)
        for{
          child <- expected
        }yield child ! CopyTo(treeNode)
        context.become(copying(expected = expected,removed))
      }
    case CopyFinished => sender ! PoisonPill
  }
  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case OperationFinished =>
      if(expected.isEmpty){
        context.parent ! CopyFinished
      }
      else context.become(copying(expected,insertConfirmed = true))
    case CopyFinished =>
      val newExpected = expected - sender
      if (newExpected.isEmpty){
        context.parent ! CopyFinished
      }else
        context.become(copying(newExpected,insertConfirmed))
  }

  def child(checkElem:Int):Position={
    if(elem > checkElem) Left else Right
  }
}
