package com.swoval.files;

import com.swoval.files.DirectoryWatcher.Event;
import com.swoval.functional.Consumer;
import java.io.IOException;

public class PlatformWatcher {
  static DirectoryWatcher make(
      final Consumer<Event> callback,
      final Executor executor,
      final DirectoryWatcher.Option... options)
      throws IOException, InterruptedException {
    return make(callback, WatchService.newWatchService(), executor, options);
  }

  static DirectoryWatcher make(
      final Consumer<Event> callback,
      final Registerable registerable,
      final Executor executor,
      final DirectoryWatcher.Option... options)
      throws InterruptedException {
    return make(
        callback,
        registerable,
        Executor.make("com.swoval.files.NioDirectoryWatcher-callback-thread"),
        executor,
        options);
  }

  static DirectoryWatcher make(
      final Consumer<Event> callback,
      final Registerable registerable,
      final Executor callbackExecutor,
      final Executor internalExecutor,
      final DirectoryWatcher.Option... options)
      throws InterruptedException {
    return new NioDirectoryWatcherImpl(
        callback, registerable, callbackExecutor, internalExecutor, options);
  }
}
