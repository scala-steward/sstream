package com.evolutiongaming.sstream

import cats.data.IndexedStateT
import cats.effect.{Bracket, ExitCase, Resource}
import cats.implicits._
import cats.{Id, MonadError, ~>}
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuite

import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

class StreamSpec extends AnyFunSuite with Matchers {

  test("apply resource") {

    sealed trait Action

    object Action {
      case object Acquire extends Action
      case object Release extends Action
      case object Use extends Action
    }

    case class State(n: Int, actions: List[Action]) {
      def add(action: Action): State = copy(actions = action :: actions)
    }

    object State {
      def empty: State = State(0, List.empty)
    }


    type StateT[A] = cats.data.StateT[Try, State, A]

    object StateT {

      def apply[A](f: State => (State, A)): StateT[A] = {
        cats.data.StateT[Try, State, A] { state => Success(f(state)) }
      }
    }


    def bracketOf[F[_]](implicit F: MonadError[F, Throwable]): Bracket[F, Throwable] = new Bracket[F, Throwable] {

      def bracketCase[A, B](acquire: F[A])(use: A => F[B])(release: (A, ExitCase[Throwable]) => F[Unit]) = {

        def onError(a: A, error: Throwable) = {
          for {
            _ <- release(a, ExitCase.error(error))
            b <- raiseError[B](error)
          } yield b
        }

        for {
          a <- acquire
          b <- use(a).handleErrorWith(e => onError(a, e))
          _ <- release(a, ExitCase.complete)
        } yield b
      }

      def raiseError[A](e: Throwable) = F.raiseError(e)

      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]) = F.handleErrorWith(fa)(f)

      def flatMap[A, B](fa: F[A])(f: A => F[B]) = F.flatMap(fa)(f)

      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]) = F.tailRecM(a)(f)

      def pure[A](a: A) = F.pure(a)
    }


    implicit val bracket = bracketOf[StateT](IndexedStateT.catsDataMonadErrorForIndexedStateT[Try, State, Throwable])

    val increment = StateT { state =>
      val n = state.n + 1
      val state1 = state.copy(n = n).add(Action.Use)
      (state1, n)
    }

    val resource = Resource.make {
      StateT { state =>
        val state1 = state.add(Action.Acquire)
        (state1, increment)
      }
    } { _ =>
      StateT { state =>
        val state1 = state.add(Action.Release)
        (state1, ())
      }
    }

    val stream = for {
      a <- Stream[StateT].apply(resource)
      a <- Stream[StateT].repeat(a)
    } yield a

    val (state, value) = stream.take(2).toList.run(State.empty).get
    value shouldEqual List(1, 2)
    state shouldEqual State(2, List(
      Action.Release,
      Action.Use,
      Action.Use,
      Action.Acquire))
  }

  test("lift") {
    Stream.lift[Id, Int](0).toList shouldEqual List(0)
  }

  test("single") {
    Stream[Id].single(0).toList shouldEqual List(0)
  }

  test("empty") {
    Stream.empty[Id, Int].toList shouldEqual Nil
  }

  test("map") {
    Stream.lift[Id, Int](0).map(_ + 1).toList shouldEqual List(1)
  }

  test("flatMap") {
    val stream = Stream.lift[Id, Int](1)
    val stream1 = for {
      a <- stream
      b <- stream
    } yield a + b

    stream1.toList shouldEqual List(2)
  }

  test("take") {
    Stream.lift[Id, Int](0).take(3).toList shouldEqual List(0)

    Stream[Id].many(1, 2, 3).take(1).toList shouldEqual List(1)

    val error = new RuntimeException with NoStackTrace
    val stream = for {
      a <- Stream[Try].apply(List(Success(1), Failure(error)))
      a <- Stream.lift(a)
    } yield a
    stream.take(1).toList shouldEqual Success(List(1))
  }

  test("first") {
    Stream[Id].single(0).first shouldEqual Some(0)
    Stream.empty[Id, Int].first shouldEqual None
  }

  test("last") {
    Stream[Id].many(1, 2, 3).last shouldEqual Some(3)
    Stream.empty[Id, Int].last shouldEqual None
  }

  test("length") {
    Stream.repeat[Id, Int](0).take(3).length shouldEqual 3
  }

  test("repeat") {
    Stream.repeat[Id, Int](0).take(3).toList shouldEqual List.fill(3)(0)
  }

  test("filter") {
    Stream[Id].many(1, 2, 3).filter(_ >= 2).toList shouldEqual List(2, 3)
  }

  test("withFilter") {
    val stream = for {
      a <- Stream[Id].repeat(0) if a == 0
    } yield a
    stream.first shouldEqual 0.some
  }

  test("collect") {
    Stream[Id].many(1, 2, 3).collect { case x if x >= 2 => x + 1 }.toList shouldEqual List(3, 4)
  }

  test("zipWithIndex") {
    Stream.repeat[Id, Int](0).zipWithIndex.take(3).toList shouldEqual List((0, 0), (0, 1), (0, 2))
  }

  test("takeWhile") {
    Stream[Id].many(1, 2, 1).takeWhile(_ < 2).toList shouldEqual List(1)
  }

  test("dropWhile") {
    Stream[Id].many(1, 2, 1).dropWhile(_ < 2).toList shouldEqual List(2, 1)
  }

  test("around") {
    sealed trait Action

    object Action {
      case object Before extends Action
      case object Inside extends Action
      case object After extends Action
    }

    type State = List[Action]

    object State {
      def empty: State = List.empty
    }


    type StateT[A] = cats.data.StateT[Id, State, A]

    object StateT {

      def apply[A](f: State => (State, A)): StateT[A] = {
        cats.data.StateT[Id, State, A](f)
      }
    }

    val functionK = new (StateT ~> StateT) {
      def apply[A](fa: StateT[A]) = {
        StateT { state =>
          val state1 = Action.Before :: state
          val (state2, a) = fa.run(state1)
          val state3 = Action.After :: state2
          (state3, a)
        }
      }
    }

    val inside = StateT { state =>
      val state1 = Action.Inside :: state
      (state1, ())
    }

    val stream = for {
      _ <- Stream.around(functionK)
      _ <- Stream.lift(inside)
    } yield ()

    val (state, value) = stream.toList.run(State.empty)
    value shouldEqual List(())
    state shouldEqual List(
      Action.After,
      Action.Inside,
      Action.Before)
  }
}
