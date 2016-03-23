package com.stripe.brushfire

import com.twitter.algebird._

trait SingleRegionSplitter[V,T] extends Splitter[V,T] {
  type R = Unit
  def ordering = implicitly[Ordering[Unit]]

  def createRegions(value: V, target: T) = List(() -> create(value, target))
  def create(value: V, target: T): S
}

case class BinarySplitter[V: Ordering, T: Monoid](partition: V => Predicate[V])
 extends SingleRegionSplitter[V, T] {

  type S = Map[V, T]
  def create(value: V, target: T) = Map(value -> target)

  val semigroup = implicitly[Semigroup[Map[V, T]]]

  def split(parent: T, stats: Map[V, T]): Iterable[Split[V, T]] = {
    stats.keys.map { v =>
      val predicate = partition(v)
      val (trues, falses) = stats.partition { case (v, d) => predicate(v) }
      Split(predicate, Monoid.sum(trues.values), Monoid.sum(falses.values))
    }
  }
}

case class RandomSplitter[V, T](original: Splitter[V, T])
    extends SingleRegionSplitter[V, T] {
  type S = original.S
  val semigroup = original.semigroup
  def create(value: V, target: T) = original.create(value, target)
  def split(parent: T, stats: S): Iterable[Split[V, T]] =
    scala.util.Random.shuffle(original.split(parent, stats)).headOption
}

case class BinnedSplitter[V, T](original: Splitter[V, T])(fn: V => V)
    extends SingleRegionSplitter[V, T] {
  type S = original.S
  def create(value: V, target: T) = original.create(fn(value), target)
  val semigroup = original.semigroup
  def split(parent: T, stats: S): Iterable[Split[V, T]] =
    original.split(parent, stats)
}

case class QTreeSplitter[T: Monoid](k: Int)
    extends SingleRegionSplitter[Double, T] {

  type S = QTree[T]

  val semigroup = new QTreeSemigroup[T](k)
  def create(value: Double, target: T) = QTree(value -> target)

  def split(parent: T, stats: QTree[T]): Iterable[Split[Double, T]] = {
    findAllThresholds(stats).map { threshold =>
      val predicate = Predicate.Lt(threshold)
      val leftDist = stats.rangeSumBounds(stats.lowerBound, threshold)._1
      val rightDist = stats.rangeSumBounds(threshold, stats.upperBound)._1
      Split(predicate, leftDist, rightDist)
    }
  }

  def findAllThresholds(node: QTree[T]): Iterable[Double] = {
    node.lowerChild.toList.flatMap { l => findAllThresholds(l) } ++
      node.upperChild.toList.flatMap { u => findAllThresholds(u) } ++
      findThreshold(node).toList
  }

  def findThreshold(node: QTree[T]): Option[Double] = {
    for (l <- node.lowerChild; u <- node.upperChild)
      yield l.upperBound
  }
}

case class SpaceSaverSplitter[V, L](capacity: Int = 1000)
    extends SingleRegionSplitter[V, Map[L, Long]] {

  type S = Map[L, SpaceSaver[V]]
  val semigroup = implicitly[Semigroup[S]]

  def create(value: V, target: Map[L, Long]) = target.mapValues { c =>
    Semigroup.intTimes(c, SpaceSaver(capacity, value))
  }

  def split(parent: Map[L, Long], stats: S): Iterable[Split[V, Map[L, Long]]] =
    stats
      .values
      .flatMap(_.counters.keys).toSet
      .map { (v: V) =>
        val mins = stats.mapValues { ss => ss.frequency(v).min }
        Split(Predicate.isEq(v), mins, Group.minus(parent, mins))
      }
}
