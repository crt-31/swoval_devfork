package com
package swoval
package files

import java.io.IOException
import java.nio.file.{ FileSystemLoopException, Files, Path, Paths }
import java.util.concurrent.atomic.AtomicBoolean

import com.swoval.files.PathWatchers.Event.Kind.{ Delete, Error, Modify }
import com.swoval.files.PathWatchers.{ Event, Overflow }
import com.swoval.functional.Consumer
import io.scalajs.nodejs
import io.scalajs.nodejs.fs.{ FSWatcherOptions, Fs }

import scala.concurrent.duration._
import scala.scalajs.js
import scala.scalajs.js.timers._
import scala.util.Try

/**
 * Native directory watcher implementation for Linux and Windows
 */
private[files] class NioPathWatcherService(
    eventConsumer: Consumer[functional.Either[Overflow, Event]],
    registerable: RegisterableWatchService)
    extends AutoCloseable {
  private[this] var closed = false
  private[this] val options = new FSWatcherOptions(recursive = false, persistent = false)
  private[this] var watchedDirectoriesByPath: Map[Path, WatchedDirectory] =
    Map.empty[Path, WatchedDirectory]

  override def close() {
    watchedDirectoriesByPath.toIndexedSeq.sortBy(_._1.toString).reverse.foreach(_._2.close())
    watchedDirectoriesByPath = Map.empty
  }
  def register(path: Path): functional.Either[IOException, WatchedDirectory] = {
    val realPath = Try(path.toRealPath()).getOrElse(path)
    if (path.startsWith(realPath) && !path.equals(realPath)) {
      functional.Either.left(new FileSystemLoopException(path.toString))
    } else {
      applyImpl(path)
    }
  }
  private def isValid(path: Path): Boolean = {
    val attrs = NioWrappers.readAttributes(path, LinkOption.NOFOLLOW_LINKS)
    attrs.isDirectory && !attrs.isSymbolicLink
  }
  private def applyImpl(path: Path): functional.Either[IOException, WatchedDirectory] = {
    try {
      functional.Either.right(watchedDirectoriesByPath get path match {
        case Some(w) => w
        case _ if isValid(path) =>
          val cb: js.Function2[nodejs.EventType, String, Unit] =
            (tpe: nodejs.EventType, name: String) => {
              val watchPath = path.resolve(Paths.get(if (name != null) name else ""))
              val exists = Files.exists(watchPath)
              val kind: Event.Kind = tpe match {
                case "rename" if !exists => Delete
                case _                   => Modify
              }
              eventConsumer.accept(
                functional.Either.right(new Event(TypedPaths.get(watchPath), kind)))
            }

          val closed = new AtomicBoolean(false)
          val watcher = Fs.watch(path.toString, options, cb)
          def setOnError(): Unit = {
            watcher.onError { _ =>
              closed.set(true)
              watcher.close()
              watchedDirectoriesByPath += path -> WatchedDirectories.INVALID
              eventConsumer.accept(functional.Either.right(new Event(TypedPaths.get(path), Error)))
            }
            ()
          }
          try setOnError()
          catch { case _: Exception => setTimeout(1.millis)(setOnError()) }
          val watchedDirectory: WatchedDirectory = new WatchedDirectory {
            override def close(): Unit = if (closed.compareAndSet(false, true)) {
              watchedDirectoriesByPath -= path
              watcher.close()
            }
          }
          watchedDirectoriesByPath += path -> watchedDirectory
          watchedDirectory
        case w =>
          w.foreach(_.close())
          watchedDirectoriesByPath += path -> WatchedDirectories.INVALID
          WatchedDirectories.INVALID
      })
    } catch {
      case e: IOException => functional.Either.left(e)
    }
  }
}
