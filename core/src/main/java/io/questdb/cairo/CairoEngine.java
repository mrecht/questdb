/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.MessageBus;
import io.questdb.MessageBusImpl;
import io.questdb.cairo.pool.PoolListener;
import io.questdb.cairo.pool.ReaderPool;
import io.questdb.cairo.pool.WriterPool;
import io.questdb.cairo.sql.ReaderOutOfDateException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.*;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.str.NativeLPSZ;
import io.questdb.std.str.Path;
import io.questdb.tasks.TelemetryTask;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;

import static io.questdb.cairo.ColumnType.SYMBOL;

public class CairoEngine implements Closeable {
    private static final Log LOG = LogFactory.getLog(CairoEngine.class);

    private final WriterPool writerPool;
    private final ReaderPool readerPool;
    private final CairoConfiguration configuration;
    private final WriterMaintenanceJob writerMaintenanceJob;
    private final MessageBus messageBus;
    private final RingQueue<TelemetryTask> telemetryQueue;
    private final MPSequence telemetryPubSeq;
    private final SCSequence telemetrySubSeq;
    private final long tableIndexFd;
    private final long tableIndexMem;
    private final long tableIndexMemSize;

    public CairoEngine(CairoConfiguration configuration) {
        this.configuration = configuration;
        this.messageBus = new MessageBusImpl(configuration);
        this.writerPool = new WriterPool(configuration, messageBus);
        this.readerPool = new ReaderPool(configuration);
        this.writerMaintenanceJob = new WriterMaintenanceJob(configuration);
        this.telemetryQueue = new RingQueue<>(TelemetryTask::new, configuration.getTelemetryConfiguration().getQueueCapacity());
        this.telemetryPubSeq = new MPSequence(telemetryQueue.getCapacity());
        this.telemetrySubSeq = new SCSequence();
        telemetryPubSeq.then(telemetrySubSeq).then(telemetryPubSeq);

        final FilesFacade ff = configuration.getFilesFacade();
        try (Path path = new Path().of(configuration.getRoot()).concat("_tab_index.d").$()) {
            this.tableIndexMemSize = Files.PAGE_SIZE;
            tableIndexFd = TableUtils.openFileRWOrFail(ff, path);
            final long fileSize = ff.length(tableIndexFd);
            if (fileSize < Long.BYTES) {
                if (!ff.allocate(tableIndexFd, Files.PAGE_SIZE)) {
                    ff.close(tableIndexFd);
                    throw CairoException.instance(ff.errno()).put("Could not allocate [file=").put(path).put(", actual=").put(fileSize).put(", desired=").put(this.tableIndexMemSize).put(']');
                }
            }

            this.tableIndexMem = ff.mmap(tableIndexFd, tableIndexMemSize, 0, Files.MAP_RW);
            if (tableIndexMem == -1) {
                ff.close(tableIndexFd);
                throw CairoException.instance(ff.errno()).put("Could not mmap [file=").put(path).put(']');
            }
            try {
                upgradeTableId();
            } catch (CairoException e) {
                close();
                throw e;
            }
        }
    }

    @Override
    public void close() {
        Misc.free(writerPool);
        Misc.free(readerPool);
        configuration.getFilesFacade().munmap(tableIndexMem, tableIndexMemSize);
        configuration.getFilesFacade().close(tableIndexFd);
    }

    public void createTable(
            CairoSecurityContext securityContext,
            AppendMemory mem,
            Path path,
            TableStructure struct
    ) {
        securityContext.checkWritePermission();
        TableUtils.createTable(
                configuration.getFilesFacade(),
                mem,
                path,
                configuration.getRoot(),
                struct,
                configuration.getMkDirMode(),
                (int) getNextTableId()
        );
    }

    public TableWriter getBackupWriter(
            CairoSecurityContext securityContext,
            CharSequence tableName,
            CharSequence backupDirName
    ) {
        securityContext.checkWritePermission();
        // There is no point in pooling/caching these writers since they are only used once, backups are not incremental
        return new TableWriter(configuration, tableName, messageBus, true, DefaultLifecycleManager.INSTANCE, backupDirName);
    }

