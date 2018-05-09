package com.iravid.managedt

import cats.effect.IO
import cats.kernel.Eq
import org.scalacheck.Arbitrary
import org.scalatest.{ FunSuite, Matchers }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.typelevel.discipline.scalatest.Discipline
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline._
import cats.implicits._

class ManagedTIOSpec
    extends FunSuite with Discipline with GeneratorDrivenPropertyChecks with ScalaFutures
    with Matchers {
  import ManagedTIOSpec._

  // used to mute stack traces from exceptions thrown
  // single abstract method syntax only supported in 2.12 (or 2.11 with experimental)
  Thread.setDefaultUncaughtExceptionHandler(new java.lang.Thread.UncaughtExceptionHandler {
    def uncaughtException(t: Thread, e: Throwable): Unit = ()
  })

  test("ManagedT invokes cleanups in reverse order of acquiry") {
    forAll { resources: List[String] =>
      var cleanups: List[String] = Nil

      val managed =
        resources.traverse(r => ManagedT(IO(r))(r => IO { cleanups = r :: cleanups }))

      val task = managed.unwrap

      val res2 = task.unsafeRunSync

      res2 shouldBe resources
      cleanups shouldBe resources
    }
  }

  test("ManagedT invokes all cleanups, even if failures occur in cleanup") {
    forAll { resources: List[(String, Boolean)] =>
      var cleanups: List[String] = Nil

      val managed = resources traverse {
        case (resource, shouldFail) =>
          ManagedT(IO(resource)) { r =>
            cleanups = r :: cleanups

            if (shouldFail) IO.raiseError(new Exception())
            else IO.unit
          }
      }

      val task = managed.unwrap

      task.attempt.unsafeRunSync

      cleanups shouldBe resources.unzip._1
    }
  }

  checkAll("Monad", MonadTests[ManagedT[IO, ?]].monad[String, String, String])
  checkAll("Monoid", MonoidTests[ManagedT[IO, String]].monoid)
}

object ManagedTIOSpec {
  implicit def ioEq[A: Eq]: Eq[IO[A]] = Eq.instance { (a, b) =>
    a.unsafeRunSync === b.unsafeRunSync
  }

  implicit def managedEq[R: Eq]: Eq[ManagedT[IO, R]] = Eq.by(_.unwrap)

  implicit def managedArbitrary[R: Arbitrary]: Arbitrary[ManagedT[IO, R]] =
    Arbitrary {
      Arbitrary.arbitrary[R].map { r =>
        ManagedT(IO(r))(_ => IO.unit)
      }
    }
}
