package com.iravid.managedt

import cats.kernel.{ Monoid, Semigroup }
import cats.{ Applicative, Monad, MonadError, StackSafeMonad }
import cats.implicits._

abstract class ManagedT[F[_], R] {

  /**
    * Use the resource in this ManagedT in the provided function. Cleanup handlers will
    * be sequenced after the F[A] returned from use.
    */
  def apply[A](use: R => F[A]): F[A]

  /**
    * Extract the resource in this ManagedT. The cleanup handlers will have already run
    * before actions sequenced after the returned F[A].
    *
    * Using this doesn't make much sense if the cleanup handlers invalidate the resource,
    * so use with caution.
    */
  def unwrap(implicit F: Applicative[F]): F[R] = apply(F.pure)
}

object ManagedT extends ManagedTLowPriority {

  /**
    * Construct a new ManagedT that acquires a resource using `acquire` and releases it
    * with `cleanup` after it is used.
    */
  def apply[F[_], R](acquire: => F[R])(cleanup: R => F[Unit])(
    implicit FE: MonadError[F, Throwable]): ManagedT[F, R] =
    new ManagedT[F, R] {
      def apply[A](use: R => F[A]): F[A] =
        for {
          resource      <- acquire
          resultOrError <- use(resource).attempt
          result <- resultOrError match {
                     case Left(e)  => cleanup(resource) *> e.raiseError[F, A]
                     case Right(a) => cleanup(resource) as a
                   }
        } yield result
    }

  /**
    * Lifts a value in `F` into `ManagedT[F, A]` *with no cleanup handler*. Use with
    * caution!
    */
  def liftF[F[_], A](fa: => F[A])(implicit FE: MonadError[F, Throwable]): ManagedT[F, A] =
    ManagedT(fa)(_ => FE.unit)

  /**
    * Lifts a value into `ManagedT[F, A]` *with no cleanup handler*. Use with
    * caution!
    */
  def pure[F[_]: MonadError[?[_], Throwable]] = new PartiallyAppliedBuilder[F]
  class PartiallyAppliedBuilder[F[_]](implicit FE: MonadError[F, Throwable]) {
    def apply[A](a: => A): ManagedT[F, A] = liftF(FE.pure(a))
  }

  implicit def monad[F[_]](
    implicit
    FE: MonadError[F, Throwable]): Monad[ManagedT[F, ?]] =
    new Monad[ManagedT[F, ?]] with StackSafeMonad[ManagedT[F, ?]] {
      def pure[A](x: A): ManagedT[F, A] =
        apply(x.pure[F])(_ => FE.unit)

      def flatMap[R1, R2](fa: ManagedT[F, R1])(f: R1 => ManagedT[F, R2]): ManagedT[F, R2] =
        new ManagedT[F, R2] {
          def apply[A](use: R2 => F[A]): F[A] =
            fa { r1 =>
              f(r1) { r2 =>
                use(r2)
              }
            }
        }

      override def tailRecM[R1, R2](r1: R1)(f: R1 => ManagedT[F, Either[R1, R2]]): ManagedT[F, R2] =
        new ManagedT[F, R2] {
          def apply[A](use: R2 => F[A]): F[A] =
            f(r1) {
              case Left(r1)  => tailRecM(r1)(f)(use)
              case Right(r2) => use(r2)
            }
        }
    }

  implicit def monoid[F[_], A](implicit A: Monoid[A],
                               FE: MonadError[F, Throwable]): Monoid[ManagedT[F, A]] =
    new Monoid[ManagedT[F, A]] {
      def combine(x: ManagedT[F, A], y: ManagedT[F, A]): ManagedT[F, A] =
        monad[F].map2(x, y)(A.combine)

      def empty: ManagedT[F, A] = monad[F].pure(A.empty)
    }
}

trait ManagedTLowPriority {
  implicit def semigroup[F[_], A](implicit FE: MonadError[F, Throwable],
                                  A: Semigroup[A]): Semigroup[ManagedT[F, A]] =
    new Semigroup[ManagedT[F, A]] {
      def combine(x: ManagedT[F, A], y: ManagedT[F, A]): ManagedT[F, A] =
        ManagedT.monad[F].map2(x, y)(A.combine)
    }
}