    public int getBusyReaderCount() {
        return readerPool.getBusyCount();
    }

    public int getBusyWriterCount() {
        return writerPool.getBusyCount();
    }

    public CairoConfiguration getConfiguration() {
        return configuration;
    }

    public MessageBus getMessageBus() {
        return messageBus;
    }

    public long getNextTableId() {
        long next;
        long x = Unsafe.getUnsafe().getLong(tableIndexMem);
        do {
            next = x;
            x = Os.compareAndSwap(tableIndexMem, next, next + 1);
        } while (next != x);
        return next + 1;
    }

    public PoolListener getPoolListener() {
        return this.writerPool.getPoolListener();
    }

    public void setPoolListener(PoolListener poolListener) {
        this.writerPool.setPoolListener(poolListener);
        this.readerPool.setPoolListener(poolListener);
    }

    public TableReader getReader(
            CairoSecurityContext securityContext,
            CharSequence tableName
    ) {
        return getReader(securityContext, tableName, TableUtils.ANY_TABLE_VERSION);
    }

    public TableReader getReader(
            CairoSecurityContext securityContext,
            CharSequence tableName,
            long version
    ) {
        TableReader reader = readerPool.get(tableName);
        if (version > -1 && reader.getVersion() != version) {
            reader.close();
            throw ReaderOutOfDateException.INSTANCE;
        }
        return reader;
    }

    public int getStatus(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName,
            int lo,
            int hi
    ) {
        return TableUtils.exists(configuration.getFilesFacade(), path, configuration.getRoot(), tableName, lo, hi);
    }

    public int getStatus(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName
    ) {
        return getStatus(securityContext, path, tableName, 0, tableName.length());
    }

    public Sequence getTelemetryPubSequence() {
        return telemetryPubSeq;
    }

    public RingQueue<TelemetryTask> getTelemetryQueue() {
        return telemetryQueue;
    }

    public SCSequence getTelemetrySubSequence() {
        return telemetrySubSeq;
    }

    public TableWriter getWriter(
            CairoSecurityContext securityContext,
            CharSequence tableName
    ) {
        securityContext.checkWritePermission();
        return writerPool.get(tableName);
    }

    public Job getWriterMaintenanceJob() {
        return writerMaintenanceJob;
    }

    public boolean lock(
            CairoSecurityContext securityContext,
            CharSequence tableName
    ) {
        securityContext.checkWritePermission();
        if (writerPool.lock(tableName)) {
            boolean locked = readerPool.lock(tableName);
            if (locked) {
                LOG.info().$("locked [table=`").$(tableName).$("`, thread=").$(Thread.currentThread().getId()).$(']').$();
                return true;
            }
            writerPool.unlock(tableName);
        }
        return false;
    }

    public boolean lockReaders(CharSequence tableName) {
        return readerPool.lock(tableName);
    }

    public boolean lockWriter(CharSequence tableName) {
        return writerPool.lock(tableName);
    }

    public boolean migrateNullFlag(CairoSecurityContext cairoSecurityContext, CharSequence tableName) {
        try (
                TableWriter writer = getWriter(cairoSecurityContext, tableName);
                TableReader reader = getReader(cairoSecurityContext, tableName)
        ) {
            TableReaderMetadata readerMetadata = reader.getMetadata();
            if (readerMetadata.getVersion() < 416) {
                LOG.info().$("migrating null flag for symbols [table=").utf8(tableName).$(']').$();
                for (int i = 0, count = reader.getColumnCount(); i < count; i++) {
                    if (readerMetadata.getColumnType(i) == SYMBOL) {
                        LOG.info().$("updating null flag [column=").utf8(readerMetadata.getColumnName(i)).$(']').$();
                        writer.getSymbolMapWriter(i).updateNullFlag(reader.hasNull(i));
                    }
                }
                writer.updateMetadataVersion();
                LOG.info().$("migrated null flag for symbols [table=").utf8(tableName).$(", tableVersion=").$(ColumnType.VERSION).$(']').$();
                return true;
            }
        }
        return false;
    }

