package data
package iteratee
package base

import scalaz._, Scalaz._

sealed trait Stream[S]
final case class EOF[S](error: Option[Error]) extends Stream[S]
final case class Chunk[S](value: S) extends Stream[S]

trait For[S,M[_],A] { self: Iteratee[S,M,A] => 
  def flatMap[B](f: A => Iteratee[S,M,B])(implicit b: Bind[({type λ[α] = Iteratee[S,M,α]})#λ]): Iteratee[S,M,B] = b.bind(self,f)
  def map[B](f: A => B)(implicit t: Functor[({type λ[α] = Iteratee[S,M,α]})#λ]): Iteratee[S,M,B] = t.fmap(self,f)
}

trait Iteratee[S,M[_],A] extends For[S,M,A] {
  type Done[S,M[_],A,R] = (A,Stream[S]) => M[R]
  type Cont[S,M[_],A,R] = ((Stream[S] => Iteratee[S,M,A]), Option[Error]) => M[R]
  def fold[R](done: Done[S,M,A,R], cont: Cont[S,M,A,R]): M[R]
}

object Iteratee {
  def idone[S,M[_]:Monad,A](a: A, s: Stream[S]): Iteratee[S,M,A] = {
    new Iteratee[S,M,A] {
      def fold[R](done: Done[S,M,A,R], cont: Cont[S,M,A,R]): M[R] = done(a,s)
    }
  }

  def icont[S,M[_]:Monad,A](k: Stream[S] => Iteratee[S,M,A], e: Option[Error]): Iteratee[S,M,A] = {
    new Iteratee[S,M,A] {
      def fold[R](done: Done[S,M,A,R], cont: Cont[S,M,A,R]): M[R] = cont(k,e)
    }
  }

  def liftI[S,M[_]:Monad,A](k: Stream[S] => Iteratee[S,M,A]): Iteratee[S,M,A] = {
    new Iteratee[S,M,A] {
      def fold[R](done: Done[S,M,A,R], cont: Cont[S,M,A,R]): M[R] = cont(k,None)
    }
  }

  def idoneM[S,M[_]:Monad,A](a: A, s: Stream[S]): M[Iteratee[S,M,A]] = {
    val iter = new Iteratee[S,M,A] {
      def fold[R](done: Done[S,M,A,R], cont: Cont[S,M,A,R]): M[R] = done(a,s)
    }
    iter.pure
  }

  implicit def IterateePure[S,M[_]:Monad]: Pure[({type λ[α] = Iteratee[S,M,α]})#λ] = {
    new Pure[({type λ[α] = Iteratee[S,M,α]})#λ] {
      def pure[A](a: => A): Iteratee[S,M,A] = idone(a,EOF(None))
    }
  }
   
  implicit def IterateeBind[S,M[_]:Monad]: Bind[({type λ[α] = Iteratee[S,M,α]})#λ] = {
    new Bind[({type λ[α] = Iteratee[S,M,α]})#λ] {
      def bind[A,B](m: Iteratee[S,M,A], f: A => Iteratee[S,M,B]): Iteratee[S,M,B] = {
        new Iteratee[S,M,B] {
          def fold[R](done: Done[S,M,B,R], cont: Cont[S,M,B,R]): M[R] = m.fold(
            done = {
              case (a,s) => f(a).fold(
                done = done,
                cont = {
                  case (k,None) => k(s).fold(done = done, cont = cont)
                  case (k,e) => cont(k,e)
                }
              )
            },
            cont = (k,e) => cont(s => bind(k(s),f),e)
          )
        }
      }
    }
  }

  implicit def IterateeFunctor[S,M[_]:Monad]: Functor[({type λ[α] = Iteratee[S,M,α]})#λ] = {
    new Functor[({type λ[α] = Iteratee[S,M,α]})#λ] {
      def fmap[A,B](m: Iteratee[S,M,A], f: A => B): Iteratee[S,M,B] = {
        new Iteratee[S,M,B] {
          def fold[R](done: Done[S,M,B,R], cont: Cont[S,M,B,R]): M[R] = m.fold(
            done = (a,s) => done(f(a),s),
            cont = (k,e) => cont(s => fmap(k(s),f),e)
          )
        }
      }
    }
  }
}

object Iteratees {
  import Iteratee._

  def head[M[_]:Monad]: Iteratee[String,M,Char] = {
    def step: Stream[String] => Iteratee[String,M,Char] = {
      case Chunk(cs) if cs.isEmpty => icont(step, None)
      case Chunk(cs) => idone(cs head, Chunk(cs tail))
      case stream => icont(step, Some("error"))
    }
    liftI(step)
  }

  def run[S,M[_]:Monad,A](iter: Iteratee[S,M,A]): M[A] = iter.fold(
    done = (a,_) => a.pure,
    cont = {
      case (k,None) => k(EOF(None)).fold(
        done = (a,_) => a.pure,
        cont = {
          case (_,None) => throw new RuntimeException("eof")
          case (_,Some(e)) => throw new RuntimeException(e)
        }
      )
      case (_,Some(e)) => throw new RuntimeException(e)
    }
  )
}

trait Enumerator[S,A] {
  def apply[M[_]:Monad](iter: Iteratee[S,M,A]): M[Iteratee[S,M,A]]
}

object Enumeratees {
  import Iteratee._

  def enumPure1Chunk[A](s: String): Enumerator[String,A] = {
    new Enumerator[String,A] {
      def apply[M[_]:Monad](iter: Iteratee[String,M,A]): M[Iteratee[String,M,A]] = iter.fold(
        done = (a,t) => idoneM(a, Chunk(s + t)),
        cont = {
          case (k,None) => k(Chunk(s)).pure
          case (k,e) => icont(k,e).pure
        }
      )
    }
  }
}

// TODO: ethul, remove this or move this
case class Id[A](value: A)
object Id {
  implicit def IdPure: Pure[Id] = new Pure[Id] {
    def pure[A](a: => A): Id[A] = Id(a)
  }
  implicit def IdBind: Bind[Id] = new Bind[Id] {
    def bind[A,B](m: Id[A], f: A => Id[B]): Id[B] = f(m value)
  }
  implicit def IdFunctor: Functor[Id] = new Functor[Id] {
    def fmap[A,B](m: Id[A], f: A => B): Id[B] = Id(f(m value))
  }
} 

// TODO: ethul, remove this or move this
object Temp {
  import Iteratees._
  import Enumeratees._

  def main(args: Array[String]) = {
    var r =
      for {
        a <- head[Id]
        b <- head[Id]
        c <- head[Id]
      } yield (a,b,c)

    val e = enumPure1Chunk("abc")(r)
    println(run(e.value))
  }
}
