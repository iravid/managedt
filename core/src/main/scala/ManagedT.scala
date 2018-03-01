package com.iravid.managedt

import cats.{ Monad, MonadError, StackSafeMonad }
import cats.implicits._

abstract class ManagedT[F[_], R] {
  def apply[A](use: R => F[A]): F[A]
}

object ManagedT {
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
    }
}
