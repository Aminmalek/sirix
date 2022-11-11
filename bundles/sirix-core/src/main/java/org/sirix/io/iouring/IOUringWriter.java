/*
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.io.iouring;

import com.github.benmanes.caffeine.cache.AsyncCache;
import net.openhft.chronicle.bytes.Bytes;
import one.jasyncfio.AsyncFile;
import org.jetbrains.annotations.NotNull;
import org.sirix.api.PageReadOnlyTrx;
import org.sirix.api.PageTrx;
import org.sirix.exception.SirixIOException;
import org.sirix.io.*;
import org.sirix.page.*;
import org.sirix.page.interfaces.Page;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * File Writer for providing read/write access for file as a Sirix backend.
 *
 * @author Marc Kramis, Seabix
 * @author Sebastian Graf, University of Konstanz
 * @author Johannes Lichtenberger
 */
public final class IOUringWriter extends AbstractForwardingReader implements Writer {

  private static final byte UBER_PAGE_BYTE_ALIGN = 100;
  private static final short REVISION_ROOT_PAGE_BYTE_ALIGN = 256; // Must be a power of two.
  private static final byte PAGE_FRAGMENT_BYTE_ALIGN = 8; // Must be a power of two.
  public static final int FLUSH_SIZE = 64_000;

  /**
   * Random access to work on.
   */
  private final AsyncFile dataFile;

  /**
   * {@link IOUringReader} reference for this writer.
   */
  private final IOUringReader reader;

  private final SerializationType serializationType;

  private final AsyncFile revisionsFile;

  private final PagePersister pagePersister;

  private final AsyncCache<Integer, RevisionFileData> cache;

  private final Path dataFilePath;

  private final Path revisionsOffsetFilePath;

  private boolean isFirstUberPage;

  private final Bytes<ByteBuffer> byteBufferBytes = Bytes.elasticByteBuffer(1_000);

  /**
   * Constructor.
   *
   * @param dataFile            the data file channel
   * @param revisionsOffsetFile the channel to the file, which holds pointers to the revision root pages
   * @param serializationType   the serialization type (for the transaction log or the data file)
   * @param pagePersister       transforms in-memory pages into byte-arrays and back
   * @param cache               the revision file data cache
   * @param reader              the reader delegate
   */
  public IOUringWriter(final AsyncFile dataFile, final AsyncFile revisionsOffsetFile, final Path dataFilePath,
      final Path revisionsOffsetFilePath, final SerializationType serializationType, final PagePersister pagePersister,
      final AsyncCache<Integer, RevisionFileData> cache, final IOUringReader reader) {
    this.dataFile = dataFile;
    this.revisionsFile = revisionsOffsetFile;
    this.dataFilePath = dataFilePath;
    this.revisionsOffsetFilePath = revisionsOffsetFilePath;
    this.serializationType = checkNotNull(serializationType);
    this.pagePersister = checkNotNull(pagePersister);
    this.cache = checkNotNull(cache);
    this.reader = checkNotNull(reader);
  }

  @Override
  public Writer truncateTo(final PageReadOnlyTrx pageReadOnlyTrx, final int revision) {
    try {
      final var dataFileRevisionRootPageOffset =
          cache.get(revision, (unused) -> getRevisionFileData(revision)).get(5, TimeUnit.SECONDS).offset();

      // Read page from file.
      final var buffer = ByteBuffer.allocateDirect(IOStorage.OTHER_BEACON).order(ByteOrder.nativeOrder());

      dataFile.read(buffer, dataFileRevisionRootPageOffset).join();

      buffer.position(0);
      final int dataLength = buffer.getInt();

      new RandomAccessFile(dataFilePath.toFile(), "rw").getChannel()
                                                       .truncate(dataFileRevisionRootPageOffset + IOStorage.OTHER_BEACON
                                                                     + dataLength);
    } catch (InterruptedException | ExecutionException | TimeoutException | IOException e) {
      throw new IllegalStateException(e);
    }

    return this;
  }

  @Override
  public IOUringWriter write(final PageReadOnlyTrx pageReadOnlyTrx, final PageReference pageReference,
      final Bytes<ByteBuffer> bufferedBytes) {
    try {
      final long offset = getOffset(bufferedBytes);
      return writePageReference(pageReadOnlyTrx, pageReference, bufferedBytes, offset);
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }
  }

