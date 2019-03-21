package akka.cluster.sbr

import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.Member._
import akka.cluster.MemberStatus.{Joining, WeaklyUp}
import akka.cluster.sbr.implicits._
import cats.Eq
import cats.data.NonEmptyMap
import cats.implicits._

import scala.collection.immutable.{SortedMap, SortedSet}

/**
 * The cluster from the point of view of a node.
 */
final case class WorldView private[sbr] (private[sbr] val self: Member,
                                         private[sbr] val selfStatus: Status,
                                         private[sbr] val otherStatuses: SortedMap[Member, Status]) {

  import WorldView._

  /**
   * All the nodes in the cluster.
   */
  def allConsideredNodes: SortedSet[Member] =
    SortedSet(allStatuses.toSortedMap.collect {
      case (member, Reachable)   => member
      case (member, Unreachable) => member
    }.toSeq: _*)

  /**
   * All the nodes in the cluster with the given role. If `role` is the empty
   * string all nodes will be returned.
   *
   * @see [[allConsideredNodes]]
   */
  def allConsideredNodesWithRole(role: String): SortedSet[Member] =
    if (role != "") allConsideredNodes.filter(_.roles.contains(role)) else allConsideredNodes

  /**
   * Nodes that are reachable from the current node. Does not count weakly-up nodes
   * as they might not be visible from the other side of a potential split.
   */
  def reachableConsideredNodes: SortedSet[ReachableConsideredNode] =
    SortedSet(allStatuses.toSortedMap.collect {
      case (member, Reachable) => ReachableConsideredNode(member)
    }.toSeq: _*)

  /**
   * Reachable nodes with the given role. If `role` is the empty
   * string all reachable nodes will be returned.
   *
   * @see [[reachableConsideredNodes]]
   */
  def reachableConsideredNodesWithRole(role: String): SortedSet[ReachableConsideredNode] =
    if (role != "") reachableConsideredNodes.filter(_.node.roles.contains(role)) else reachableConsideredNodes

  def reachableNodes: SortedSet[ReachableNode] =
    SortedSet(allStatuses.toSortedMap.collect {
      case (member, Reachable) => ReachableNode(member)
      case (member, Staged)    => ReachableNode(member)
    }.toSeq: _*)

  /**
   * Nodes that have been flagged as unreachable.
   */
  def unreachableNodes: SortedSet[UnreachableNode] =
    SortedSet(allStatuses.toSortedMap.iterator.collect {
      case (member, Unreachable) => UnreachableNode(member)
    }.toSeq: _*)

  /**
   * Unreachable nodes with the given role. If `role` is the empty
   * string all unreachable nodes will be returned.
   *
   * @see [[unreachableNodes]]
   */
  def unreachableNodesWithRole(role: String): SortedSet[UnreachableNode] =
    if (role != "") unreachableNodes.filter(_.node.roles.contains(role)) else unreachableNodes

  /**
   * The reachability of the `member`.
   */
  def statusOf(node: Member): Option[Status] = allStatuses.lookup(node)

  /**
   * Updates the reachability given the member event.
   */
  def memberEvent(event: MemberEvent): Either[WorldViewError, WorldView] = {
    println(s"EVENT: $event")
    event match {
      case _: MemberJoined | _: MemberWeaklyUp => stage(event.member)

      case MemberUp(member) => up(member)

      case _: MemberLeft | _: MemberExited => prepareRemove(event.member)

      case MemberDowned(member) => down(member)

      // Not part of the cluster anymore.
      case MemberRemoved(member, _) => remove(member)
    }
  }

  /**
   * Updates the reachability given the reachability event.
   */
  def reachabilityEvent(event: ReachabilityEvent): Either[WorldViewError, WorldView] = event match {
    case UnreachableMember(member) => becomeUnreachable(member)
    case ReachableMember(member)   => becomeReachable(member)
  }

  /**
   * Stages the `node`.
   *
   * A staged node is a node that has been seen by the the current
   * node but should not be counted in the decisions. E.g. in the
   * `Joining` and `WeaklyUp` status.
   *
   */
  private def stage(node: Member): Either[NodeAlreadyStaged, WorldView] =
    if (node === self) {
      // `self` is added at construction
      this.asRight
    } else {
      statusOf(node)
        .fold[Either[NodeAlreadyStaged, WorldView]](copy(otherStatuses = otherStatuses + (node -> Staged)).asRight)(
          _ => NodeAlreadyStaged(node).asLeft
        )
    }

  /**
   * Effectively checks that the `node` is in the current status before removing
   * it when it moves to the `Removed` state.
   */
  private def prepareRemove(node: Member): Either[WorldViewError, WorldView] =
    if (node === self) {
      this.asRight // todo can it?
    } else {
      statusOf(node).fold[Either[WorldViewError, WorldView]](UnknownNode(node).asLeft) {
        case Staged      => NodeStillStaged(node).asLeft
        case Reachable   => this.asRight
        case Unreachable => this.asRight
      }
    }

  /**
   * todo
   */
  private def down(node: Member): Either[WorldViewError, WorldView] =
    statusOf(node).fold[Either[WorldViewError, WorldView]](UnknownNode(node).asLeft)(_ => this.asRight)

  /**
   * Makes a staged node `Reachable`.
   */
  private def up(node: Member): Either[NodeAlreadyCounted, WorldView] =
    if (node === self) {
      copy(selfStatus = Reachable).asRight
    } else {
      statusOf(node)
        .fold[Either[NodeAlreadyCounted, WorldView]](copy(otherStatuses = otherStatuses + (node -> Reachable)).asRight) { // todo check if node other than self can become directly up
          case Staged      => copy(otherStatuses = otherStatuses + (node -> Reachable)).asRight
          case Reachable   => NodeAlreadyCounted(node).asLeft
          case Unreachable => NodeAlreadyCounted(node).asLeft
        }
    }

  /**
   * Remove the `node`.
   */
  private def remove(node: Member): Either[WorldViewError, WorldView] =
    if (node === self) {
      CannotRemoveSelf(node).asLeft
    } else if (otherStatuses.contains(node)) {
      copy(otherStatuses = otherStatuses - node).asRight
    } else {
      UnknownNode(node).asLeft
    }

  /**
   * Change the `node`'s status to `Unreachable`.
   */
  private def becomeUnreachable(node: Member): Either[WorldViewError, WorldView] =
    if (node === self) {
      SelfCannotBeUnreachable(node).asLeft
    } else if (allStatuses.contains(node)) {
      copy(otherStatuses = otherStatuses + (node -> Unreachable)).asRight
    } else {
      UnknownNode(node).asLeft
    }

  /**
   * Change the `node`'s state to `Reachable`.
   */
  private def becomeReachable(node: Member): Either[WorldViewError, WorldView] = {
    def update(worldView: => WorldView)(status: Status): Either[WorldViewError, WorldView] = status match {
      case Reachable   => NodeAlreadyReachable(node).asLeft
      case Unreachable => worldView.asRight
      case Staged      => NodeStillStaged(node).asLeft
    }

    if (node === self) {
      update(copy(selfStatus = Reachable))(selfStatus)
    } else {
      statusOf(node).fold[Either[WorldViewError, WorldView]](UnknownNode(node).asLeft)(
        update(copy(otherStatuses = otherStatuses + (node -> Reachable)))
      )
    }
  }

  private[sbr] val allStatuses: NonEmptyMap[Member, Status] = NonEmptyMap(self -> selfStatus, otherStatuses)
}

