package com.swoval.files

import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Map.Entry

object MapOps {

  def diffDirectoryEntries[T](oldEntries: List[Directory.Entry[T]],
                              newEntries: List[Directory.Entry[T]],
                              observer: Directory.Observer[T]): Unit = {
    val oldMap: Map[Path, Directory.Entry[T]] =
      new HashMap[Path, Directory.Entry[T]]()
    val oldIterator: Iterator[Directory.Entry[T]] = oldEntries.iterator()
    while (oldIterator.hasNext) {
      val entry: Directory.Entry[T] = oldIterator.next()
      oldMap.put(entry.path, entry)
    }
    val newMap: Map[Path, Directory.Entry[T]] =
      new HashMap[Path, Directory.Entry[T]]()
    val newIterator: Iterator[Directory.Entry[T]] = newEntries.iterator()
    while (newIterator.hasNext) {
      val entry: Directory.Entry[T] = newIterator.next()
      newMap.put(entry.path, entry)
    }
    diffDirectoryEntries(oldMap, newMap, observer)
  }

  def diffDirectoryEntries[K, V](oldMap: Map[K, Directory.Entry[V]],
                                 newMap: Map[K, Directory.Entry[V]],
                                 observer: Directory.Observer[V]): Unit = {
    val newIterator: Iterator[Entry[K, Directory.Entry[V]]] =
      new ArrayList(newMap.entrySet()).iterator()
    val oldIterator: Iterator[Entry[K, Directory.Entry[V]]] =
      new ArrayList(oldMap.entrySet()).iterator()
    while (newIterator.hasNext) {
      val entry: Entry[K, Directory.Entry[V]] = newIterator.next()
      val oldValue: Directory.Entry[V] = oldMap.get(entry.getKey)
      if (oldValue != null) {
        observer.onUpdate(oldValue, entry.getValue)
      } else {
        observer.onCreate(entry.getValue)
      }
    }
    while (oldIterator.hasNext) {
      val entry: Entry[K, Directory.Entry[V]] = oldIterator.next()
      if (!newMap.containsKey(entry.getKey)) {
        observer.onDelete(entry.getValue)
      }
    }
  }

}
