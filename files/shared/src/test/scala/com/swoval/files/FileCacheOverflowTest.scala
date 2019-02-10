package com.swoval.files

import java.io.IOException
import java.nio.file.{ Path, Paths }

import com.swoval.files.FileCacheTest.FileCacheOps
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.TestHelpers.EntryOps._
import com.swoval.files.TestHelpers._
import com.swoval.files.test._
import com.swoval.runtime.Platform
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

trait FileCacheOverflowTest extends TestSuite with FileCacheTest {
  import FileCacheOverflowTest._
  def getBounded[T <: AnyRef](
      converter: FileTreeDataViews.Converter[T],
      cacheObserver: FileTreeDataViews.CacheObserver[T]
  ): FileTreeRepository[T] =
    FileCacheTest.get[T](
      false,
      converter,
      cacheObserver,
      (r: DirectoryRegistry) => {
        PathWatchers.get(false,
                         new BoundedWatchService(boundedQueueSize, RegisterableWatchServices.get()),
                         r)
      }
    )
  private val name = getClass.getSimpleName

  private val boundedQueueSize = System.getProperty("swoval.test.queue.size") match {
    case null => 4
    case c    => Try(c.toInt).getOrElse(4)
  }
  private val subdirsToAdd = System.getProperty("swoval.test.subdir.count") match {
    case null =>
      if (!Platform.isJVM) {
        if (Platform.isWin) 5 else 50
      } else 200
    case c => Try(c.toInt).getOrElse(200)
  }
  private val filesPerSubdir = System.getProperty("swoval.test.files.count") match {
    case null => 5
    case c    => Try(c.toInt).getOrElse(5)
  }
  private val timeout = DEFAULT_TIMEOUT * (if (Platform.isWin) 5 else 1)

  val testsImpl = Tests {
    'overflow - withTempDirectory { root =>
      val dir = root.resolve("overflow").resolve(name).createDirectories()
      // Windows is slow (at least on my vm)
      val executor = Executor.make("com.swoval.files.FileCacheTest.addmany.worker-thread")
      val creationLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
      val deletionLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
      val updateLatch = new CountDownLatch(subdirsToAdd)
      val subdirs = (1 to subdirsToAdd).map { i =>
        dir.resolve(s"subdir-$i")
      }
      val files = subdirs.flatMap { subdir =>
        (1 to filesPerSubdir).map { j =>
          subdir.resolve(s"file-$j")
        }
      }
      val allFiles = (subdirs ++ files).toSet
      val foundFiles = mutable.Set.empty[Path]
      val updatedFiles = mutable.Set.empty[Path]
      val deletedFiles = mutable.Set.empty[Path]
      val observer = getObserver[Path](
        (e: Entry[Path]) => {
          if (foundFiles.sync(_.add(e.path))) creationLatch.countDown()
        },
        (_: Entry[Path], e: Entry[Path]) => {
          if (foundFiles.sync(_.add(e.path))) creationLatch.countDown()
          if (Try(e.path.lastModified) == Success(3000)) {
            if (updatedFiles.sync(_.add(e.path))) {
              e.path.setLastModifiedTime(4000)
              updateLatch.countDown()
            }
          }
        },
        (e: Entry[Path]) => if (deletedFiles.sync(_.add(e.path))) deletionLatch.countDown(),
        (_: IOException) => {}
      )
      usingAsync(getBounded[Path](identity, observer)) { c =>
        c.reg(dir)
        executor.run(() => {
          subdirs.foreach(_.createDirectories())
          files.foreach(_.createFile())
        })
        creationLatch
          .waitFor(timeout) {
            val found = c.ls(dir).map(_.path).toSet
            // Need to synchronize since files is first set on a different thread
            allFiles.synchronized {
              found === allFiles
            }
          }
          .flatMap { _ =>
            executor.run(() => {
              val name = Paths.get("file-1")
              files.filter(_.getFileName == name).foreach(_.setLastModifiedTime(3000))
            })
            updateLatch
              .waitFor(timeout) {
                val found = c.ls(dir).map(_.path).toSet
                allFiles.synchronized {
                  found === allFiles
                }
              }
              .flatMap { _ =>
                executor.run(() => {
                  files.foreach(_.delete())
                  subdirs.foreach(_.delete())
                })
                deletionLatch
                  .waitFor(timeout) {
                    c.ls(dir) === Seq.empty
                  }
              }
          }
          .andThen {
            case Failure(e) =>
              println(s"Task failed $e")
              if (creationLatch.getCount > 0) {
                val count = creationLatch.getCount
                println((allFiles diff foundFiles).toSeq.take(10).sorted mkString "\n")
                10.milliseconds.sleep
                val newCount = creationLatch.getCount
                if (newCount == count)
                  println(s"$this Creation latch not triggered ($count)")
                else
                  println(
                    s"$this Creation latch not triggered, but still being decremented $newCount")
              }
              if (creationLatch.getCount <= 0 && updateLatch.getCount > 0) {
                val count = updateLatch.getCount
                10.milliseconds.sleep
                val newCount = updateLatch.getCount
                val expected = files.filter(_.getFileName.toString == "file-1").toSet
                println((expected diff updatedFiles.toSet).take(10).toSeq.sorted mkString "\n")
                if (newCount == count)
                  println(s"$this Update latch not triggered ($count)")
                else
                  println(
                    s"$this Update latch not triggered, but still being decremented $newCount")
              }
              if (creationLatch.getCount <= 0 && updateLatch.getCount <= 0 && deletionLatch.getCount > 0) {
                val count = deletionLatch.getCount
                10.milliseconds.sleep
                val newCount = deletionLatch.getCount
                if (newCount == count)
                  println(s"$this Deletion latch not triggered ($count)")
                else
                  println(
                    s"$this Deletion latch not triggered, but still being decremented $newCount")
                println((allFiles diff deletedFiles.toSet).toSeq.sorted.take(10) mkString "\n")
              }
              executor.close()
            case _ =>
              executor.close()
          }
      }
    }
  }
}
object FileCacheOverflowTest extends FileCacheOverflowTest with DefaultFileCacheTest {
  private implicit class SyncOps[T](val t: T) extends AnyVal {
    def sync[R](f: T => R): R = t.synchronized(f(t))
  }
  override def getBounded[T <: AnyRef](
      converter: FileTreeDataViews.Converter[T],
      cacheObserver: FileTreeDataViews.CacheObserver[T]): FileTreeRepository[T] =
    if (Platform.isMac) FileCacheTest.getCached(false, converter, cacheObserver)
    else super.getBounded(converter, cacheObserver)
  val tests = testsImpl
}
object NioFileCacheOverflowTest extends FileCacheOverflowTest with NioFileCacheTest {
  val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests('ignore - {
        println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