object WorldView {
  def init(self: Member): WorldView = WorldView(self, Staged, SortedMap(self -> Staged))

  // todo test
  def apply(self: Member, state: CurrentClusterState): WorldView = {
    val unreachableMembers: SortedMap[Member, Unreachable.type] =
      state.unreachable
        .map(_ -> Unreachable)(collection.breakOut)

    val reachableMembers: SortedMap[Member, Status] =
      state.members
        .diff(state.unreachable)
        .map { m =>
          m.status match {
            case Joining | WeaklyUp => m -> Staged
            case _                  => m -> Reachable
          }
        }(collection.breakOut)

    WorldView(self,
              reachableMembers.getOrElse(self, Staged),
              unreachableMembers ++ reachableMembers.filterKeys(_ === self)) // Self is added separately
  }

  sealed abstract class WorldViewError(message: String) extends Throwable(message) {
    val node: Member
  }

  object WorldViewError {
    implicit val worldViewErrorEq: Eq[WorldViewError] = new Eq[WorldViewError] {
      override def eqv(x: WorldViewError, y: WorldViewError): Boolean = (x, y) match {
        case (_: UnknownNode, _: UnknownNode)                           => x.node === y.node
        case (_: NodeStillStaged, _: NodeStillStaged)                   => x.node === y.node
        case (_: NodeAlreadyCounted, _: NodeAlreadyCounted)             => x.node === y.node
        case (_: NodeAlreadyReachable, _: NodeAlreadyReachable)         => x.node === y.node
        case (_: NodeAlreadyStaged, _: NodeAlreadyStaged)               => x.node === y.node
        case (_: SelfCannotBeUnreachable, _: SelfCannotBeUnreachable)   => x.node === y.node
        case (_: CannotDownNonUnreachable, _: CannotDownNonUnreachable) => x.node === y.node
        case (_: CannotRemoveSelf, _: CannotRemoveSelf)                 => x.node === y.node
        case _                                                          => false
      }
    }
  }

  final case class UnknownNode(node: Member)              extends WorldViewError(s"$node")
  final case class NodeStillStaged(node: Member)          extends WorldViewError(s"$node")
  final case class NodeAlreadyCounted(node: Member)       extends WorldViewError(s"$node")
  final case class NodeAlreadyReachable(node: Member)     extends WorldViewError(s"$node")
  final case class NodeAlreadyStaged(node: Member)        extends WorldViewError(s"$node")
  final case class SelfCannotBeUnreachable(node: Member)  extends WorldViewError(s"$node")
  final case class CannotDownNonUnreachable(node: Member) extends WorldViewError(s"$node")
  final case class CannotRemoveSelf(node: Member)         extends WorldViewError(s"$node")

  implicit val worldViewEq: Eq[WorldView] = new Eq[WorldView] {
    override def eqv(x: WorldView, y: WorldView): Boolean =
      x.self === y.self && x.selfStatus === y.selfStatus && x.otherStatuses === y.otherStatuses
  }
}