    public boolean releaseAllReaders() {
        return readerPool.releaseAll();
    }

    public boolean releaseAllWriters() {
        return writerPool.releaseAll();
    }

    public boolean releaseInactive() {
        boolean useful = writerPool.releaseInactive();
        useful |= readerPool.releaseInactive();
        return useful;
    }

    public void remove(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName
    ) {
        securityContext.checkWritePermission();
        if (lock(securityContext, tableName)) {
            try {
                path.of(configuration.getRoot()).concat(tableName).$();
                if (!configuration.getFilesFacade().rmdir(path)) {
                    int error = configuration.getFilesFacade().errno();
                    LOG.error().$("remove failed [tableName='").utf8(tableName).$("', error=").$(error).$(']').$();
                    throw CairoException.instance(error).put("Table remove failed");
                }
                return;
            } finally {
                unlock(securityContext, tableName, null);
            }
        }
        throw CairoException.instance(configuration.getFilesFacade().errno()).put("Could not lock '").put(tableName).put('\'');
    }

    public boolean removeDirectory(@Transient Path path, CharSequence dir) {
        path.of(configuration.getRoot()).concat(dir);
        final FilesFacade ff = configuration.getFilesFacade();
        return ff.rmdir(path.put(Files.SEPARATOR).$());
    }

    public void rename(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName,
            Path otherPath,
            CharSequence newName
    ) {
        securityContext.checkWritePermission();
        if (lock(securityContext, tableName)) {
            try {
                rename0(path, tableName, otherPath, newName);
            } finally {
                unlock(securityContext, tableName, null);
            }
        } else {
            LOG.error().$("cannot lock and rename [from='").$(tableName).$("', to='").$(newName).$("']").$();
            throw EntryUnavailableException.INSTANCE.put("Cannot lock [table=").put(tableName).put(']');
        }
    }

    // This is not thread safe way to reset table ID back to 0
    // It is useful for testing only
    public void resetTableId() {
        Unsafe.getUnsafe().putLong(tableIndexMem, 0);
    }

    public void unlock(
            CairoSecurityContext securityContext,
            CharSequence tableName,
            @Nullable TableWriter writer
    ) {
        readerPool.unlock(tableName);
        writerPool.unlock(tableName, writer);
        LOG.info().$("unlocked [table=`").$(tableName).$(']').$();
    }

    public void unlockReaders(CharSequence tableName) {
        readerPool.unlock(tableName);
    }

    public void unlockWriter(CharSequence tableName) {
        writerPool.unlock(tableName);
    }

