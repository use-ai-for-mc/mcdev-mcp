package dev.mcdevmcp.storage.h2;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class DatabaseLockState {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    final ReentrantLock sharedGuard = new ReentrantLock(true);
    int sharedReferences;
    FileChannel sharedChannel;
    FileLock sharedFileLock;
}