  private long getOffset(Bytes<ByteBuffer> bufferedBytes) throws IOException {
    final long fileSize = dataFile.size().join();
    long offset;

    if (fileSize == 0) {
      offset = IOStorage.FIRST_BEACON;
      offset += (PAGE_FRAGMENT_BYTE_ALIGN - (offset & (PAGE_FRAGMENT_BYTE_ALIGN - 1)));
      offset += bufferedBytes.writePosition();
    } else {
      offset = fileSize + bufferedBytes.writePosition();
    }

    return offset;
  }

  @NotNull
  private IOUringWriter writePageReference(final PageReadOnlyTrx pageReadOnlyTrx, final PageReference pageReference,
      Bytes<ByteBuffer> bufferedBytes, long offset) {
    try {
      POOL.submit(() -> writePage(pageReadOnlyTrx, pageReference, bufferedBytes, offset)).get();
      return this;
    } catch (InterruptedException | ExecutionException e) {
      throw new SirixIOException(e);
    }
  }

  @NotNull
  private IOUringWriter writePage(PageReadOnlyTrx pageReadOnlyTrx, PageReference pageReference,
      Bytes<ByteBuffer> bufferedBytes, long offset) {
    // Perform byte operations.
    try {
      // Serialize page.
      final Page page = pageReference.getPage();
      assert page != null;

      pagePersister.serializePage(pageReadOnlyTrx, byteBufferBytes, page, serializationType);
      final var byteArray = byteBufferBytes.toByteArray();

      final byte[] serializedPage;

      try (final ByteArrayOutputStream output = new ByteArrayOutputStream(byteArray.length);
           final DataOutputStream dataOutput = new DataOutputStream(reader.byteHandler.serialize(output))) {
        dataOutput.write(byteArray);
        dataOutput.flush();
        serializedPage = output.toByteArray();
      }

      byteBufferBytes.clear();

      int offsetToAdd = 0;

      // Getting actual offset and appending to the end of the current file.
      if (serializationType == SerializationType.DATA) {
        if (page instanceof UberPage) {
          offsetToAdd =
              UBER_PAGE_BYTE_ALIGN - ((serializedPage.length + IOStorage.OTHER_BEACON) % UBER_PAGE_BYTE_ALIGN);
        } else if (page instanceof RevisionRootPage && offset % REVISION_ROOT_PAGE_BYTE_ALIGN != 0) {
          offsetToAdd = (int) (REVISION_ROOT_PAGE_BYTE_ALIGN - (offset & (REVISION_ROOT_PAGE_BYTE_ALIGN - 1)));
          offset += offsetToAdd;
        } else if (offset % PAGE_FRAGMENT_BYTE_ALIGN != 0) {
          offsetToAdd = (int) (PAGE_FRAGMENT_BYTE_ALIGN - (offset & (PAGE_FRAGMENT_BYTE_ALIGN
              - 1)));//(offset % PAGE_FRAGMENT_BYTE_ALIGN));
          offset += offsetToAdd;
        }
      }

      if (!(page instanceof UberPage) && offsetToAdd > 0) {
        bufferedBytes.writePosition(bufferedBytes.writePosition() + offsetToAdd);
      }

      bufferedBytes.writeInt(serializedPage.length);
      bufferedBytes.write(serializedPage);

      if (page instanceof UberPage && offsetToAdd > 0) {
        final byte[] bytesToAdd = new byte[(int) offsetToAdd];
        bufferedBytes.write(bytesToAdd);
      }

      if (bufferedBytes.writePosition() > FLUSH_SIZE) {
        bufferedBytes = flushBuffer((PageTrx) pageReadOnlyTrx, bufferedBytes);
      }

      // Remember page coordinates.
      switch (serializationType) {
        case DATA -> pageReference.setKey(offset);
        case TRANSACTION_INTENT_LOG -> pageReference.setPersistentLogKey(offset);
        default -> {
          // Must not happen.
        }
      }

      if (page instanceof UnorderedKeyValuePage unorderedKeyValuePage) {
        pageReference.setHash(unorderedKeyValuePage.getHashCode());
      } else {
        pageReference.setHash(reader.hashFunction.hashBytes(serializedPage).asBytes());
      }

      if (serializationType == SerializationType.DATA) {
        if (page instanceof RevisionRootPage revisionRootPage) {
          ByteBuffer buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
          buffer.putLong(offset);
          buffer.position(8);
          buffer.putLong(revisionRootPage.getRevisionTimestamp());
          buffer.position(0);
          final long revisionsFileOffset;
          if (revisionRootPage.getRevision() == 0) {
            revisionsFileOffset = revisionsFile.size().join() + IOStorage.FIRST_BEACON;
          } else {
            revisionsFileOffset = revisionsFile.size().join();
          }
          revisionsFile.write(buffer, revisionsFileOffset).join();
          buffer = null;
          final long currOffset = offset;
          cache.put(revisionRootPage.getRevision(),
                    CompletableFuture.supplyAsync(() -> new RevisionFileData(currOffset,
                                                                             Instant.ofEpochMilli(revisionRootPage.getRevisionTimestamp()))));
        } else if (page instanceof UberPage && isFirstUberPage) {
          final ByteBuffer firstUberPageBuffer =
              ByteBuffer.allocateDirect(IOStorage.FIRST_BEACON >> 1).order(ByteOrder.nativeOrder());
          firstUberPageBuffer.put(serializedPage);
          firstUberPageBuffer.position(0);
          revisionsFile.write(firstUberPageBuffer, 0L).join();
          final ByteBuffer secondUberPageBuffer =
              ByteBuffer.allocateDirect(IOStorage.FIRST_BEACON >> 1).order(ByteOrder.nativeOrder());
          secondUberPageBuffer.put(serializedPage);
          secondUberPageBuffer.position(0);
          revisionsFile.write(secondUberPageBuffer, (long) IOStorage.FIRST_BEACON >> 1).join();
          revisionsFile.dataSync().join();
        }
      }

      return this;
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }
  }

