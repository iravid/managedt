[![Build Status](https://travis-ci.org/iravid/managedt.svg?branch=master)](https://travis-ci.org/iravid/managedt)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.iravid/managedt_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.iravid/managedt_2.12)

# ManagedT: a monad for resource management

`ManagedT[F[_], A]` is a monad for constructing computations that acquire and
release resources. It is a translation of Gabriel Gonzalez'
[managed](https://hackage.haskell.org/package/managed) library, with the
difference that this library abstracts over the effect type.

This library only contains typeclass instances for
[cats](github.com/typelevel/cats). Scalaz already contains the `Codensity` data
type which can serve the same functionality (with, albeit, some additional
plumbing for cleanup handlers).

## IMPORTANT CAVEATS

This library supports any `F[_]` that has `MonadError[F, Throwable]`. That
includes `monix.eval.Task`, `cats.effect.IO`, `scala.concurrent.Future` (and
others). 

However, it is very likely to be broken if:
- you use cancellation with `Task` or `IO` - `MonadError` is not strong enough to express
  bracketing with cancellation, so that'll have to wait for `MonadBracket` in
  `cats-effect`;
- you use it with a monad transformer such as `EitherT[IO, E, A]`, where there
  are several "layers" in which errors could be thrown;
- you throw exceptions in the acquire or cleanup actions that are not sequenced
  into the `F[_]`;
- you pass `Future` values that have already been executed in the acquire or
  cleanup functions. The library has constructors with by-name values in most
  places to assist with that.

Other than that, it's all good ;-) The test suite exercises the common usecases;
you are welcome to have a look.

## Usage

### SBT

```scala
libraryDependencies += ״com.iravid" %% "managedt" % "0.1"
```

### In your project

First, create some resources:

```tut:book
import com.iravid.managedt.ManagedT
import cats.effect.IO

val resource1 = ManagedT(IO { println("Acquiring 1"); 1 })(r => IO(println(s"Cleaning $r")))
val resource2 = ManagedT(IO { println("Acquiring 2"); 2 })(r => IO(println(s"Cleaning $r")))
```

Compose them - `ManagedT[F, R]` is a monad in `R`, so you can use the usual forms of composition:
```tut:book
import cats.implicits._

val zipped = (resource1, resource2).tupled
val added = for {
  r1 <- resource1
  r2 <- resource2
} yield r1 + r2

val resources = (1 to 10).toList traverse { r =>
  ManagedT(IO { println(s"Acquiring $r"); r})(r => IO(println(s"Cleaning $r")))
}
```

And, finally, use your acquired resource in a function of the form `R => F[A]`:
```tut:book
val zippedUsage = zipped { case (r1, r2) => 
  IO {
    println("Using zipped"); r1 + r2 
  }
}

val addedUsage = added { r => 
  IO {
    println("Using added"); r * r 
  }
}

val resourcesUsage = resources { rs => 
  IO {
    println("Using resources")
    rs.sum 
  }
}
```

As we're using `IO`, nothing has actually run yet; we've composed programs that use the resources safely. Let's see what happens when we run them:

```tut:book
zippedUsage.unsafeRunSync
```

The program constructed by `ManagedT[F, A]` properly acquires and releases resources in the right order. This also works for the traversed list of resources:
```tut:book
resourcesUsage.unsafeRunSync
```

Should one of the acquires, clean-ups or uses fail, the clean-ups will still run properly:
```tut:book
val acquireFailure = for {
  _ <- ManagedT(IO.unit)(_ => IO { println("Cleaning 1") })
  _ <- ManagedT(IO.unit)(_ => IO { println("Cleaning 2")})
  _ <- ManagedT(IO.raiseError(new Exception))((_: Unit) => IO { println("Cleaning 3") })
} yield ()

acquireFailure(_ => IO.unit).attempt.unsafeRunSync

val useFailure = for {
  _ <- ManagedT(IO.unit)(_ => IO { println("Cleaning 1") })
  _ <- ManagedT(IO.unit)(_ => IO { println("Cleaning 2")})
  _ <- ManagedT(IO.unit)(_ => IO { println("Cleaning 3") })
} yield ()

useFailure(_ => IO.raiseError(new Exception())).attempt.unsafeRunSync

val cleanupFailure = for {
  _ <- ManagedT(IO.unit)(_ => IO { println("Cleaning 1") })
  _ <- ManagedT(IO.unit)(_ => IO.raiseError(new Exception()))
  _ <- ManagedT(IO.unit)(_ => IO { println("Cleaning 3") })
} yield ()

cleanupFailure(_ => IO.unit).attempt.unsafeRunSync
```
