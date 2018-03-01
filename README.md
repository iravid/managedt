[![Build Status](https://travis-ci.org/iravid/managedt.svg?branch=master)](https://travis-ci.org/iravid/managedt)
[![Download](https://api.bintray.com/packages/iravid/maven/managedt/images/download.svg) ](https://bintray.com/iravid/maven/managedt/_latestVersion)

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
- you throw exceptions in the acquire or cleanup actions that are not sequenced
  into the `F[_]`;
- you pass `Future` values that have already been executed in the acquire or
  cleanup functions. The library has constructors with by-name values in most
  places to assist with that.

The test suite exercises the common usecases; you are welcome to have a look.

## Usage

### SBT

```scala
resolvers += Resolver.jcenterRepo
libraryDependencies += ״com.iravid" %% "managedt" % "0.1"
```

### In your project

First, create some resources:

```scala
import com.iravid.managedt.ManagedT
// import com.iravid.managedt.ManagedT

import monix.eval.Task
// import monix.eval.Task

val resource1 = ManagedT(Task { println("Acquiring 1"); 1 })(r => Task(println(s"Cleaning $r")))
// resource1: com.iravid.managedt.ManagedT[monix.eval.Task,Int] = com.iravid.managedt.ManagedT$$anon$4@7a33d7a

val resource2 = ManagedT(Task { println("Acquiring 2"); 2 })(r => Task(println(s"Cleaning $r")))
// resource2: com.iravid.managedt.ManagedT[monix.eval.Task,Int] = com.iravid.managedt.ManagedT$$anon$4@6615cfaa
```

Compose them - `ManagedT[F, R]` is a monad in `R`, so you can use the usual forms of composition:
```scala
import cats.implicits._
// import cats.implicits._

val zipped = (resource1, resource2).tupled
// zipped: com.iravid.managedt.ManagedT[monix.eval.Task,(Int, Int)] = com.iravid.managedt.ManagedT$$anon$1$$anon$5@640dc942

val added = for {
  r1 <- resource1
  r2 <- resource2
} yield r1 + r2
// added: com.iravid.managedt.ManagedT[monix.eval.Task,Int] = com.iravid.managedt.ManagedT$$anon$1$$anon$5@9c99dfa

val resources = (1 to 10).toList traverse { r =>
  ManagedT(Task { println(s"Acquiring $r"); r})(r => Task(println(s"Cleaning $r")))
}
// resources: com.iravid.managedt.ManagedT[monix.eval.Task,List[Int]] = com.iravid.managedt.ManagedT$$anon$1$$anon$5@127a5af
```

And, finally, use your acquired resource in a function of the form `R => F[A]`:
```scala
val zippedUsage = zipped { case (r1, r2) => 
  Task {
    println("Using zipped"); r1 + r2 
  }
}
// zippedUsage: monix.eval.Task[Int] = Task.FlatMap$1674067291

val addedUsage = added { r => 
  Task {
    println("Using added"); r * r 
  }
}
// addedUsage: monix.eval.Task[Int] = Task.FlatMap$2021842220

val resourcesUsage = resources { rs => 
  Task {
    println("Using resources")
    rs.sum 
  }
}
// resourcesUsage: monix.eval.Task[Int] = Task.FlatMap$1327562396
```

As we're using `Task`, nothing has actually run yet; we've composed programs that use the resources safely. Let's see what happens when we run them:

```scala
import monix.execution.Scheduler
implicit val scheduler = Scheduler.singleThread(name="tut")
```

```scala
import scala.concurrent.Await
// import scala.concurrent.Await

import scala.concurrent.duration._
// import scala.concurrent.duration._

Await.result(zippedUsage.runAsync, 5.seconds)
// Acquiring 1
// Acquiring 2
// Using zipped
// Cleaning 2
// Cleaning 1
// res0: Int = 3
```

The program constructed by `ManagedT[F, A]` properly acquires and releases resources in the right order. This also works for the traversed list of resources:
```scala
Await.result(resourcesUsage.runAsync, 5.seconds)
// Acquiring 1
// Acquiring 2
// Acquiring 3
// Acquiring 4
// Acquiring 5
// Acquiring 6
// Acquiring 7
// Acquiring 8
// Acquiring 9
// Acquiring 10
// Using resources
// Cleaning 10
// Cleaning 9
// Cleaning 8
// Cleaning 7
// Cleaning 6
// Cleaning 5
// Cleaning 4
// Cleaning 3
// Cleaning 2
// Cleaning 1
// res1: Int = 55
```

Should one of the acquires, clean-ups or uses fail, the clean-ups will still run properly:
```scala
val acquireFailure = for {
  _ <- ManagedT(Task.unit)(_ => Task { println("Cleaning 1") })
  _ <- ManagedT(Task.unit)(_ => Task { println("Cleaning 2")})
  _ <- ManagedT(Task.raiseError(new Exception))((_: Unit) => Task { println("Cleaning 3") })
} yield ()
// acquireFailure: com.iravid.managedt.ManagedT[monix.eval.Task,Unit] = com.iravid.managedt.ManagedT$$anon$1$$anon$5@31bb2b58

Await.result(acquireFailure(_ => Task.unit).attempt.runAsync, 5.seconds)
// Cleaning 2
// Cleaning 1
// res2: Either[Throwable,Unit] = Left(java.lang.Exception)

val useFailure = for {
  _ <- ManagedT(Task.unit)(_ => Task { println("Cleaning 1") })
  _ <- ManagedT(Task.unit)(_ => Task { println("Cleaning 2")})
  _ <- ManagedT(Task.unit)(_ => Task { println("Cleaning 3") })
} yield ()
// useFailure: com.iravid.managedt.ManagedT[monix.eval.Task,Unit] = com.iravid.managedt.ManagedT$$anon$1$$anon$5@2dcc81a1

Await.result(useFailure(_ => Task.raiseError(new Exception())).attempt.runAsync, 5.seconds)
// Cleaning 3
// Cleaning 2
// Cleaning 1
// res3: Either[Throwable,Nothing] = Left(java.lang.Exception)

val cleanupFailure = for {
  _ <- ManagedT(Task.unit)(_ => Task { println("Cleaning 1") })
  _ <- ManagedT(Task.unit)(_ => Task.raiseError(new Exception()))
  _ <- ManagedT(Task.unit)(_ => Task { println("Cleaning 3") })
} yield ()
// cleanupFailure: com.iravid.managedt.ManagedT[monix.eval.Task,Unit] = com.iravid.managedt.ManagedT$$anon$1$$anon$5@53e218e1

Await.result(cleanupFailure(_ => Task.unit).attempt.runAsync, 5.seconds)
// Cleaning 3
// Cleaning 1
// res4: Either[Throwable,Unit] = Left(java.lang.Exception)
```