  @Override
  public void close() {
    if (dataFile != null) {
      dataFile.dataSync().join();
    }
    if (revisionsFile != null) {
      revisionsFile.dataSync().join();
    }
    if (reader != null) {
      reader.close();
    }
  }

  @Override
  public Writer writeUberPageReference(final PageReadOnlyTrx pageReadOnlyTrx, final PageReference pageReference,
      Bytes<ByteBuffer> bufferedBytes) {
    try {
      if (bufferedBytes.writePosition() > 0) {
        bufferedBytes = flushBuffer((PageTrx) pageReadOnlyTrx, bufferedBytes);
      }

      isFirstUberPage = true;
      writePageReference(pageReadOnlyTrx, pageReference, bufferedBytes, 0);
      isFirstUberPage = false;
      writePageReference(pageReadOnlyTrx, pageReference, bufferedBytes, IOStorage.FIRST_BEACON >> 1);

      final var buffer = bufferedBytes.underlyingObject().rewind();
      buffer.limit((int) bufferedBytes.readLimit());
      dataFile.write(buffer, 0L).join();
      dataFile.dataSync().join();
      ((PageTrx) pageReadOnlyTrx).newBufferedBytesInstance();
      bufferedBytes.clear();
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }

    return this;
  }

  private Bytes<ByteBuffer> flushBuffer(final PageTrx pageTrx, final Bytes<ByteBuffer> bufferedBytes)
      throws IOException {
    final long fileSize = dataFile.size().join();
    long offset;

    if (fileSize == 0) {
      offset = IOStorage.FIRST_BEACON;
      offset += (PAGE_FRAGMENT_BYTE_ALIGN - (offset % PAGE_FRAGMENT_BYTE_ALIGN));
    } else {
      offset = fileSize;
    }

    final var buffer = bufferedBytes.underlyingObject().rewind();
    buffer.limit((int) bufferedBytes.readLimit());
    dataFile.write(buffer, offset).join();
    bufferedBytes.clear();
    return pageTrx.newBufferedBytesInstance();
  }

  @Override
  protected Reader delegate() {
    return reader;
  }

  @Override
  public Writer truncate() {
    try {
      new RandomAccessFile(dataFilePath.toFile(), "rw").getChannel().truncate(0);

      if (revisionsFile != null) {
        new RandomAccessFile(revisionsOffsetFilePath.toFile(), "rw").getChannel().truncate(0);
      }
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }

    return this;
  }
}
