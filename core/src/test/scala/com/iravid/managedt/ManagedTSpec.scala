package com.iravid.managedt

import cats.kernel.Eq
import monix.execution.Scheduler
import monix.eval.Task
import org.scalacheck.Arbitrary
import org.scalatest.{ FunSuite, Matchers }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.typelevel.discipline.scalatest.Discipline
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline._
import cats.implicits._
import scala.concurrent.Await
import scala.concurrent.duration._

class ManagedTSpec
    extends FunSuite with Discipline with GeneratorDrivenPropertyChecks with ScalaFutures
    with Matchers {
  import ManagedTSpec._
  import monix.execution.Scheduler.Implicits.global

  test("ManagedT invokes cleanups in reverse order of acquiry") {
    forAll { resources: List[String] =>
      var cleanups: List[String] = Nil

      val managed =
        resources.traverse(r => ManagedT(Task(r))(r => Task { cleanups = r :: cleanups }))

      val task = managed.unwrap

      val res2 = task.runAsync.futureValue

      res2 shouldBe resources
      cleanups shouldBe resources
    }
  }

  test("ManagedT invokes all cleanups, even if failures occur in cleanup") {
    forAll { resources: List[(String, Boolean)] =>
      var cleanups: List[String] = Nil

      val managed = resources traverse {
        case (resource, shouldFail) =>
          ManagedT(Task(resource)) { r =>
            cleanups = r :: cleanups

            if (shouldFail) Task.raiseError(new Exception())
            else Task.unit
          }
      }

      val task = managed.unwrap

      task.attempt.runAsync.futureValue

      cleanups shouldBe resources.unzip._1
    }
  }

  checkAll("Monad", MonadTests[ManagedT[Task, ?]].monad[String, String, String])
  checkAll("Monoid", MonoidTests[ManagedT[Task, String]].monoid)
}

object ManagedTSpec {
  implicit def taskEq[A: Eq](implicit S: Scheduler): Eq[Task[A]] = Eq.instance { (a, b) =>
    Await.result(a.runAsync, 30.seconds) === Await.result(b.runAsync, 30.seconds)
  }

  implicit def managedEq[R: Eq](implicit S: Scheduler): Eq[ManagedT[Task, R]] = Eq.by(_.unwrap)

  implicit def managedArbitrary[R: Arbitrary]: Arbitrary[ManagedT[Task, R]] =
    Arbitrary {
      Arbitrary.arbitrary[R].map { r =>
        ManagedT(Task(r))(_ => Task.unit)
      }
    }
}
