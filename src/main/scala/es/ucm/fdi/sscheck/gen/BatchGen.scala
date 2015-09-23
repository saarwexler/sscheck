package es.ucm.fdi.sscheck.gen

import org.scalacheck.Gen
import org.scalacheck.Shrink
import org.scalacheck.Shrink.shrink
import scala.language.implicitConversions

/**
 * Generators for Batch, a class for representing batches of elements 
 *  in a discrete data stream. This is a local representation, but you
 *  can use `import es.ucm.fdi.sscheck.gen.RDDGen._` to get an automatic
 *  conversion from `Gen[Batch[A]]` to `Gen[RDD[A]]` 
 * 
 * All the temporal generators defined in this object are sized generators, but the size parameters
 * only affects the number of batches in the output DStream, not the size of the batches. 
 * The size of the batches can be changed by using Gen.resize in the definition of the batch
 * generator that is passed to these HO generators
 * 
 * On the other hand for generators of arbitrary DStreams like Gen.resize(5, arbitrary[DStream[Int]])
 * Gen.resize has effect, both in the number of batches and in the size of the batches, as those
 * arbitrary instances are based on arbitrary for lists
 * 
 *  TODO  That should be tested
  // by using Gen.resize(5, TLGen.always(Batch(List(0, 1)))) (constant batch size)
  // and then in Gen.resize(3, TLGen.always(BatchGen.ofNtoM(1, 5, Gen.choose(0, 3))))
  // the point is that we always have 3 batches, but Gen.resize doesn't affect the batch
  // size but only the number of batches
  // 
 *
 * */
object BatchGen {
  def apply[A](bg : Gen[Batch[A]]) : BatchGen[A] = new BatchGen(bg)
  implicit def genBatch2BatchGen[A](bg : Gen[Batch[A]]) : BatchGen[A] = BatchGen(bg)
  
  implicit def batchGen2seqGen[A](g : Gen[Batch[A]]) : Gen[Seq[A]] = g.map(_.toSeq)
  implicit def seqGen2batchGen[A](g : Gen[Seq[A]]) : Gen[Batch[A]] = g.map(Batch(_:_*))
    
  /** Shrink function for Batch 
   * 
   * Although a Batch is a Seq, we need this simple definition of 
   * shrink as the default doesn't work properly
   * 
   * Note shrinking assumes no particular structure on the batches, but 
   * that is no problem as there is no temporal structure in the 
   * Batch generators: note generators like BatchGen.now() are in 
   * fact generators of PDStream 
   * */
  implicit def shrinkBatch[A] : Shrink[Batch[A]] = Shrink( batch =>
    // unwrap the underlying Seq, shrink the Seq, and rewrap
    shrink(batch.toSeq).map(Batch(_:_*))
  )
  
  /** @returns a generator of Batch that generates its elements from g
   * */
  def of[T](g : => Gen[T]) : Gen[Batch[T]] = {
	import Buildables.buildableBatch
	Gen.containerOf[Batch, T](g)  
  }
	
  /** @returns a generator of Batch that generates its elements from g
  * */
  def ofN[T](n : Int, g : Gen[T]) : Gen[Batch[T]] = {
    import Buildables.buildableBatch
    Gen.containerOfN[Batch, T](n, g)  
  }
	
  /** @returns a generator of Batch that generates its elements from g
  * */
  def ofNtoM[T](n : Int, m : Int, g : Gen[T]) : Gen[Batch[T]] = {
	import Buildables.buildableBatch
    UtilsGen.containerOfNtoM[Batch, T](n, m, g)  
  }
  
  /** @return a dstream generator with exactly bg as the only batch
  * */
  def now[A](bg : Gen[Batch[A]]) : Gen[PDStream[A]] = {
    bg.map(PDStream(_))
  } 
  
  /** @return a dstream generator that has exactly two batches, the first one
   *  is emptyBatch, and the second one is bg  
   * */
  def next[A](bg : Gen[Batch[A]]) : Gen[PDStream[A]] = {
    PDStreamGen.next(now(bg))
  } 
  
  /** @return a dstream generator that has n empty batches followed by bg
   * */
  def laterN[A](n : Int, bg : Gen[Batch[A]]) : Gen[PDStream[A]] = {
    PDStreamGen.laterN(n, now(bg))
  }
  
  /**
   * @return a generator of DStream that repeats batches generated by gb1 until 
   * a batch generated by bg2 is produced
   * 
   * Note bg2 always occurs eventually, so this is not a weak until. When bg2 occurs
   * then the generated DStream finishes 
   * This generator is exclusive in the sense that when bg2 finally happens then bg1 
   * doesn't occur. 
   * 
   * Example: 
   * 
   * Gen.resize(10,
  		always(BatchGen.ofN(2, 3))
  	 +  until(BatchGen.ofN(2, 0), BatchGen.ofN(2, 1))
     ). sample   //> res34: Option[es.ucm.fdi.sscheck.DStream[Int]] = Some(DStream(Batch(3, 3, 0
                 //| , 0), Batch(3, 3, 0, 0), Batch(3, 3, 1, 1), Batch(3, 3), Batch(3, 3), Batch
                 //| (3, 3), Batch(3, 3), Batch(3, 3), Batch(3, 3), Batch(3, 3)))
   * */
  def until[A](bg1 : Gen[Batch[A]], bg2 : Gen[Batch[A]]) : Gen[PDStream[A]] =
    PDStreamGen.until(now(bg1), now(bg2))
    
  def eventually[A](bg : Gen[Batch[A]]) : Gen[PDStream[A]] =
    // note true is represented here as Batch.empty, as true
    // asks for nothing to happen
    // until(Batch.empty : Batch[A], bg)
    PDStreamGen.eventually(now(bg)) 

  def always[A](bg : Gen[Batch[A]]) : Gen[PDStream[A]] =
    PDStreamGen.always(now(bg))
    
  /** 
   *  Generator for the weak version of LTL release operator: either bg2
   *  happens forever, or it happens until bg1 happens, including the
   *  moment when bg1 happens  
   * */
  def release[A](bg1 : Gen[Batch[A]], bg2 : Gen[Batch[A]]) : Gen[PDStream[A]] =
    PDStreamGen.release(now(bg1), now(bg2))
}
  
class BatchGen[A](self : Gen[Batch[A]]) {
  import BatchGen._
  /** Returns the generator that results from concatenating the sequences
   *  generated by the generator wrapped by this, and other. This only makes
   *  sense when ordered batches are considered
   * */
  def ++(other : Gen[Batch[A]]) : Gen[Batch[A]] = UtilsGen.concSeq(self, other)
  
  /** @return a generator for the concatenation of the generated batches  
  */
  def +(other : Gen[Batch[A]]) : Gen[Batch[A]] = 
    for {
      b1 <- self
      b2 <- other
    } yield b1 ++ b2
    
  def next : Gen[PDStream[A]] = BatchGen.next(self)
  def laterN(n : Int) : Gen[PDStream[A]] = BatchGen.laterN(n, self)
  def eventually : Gen[PDStream[A]] = BatchGen.eventually(self)
  def until(other : Gen[Batch[A]]) : Gen[PDStream[A]] = BatchGen.until(self, other)
  def always : Gen[PDStream[A]] = BatchGen.always(self)
  def release(other : Gen[Batch[A]]) : Gen[PDStream[A]] = BatchGen.release(self, other)
}