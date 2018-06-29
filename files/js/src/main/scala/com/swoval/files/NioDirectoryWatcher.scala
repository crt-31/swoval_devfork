// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.Directory.Entry.DIRECTORY
import com.swoval.files.DirectoryWatcher.Event.Create
import com.swoval.files.DirectoryWatcher.Event.Overflow
import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.Directory.Converter
import com.swoval.files.Directory.Observer
import com.swoval.functional.Consumer
import com.swoval.functional.Filter
import com.swoval.functional.IO
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Set
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

/**
 Provides a DirectoryWatcher that is backed by a [[java.nio.file.WatchService]].
 */
abstract class NioDirectoryWatcher(register: IO[Path, WatchedDirectory],
                                   protected val callbackExecutor: Executor,
                                   protected val executor: Executor,
                                   private val directoryRegistry: DirectoryRegistry,
                                   options: DirectoryWatcher.Option*)
    extends DirectoryWatcher {

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val rootDirectories: Map[Path, Directory[WatchedDirectory]] =
    new HashMap()

  private val pollNewDirectories: Boolean =
    ArrayOps.contains(options, DirectoryWatcher.Options.POLL_NEW_DIRECTORIES)

  private val converter: Converter[WatchedDirectory] =
    new Converter[WatchedDirectory]() {
      override def apply(path: Path): WatchedDirectory =
        register.apply(path).getOrElse(WatchedDirectories.INVALID)
    }

  private def getRoot(root: Path): Directory[WatchedDirectory] = {
    var result: Directory[WatchedDirectory] = rootDirectories.get(root)
    if (result == null) {
      try {
        result = new Directory(
          root,
          root,
          converter,
          java.lang.Integer.MAX_VALUE,
          new Filter[QuickFile]() {
            override def accept(quickFile: QuickFile): Boolean =
              directoryRegistry.accept(quickFile.toPath())
          }
        ).init()
        rootDirectories.put(root, result)
      } catch {
        case e: IOException => {}

      }
    }
    result
  }

  /**
   * Register a path to monitor for file events
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return an [[com.swoval.functional.Either]] containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  override def register(path: Path,
                        maxDepth: Int): com.swoval.functional.Either[IOException, Boolean] =
    executor
      .block(new Callable[Boolean]() {
        override def call(): Boolean = registerImpl(path, maxDepth)
      })
      .castLeft(classOf[IOException])

  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
  override def unregister(path: Path): Unit = {
    executor.block(new Runnable() {
      override def run(): Unit = {
        directoryRegistry.removeDirectory(path)
        val dir: Directory[WatchedDirectory] = getRoot(path.getRoot)
        if (dir != null) {
          val entries: List[Directory.Entry[WatchedDirectory]] =
            dir.list(true, AllPass)
          Collections.sort(entries)
          Collections.reverse(entries)
          val it: Iterator[Directory.Entry[WatchedDirectory]] =
            entries.iterator()
          while (it.hasNext) {
            val watchedDirectory: Directory.Entry[WatchedDirectory] = it.next()
            if (!directoryRegistry.accept(watchedDirectory.path)) {
              val toCancel: Iterator[Directory.Entry[WatchedDirectory]] =
                dir.remove(watchedDirectory.path).iterator()
              while (toCancel.hasNext) toCancel.next().getValue.close()
            }
          }
        }
      }
    })
  }

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      super.close()
      executor.block(new Runnable() {
        override def run(): Unit = {
          callbackExecutor.close()
          val it: Iterator[Directory[WatchedDirectory]] =
            rootDirectories.values.iterator()
          while (it.hasNext) {
            val dir: Directory[WatchedDirectory] = it.next()
            dir.entry().getValue.close()
            val entries: Iterator[Directory.Entry[WatchedDirectory]] =
              dir.list(true, AllPass).iterator()
            while (entries.hasNext) entries.next().getValue.close()
          }
        }
      })
      executor.close()
    }
  }

  private def maybeRunCallback(callback: Consumer[DirectoryWatcher.Event],
                               event: DirectoryWatcher.Event): Unit = {
    if (directoryRegistry.accept(event.path)) {
      callbackExecutor.run(new Runnable() {
        override def run(): Unit = {
          callback.accept(event)
        }
      })
    }
  }

  private def processPath(callback: Consumer[DirectoryWatcher.Event],
                          path: Path,
                          kind: DirectoryWatcher.Event.Kind,
                          processedDirs: HashSet[QuickFile],
                          processedFiles: HashSet[Path]): Unit = {
    val newFiles: Set[QuickFile] = new HashSet[QuickFile]()
    val maxDepth: Int = directoryRegistry.maxDepthFor(path)
    add(path,
        maxDepth -
          (if (maxDepth == java.lang.Integer.MAX_VALUE) 0 else 1),
        newFiles)
    if (processedFiles.add(path)) {
      maybeRunCallback(callback, new DirectoryWatcher.Event(path, kind))
      val it: Iterator[QuickFile] = newFiles.iterator()
      while (it.hasNext) {
        val file: QuickFile = it.next()
        if (file.isDirectory && processedDirs.add(file)) {
          processPath(callback,
                      file.toPath(),
                      DirectoryWatcher.Event.Create,
                      processedDirs,
                      processedFiles)
        } else if (processedFiles.add(file.toPath())) {
          maybeRunCallback(callback,
                           new DirectoryWatcher.Event(file.toPath(), DirectoryWatcher.Event.Create))
        }
      }
    }
  }

  protected def handleEvent(callback: Consumer[DirectoryWatcher.Event],
                            path: Path,
                            kind: DirectoryWatcher.Event.Kind): Unit = {
    if (!Files.exists(path)) {
      val root: Directory[WatchedDirectory] = rootDirectories.get(path.getRoot)
      if (root != null) {
        val it: Iterator[Directory.Entry[WatchedDirectory]] =
          root.remove(path).iterator()
        while (it.hasNext) {
          val watchedDirectory: WatchedDirectory = it.next().getValue
          if (watchedDirectory != null) {
            watchedDirectory.close()
          }
        }
      }
    }
    if (Files.isDirectory(path)) {
      processPath(callback, path, kind, new HashSet[QuickFile](), new HashSet[Path]())
    } else {
      maybeRunCallback(callback, new DirectoryWatcher.Event(path, kind))
    }
  }

  protected def handleOverflow(callback: Consumer[DirectoryWatcher.Event], path: Path): Unit = {
    val maxDepth: Int = directoryRegistry.maxDepthFor(path)
    var stop: Boolean = false
    while (!stop && maxDepth > 0) try {
      var registered: Boolean = false
      val files: Set[QuickFile] = new HashSet[QuickFile]()
      val directoryIterator: Iterator[Path] =
        directoryRegistry.registeredDirectories().iterator()
      while (directoryIterator.hasNext) files.add(
        new QuickFileImpl(directoryIterator.next().toString, DIRECTORY))
      maybePoll(path, files)
      val it: Iterator[QuickFile] = files.iterator()
      while (it.hasNext) {
        val file: QuickFile = it.next()
        if (file.isDirectory) {
          val regResult: Boolean = registerImpl(file.toPath(),
                                                if (maxDepth == java.lang.Integer.MAX_VALUE)
                                                  java.lang.Integer.MAX_VALUE
                                                else maxDepth - 1)
          registered = registered || regResult
          if (regResult) callbackExecutor.run(new Runnable() {
            override def run(): Unit = {
              callback.accept(new DirectoryWatcher.Event(file.toPath(), Create))
            }
          })
        }
      }
      stop = !registered
    } catch {
      case e: NoSuchFileException => stop = false

      case e: IOException => stop = true

    }
    callbackExecutor.run(new Runnable() {
      override def run(): Unit = {
        callback.accept(new DirectoryWatcher.Event(path, Overflow))
      }
    })
  }

  private def maybePoll(path: Path, files: Set[QuickFile]): Unit = {
    if (pollNewDirectories) {
      var result: Boolean = false
      do {
        result = false
        val it: Iterator[QuickFile] = QuickList
          .list(
            path,
            0,
            false,
            new Filter[QuickFile]() {
              override def accept(quickFile: QuickFile): Boolean =
                !quickFile.isDirectory || directoryRegistry.accept(quickFile.toPath())
            }
          )
          .iterator()
        while (it.hasNext) result = files.add(it.next()) || result
      } while (!Thread.currentThread().isInterrupted && result);
    }
  }

  /**
   * Similar to register, but tracks all of the new files found in the directory. It polls the
   * directory until the contents stop changing to ensure that a callback is fired for each path in
   * the newly created directory (up to the maxDepth). The assumption is that once the callback is
   * fired for the path, it is safe to assume that no event for a new file in the directory is
   * missed. Without the polling, it would be possible that a new file was created in the directory
   * before we registered it with the watch service. If this happened, then no callback would be
   * invoked for that file.
   *
   * @param path The newly created directory to add
   * @param maxDepth The maximum depth of the subdirectory traversal
   * @param newFiles The set of files that are found for the newly created directory
   * @return true if no exception is thrown
   */
  private def add(path: Path, maxDepth: Int, newFiles: Set[QuickFile]): Boolean = {
    var result: Boolean = true
    try {
      if (directoryRegistry.maxDepthFor(path) >= 0) {
        val dir: Directory[WatchedDirectory] = getRoot(path.getRoot)
        if (dir != null) {
          update(dir, path)
        }
      }
      maybePoll(path, newFiles)
    } catch {
      case e: IOException => result = false

    }
    result
  }

  private val updateObserver: Directory.Observer[WatchedDirectory] =
    new Observer[WatchedDirectory]() {
      override def onCreate(newEntry: Directory.Entry[WatchedDirectory]): Unit = {}

      override def onDelete(oldEntry: Directory.Entry[WatchedDirectory]): Unit = {
        oldEntry.getValue.close()
      }

      override def onUpdate(oldEntry: Directory.Entry[WatchedDirectory],
                            newEntry: Directory.Entry[WatchedDirectory]): Unit = {}

      override def onError(path: Path, exception: IOException): Unit = {}
    }

  private def update(dir: Directory[WatchedDirectory], path: Path): Unit = {
    dir.update(path, DIRECTORY).observe(updateObserver)
  }

  private def registerImpl(path: Path, maxDepth: Int): Boolean = {
    val existingMaxDepth: Int = directoryRegistry.maxDepthFor(path)
    val result: Boolean = existingMaxDepth < maxDepth
    var realPath: Path = null
    try realPath = path.toRealPath()
    catch {
      case e: IOException => realPath = path

    }
    if (result) {
      directoryRegistry.addDirectory(path, maxDepth)
    } else if (path != realPath) {
      /*
       * Note that watchedDir is not null, which means that this path has been
       * registered with a different alias.
       */

      throw new FileSystemLoopException(path.toString)
    }
    if (result) {
      val dir: Directory[WatchedDirectory] = getRoot(realPath.getRoot)
      var toUpdate: Path = path
      while (toUpdate != null && !Files.isDirectory(toUpdate)) toUpdate = toUpdate.getParent
      if (dir != null && toUpdate != null) update(dir, toUpdate)
    }
    result
  }

}
