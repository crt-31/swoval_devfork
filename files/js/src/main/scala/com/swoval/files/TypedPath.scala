// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import java.io.IOException
import java.nio.file.Path
import java.util.ArrayList
import java.util.Collection
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Set
import scala.beans.{ BeanProperty, BooleanBeanProperty }

/**
 * A mix-in for an object that represents a file system path. Provides (possibly) fast accessors for
 * the type of the file.
 */
trait TypedPath {

  /**
   * Return the path.
   *
   * @return the path.
   */
  def getPath(): Path

  /**
   * Does this path exist?
   *
   * @return true when the path exists.
   */
  def exists(): Boolean

  /**
   * Is the path represented by this a directory?
   *
   * @return true if the underlying path is a directory
   */
  def isDirectory(): Boolean

  /**
   * Is the path represented by this a regular file?
   *
   * @return true if the underlying path is a regular file
   */
  def isFile(): Boolean

  /**
   * Is the path represented by this a symbolic link?
   *
   * @return true if the underlying path is a symbolic link
   */
  def isSymbolicLink(): Boolean

  /**
   * Returns the real path when the target of the symbolic link if this path is a symbolic link and
   * the path itself otherwise.
   *
   * @return the real path.
   */
  def toRealPath(): Path

}

object TypedPaths {

  private abstract class TypedPathImpl(@BeanProperty val path: Path) extends TypedPath {

    override def toRealPath(): Path =
      try if (isSymbolicLink) path.toRealPath() else path
      catch {
        case e: IOException => path

      }

    override def toString(): String =
      "TypedPath(" + path + ", " + isSymbolicLink + ", " + toRealPath() +
        ")"

    override def equals(other: Any): Boolean = other match {
      case other: TypedPath => other.getPath == getPath
      case _                => false

    }

    override def hashCode(): Int = getPath.hashCode

  }

  def getDelegate(path: Path, typedPath: TypedPath): TypedPath =
    new TypedPathImpl(path) {
      override def exists(): Boolean = typedPath.exists()

      override def isDirectory(): Boolean = typedPath.isDirectory

      override def isFile(): Boolean = typedPath.isFile

      override def isSymbolicLink(): Boolean = typedPath.isSymbolicLink
    }

  def get(path: Path): TypedPath =
    try get(path, Entries.getKind(path))
    catch {
      case e: IOException => get(path, Entries.NONEXISTENT)

    }

  def get(path: Path, kind: Int): TypedPath = new TypedPathImpl(path) {
    override def exists(): Boolean = (kind & Entries.NONEXISTENT) == 0

    override def isDirectory(): Boolean = (kind & Entries.DIRECTORY) != 0

    override def isFile(): Boolean = (kind & Entries.FILE) != 0

    override def isSymbolicLink(): Boolean = (kind & Entries.LINK) != 0
  }

}