    public void upgradeTableId() {
        final FilesFacade ff = configuration.getFilesFacade();
        long mem = Unsafe.malloc(8);
        try {
            try (Path path = new Path()) {
                path.of(configuration.getRoot());
                final int rootLen = path.length();

                // check if all tables have been upgraded already
                path.concat(TableUtils.UPGRADE_FILE_NAME).$();
                final boolean existed = ff.exists(path);
                long upgradeFd = TableUtils.openFileRWOrFail(ff, path);
                LOG.debug()
                        .$("open [fd=").$(upgradeFd)
                        .$(", path=").$(path)
                        .$(']').$();
                if (existed) {
                    long readLen = ff.read(upgradeFd, mem, Integer.BYTES, 0);
                    if (readLen == Integer.BYTES) {
                        if (Unsafe.getUnsafe().getInt(mem) >= ColumnType.VERSION_THAT_ADDED_TABLE_ID) {
                            LOG.info().$("table IDs are up to date").$();
                            ff.close(upgradeFd);
                            upgradeFd = -1;
                        }
                    } else {
                        ff.close(upgradeFd);
                        throw CairoException.instance(ff.errno()).put("could not read [fd=").put(upgradeFd).put(", path=").put(path).put(']');
                    }
                }

                if (upgradeFd != -1) {
                    try {
                        LOG.info().$("upgrading table IDs").$();
                        final NativeLPSZ nativeLPSZ = new NativeLPSZ();
                        ff.iterateDir(path.trimTo(rootLen).$(), (name, type) -> {
                            if (type == Files.DT_DIR) {
                                nativeLPSZ.of(name);
                                if (Chars.notDots(nativeLPSZ)) {
                                    final int plen = path.length();
                                    path.chopZ().concat(nativeLPSZ).concat(TableUtils.META_FILE_NAME).$();
                                    if (ff.exists(path)) {
                                        assignTableId(ff, path, mem);
                                    }
                                    path.trimTo(plen);
                                }
                            }
                        });
                        LOG.info().$("upgraded table IDs").$();

                        Unsafe.getUnsafe().putInt(mem, ColumnType.VERSION);
                        long writeLen = ff.write(upgradeFd, mem, Integer.BYTES, 0);
                        if (writeLen < Integer.BYTES) {
                            throw CairoException.instance(ff.errno()).put("Could not write to [fd=").put(upgradeFd).put(']');
                        }
                    } finally {
                        ff.close(upgradeFd);
                    }
                }
            }
        } finally {
            Unsafe.free(mem, 8);
        }
    }

    // path is to the metadata file of table
    private void assignTableId(FilesFacade ff, Path path, long mem) {
        final long fd = TableUtils.openFileRWOrFail(ff, path);
        if (ff.read(fd, mem, 8, TableUtils.META_OFFSET_VERSION) == 8) {
            if (Unsafe.getUnsafe().getInt(mem) < ColumnType.VERSION_THAT_ADDED_TABLE_ID) {
                LOG.info().$("upgrading [path=").$(path).$(']').$();
                Unsafe.getUnsafe().putInt(mem, ColumnType.VERSION);
                Unsafe.getUnsafe().putInt(mem + Integer.BYTES, (int) getNextTableId());
                if (ff.write(fd, mem, 8, TableUtils.META_OFFSET_VERSION) == 8) {
                    ff.close(fd);
                    return;
                }
            }
            ff.close(fd);
            return;
        }
        ff.close(fd);
        throw CairoException.instance(ff.errno()).put("Could not update table id [path=").put(path).put(']');
    }

    private void rename0(Path path, CharSequence tableName, Path otherPath, CharSequence to) {
        final FilesFacade ff = configuration.getFilesFacade();
        final CharSequence root = configuration.getRoot();

        if (TableUtils.exists(ff, path, root, tableName) != TableUtils.TABLE_EXISTS) {
            LOG.error().$('\'').utf8(tableName).$("' does not exist. Rename failed.").$();
            throw CairoException.instance(0).put("Rename failed. Table '").put(tableName).put("' does not exist");
        }

        path.of(root).concat(tableName).$();
        otherPath.of(root).concat(to).$();

        if (ff.exists(otherPath)) {
            LOG.error().$("rename target exists [from='").$(tableName).$("', to='").$(otherPath).$("']").$();
            throw CairoException.instance(0).put("Rename target exists");
        }

        if (!ff.rename(path, otherPath)) {
            int error = ff.errno();
            LOG.error().$("rename failed [from='").$(path).$("', to='").$(otherPath).$("', error=").$(error).$(']').$();
            throw CairoException.instance(error).put("Rename failed");
        }
    }

    private class WriterMaintenanceJob extends SynchronizedJob {

        private final MicrosecondClock clock;
        private final long checkInterval;
        private long last = 0;

        public WriterMaintenanceJob(CairoConfiguration configuration) {
            this.clock = configuration.getMicrosecondClock();
            this.checkInterval = configuration.getIdleCheckInterval() * 1000;
        }

        @Override
        protected boolean runSerially() {
            long t = clock.getTicks();
            if (last + checkInterval < t) {
                last = t;
                return releaseInactive();
            }
            return false;
        }
    }
}
