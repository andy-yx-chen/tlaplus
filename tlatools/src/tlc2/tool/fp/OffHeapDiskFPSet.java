// Copyright (c) 2012 Markus Alexander Kuppe. All rights reserved.
package tlc2.tool.fp;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.LongBuffer;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import tlc2.output.EC;
import tlc2.output.MP;
import util.Assert;

@SuppressWarnings({ "serial" })
public final class OffHeapDiskFPSet extends DiskFPSet implements FPSetStatistic {
	
	protected static final double COLLISION_BUCKET_RATIO = .025d;
	
	protected final int bucketCapacity;

	private final LongArray array;

	/**
	 * A bucket containing collision elements which is used as a fall-back if a
	 * bucket is fully used up. Buckets cannot grow as the whole in-memory
	 * data-structure is static and not designed to be resized.
	 * 
	 * <p>
	 * Open addressing - contrary to separate chaining - is not an option for an
	 * {@link OffHeapDiskFPSet}, because it does not support the invariant of
	 * monotonic increasing buckets required by the {@link Indexer}. Adhering to
	 * this invariant has the benefit, that only the elements in a bucket have
	 * to be sorted, but they don't change buckets during sort. Thus, a
	 * temporary sort array as in {@link LSBDiskFPSet.LSBFlusher#prepareTable()} is
	 * obsolete halving the memory footprint.
	 * </p>
	 */
	protected final CollisionBucket collisionBucket;
	
	/**
	 * The indexer maps a fingerprint to a in-memory bucket and the associated lock
	 */
	private final Indexer indexer;
	
	private final ReadWriteLock csRWLock = new ReentrantReadWriteLock();

	protected OffHeapDiskFPSet(final FPSetConfiguration fpSetConfig) throws RemoteException {
		super(fpSetConfig);
		
		final long memoryInFingerprintCnt = fpSetConfig.getMemoryInFingerprintCnt();
		
		// Determine base address which varies depending on machine architecture.
		this.array = new LongArray(memoryInFingerprintCnt);
		this.bucketCapacity = InitialBucketCapacity;

		final int csCapacity = (int) (maxTblCnt * COLLISION_BUCKET_RATIO);
		this.collisionBucket = new TreeSetCollisionBucket(csCapacity);
		
		this.flusher = new OffHeapMSBFlusher();
		
		// Calculate Hamming weight of maxTblCnt
		final int bitCount = Long.bitCount(memoryInFingerprintCnt);
		
		// If Hamming weight is 1, the logical index address can be calculated
		// significantly faster by bit-shifting. However, with large memory
		// sizes, only supporting increments of 2^n sizes would waste memory
		// (e.g. either 32GiB or 64Gib). Hence, we check if the bitCount allows
		// us to use bit-shifting. If not, we fall back to less efficient
		// calculations. Additionally we increase the bucket capacity to make
		// use of extra memory. The down side is, that larger buckets mean
		// increased linear search. But linear search on maximally 31 elements
		// still outperforms disk I/0.
		if (bitCount == 1) {
			this.indexer = new BitshiftingIndexer(bucketCapacity, memoryInFingerprintCnt, fpSetConfig.getFpBits());
		} else {
			// non 2^n buckets cannot use a bit shifting indexer
			this.indexer = new Indexer(bucketCapacity, memoryInFingerprintCnt, fpSetConfig.getFpBits());
		}
	}
	
	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#init(int, java.lang.String, java.lang.String)
	 */
	public void init(int numThreads, String aMetadir, String filename)
			throws IOException {
		super.init(numThreads, aMetadir, filename);
		
		array.zeroMemory(numThreads);
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#sizeof()
	 */
	public long sizeof() {
		long size = 44; // approx size of this DiskFPSet object
		size += maxTblCnt * (long) LongSize;
		size += getIndexCapacity() * 4;
		return size;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#needsDiskFlush()
	 */
	protected final boolean needsDiskFlush() {
		// Only flush due to collision ratio when primary hash table is at least
		// 25% full. Otherwise a second flush potentially immediately follows a
		// first one, when both values for tblCnt and collision size can be small.
		return (collisionRatioExceeds(COLLISION_BUCKET_RATIO) && loadFactorExceeds(.25d)) 
				|| loadFactorExceeds(1d) || forceFlush;
	}
	
	/**
	 * This limits the (primary) in-memory hash table to grow beyond the given
	 * limit.
	 * 
	 * @param limit
	 *            A limit in the domain [0, 1] which restricts the hash table
	 *            from growing past it.
	 * @return true iff the current hash table load exceeds the given limit
	 */
	private final boolean loadFactorExceeds(final double limit) {
		// Base this one the primary hash table only and exclude the
		// collision bucket
		final double d = (this.tblCnt.doubleValue() - collisionBucket.size()) / (double) this.maxTblCnt;
		return d >= limit;
	}

	/**
	 * @param limit A limit the collsionBucket is not allowed to exceed
	 * @return The proportional size of the collision bucket compared to the
	 *         size of the set.
	 */
	private final boolean collisionRatioExceeds(final double limit) {
		// Do not use the thread safe getCollisionRatio here to avoid
		// unnecessary locking. put() calls us holding a memory write locking
		// which also blocks writers to collisionBucket.
		final long size = collisionBucket.size();
		// Subtract size from overall tblCnt as it includes the cs size
		// @see put(long)
		final double d = (double) size / (tblCnt.doubleValue() - size);
		return d >= limit;
	}
	
	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#getLockIndex(long)
	 */
	@Override
	protected final int getLockIndex(long fp) {
		return this.indexer.getLockIndex(fp);
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#memLookup(long)
	 */
	final boolean memLookup(long fp) {
		final long position = indexer.getLogicalPosition(fp);
		
		// Linearly search the logical bucket; 0L is an invalid fp and marks the
		// end of the allocated bucket
		long l = -1L;
		for (int i = 0; i < bucketCapacity && l != 0L; i++) {
			l = array.get(position + i);
			// zero the long msb (which is 1 if fp has been flushed to disk)
			if (fp == (l & 0x7FFFFFFFFFFFFFFFL)) {
				return true;
			}
		}
		
		return csLookup(fp);
	}
	
	/**
	 * Probes {@link OffHeapDiskFPSet#collisionBucket} for the given fingerprint.
	 * @param fp
	 * @return true iff fp is in the collision bucket
	 */
	protected final boolean csLookup(long fp) {
		try {
			csRWLock.readLock().lock();
			return collisionBucket.contains(fp);
		} finally {
			csRWLock.readLock().unlock();
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#memInsert(long)
	 */
	final boolean memInsert(long fp) {
		final long position = indexer.getLogicalPosition(fp);

		long l = -1;
		long freePosition = -1L;
		for (int i = 0; i < bucketCapacity && l != 0L; i++) {
			l = array.get(position + i);
			// zero the long msb (which is 1 if fp has been flushed to disk)
			if (fp == (l & 0x7FFFFFFFFFFFFFFFL)) {
				return true;
			} else if (l == 0L && freePosition == -1) {
				if (i == 0) {
					tblLoad++;
				}
				// empty or disk written slot found, simply insert at _current_ position
				this.array.set(position + i, fp);
				this.tblCnt.getAndIncrement();
				return false;
			} else if (l < 0L && freePosition == -1) {
				// record free (disk written fp) slot
				freePosition = position + i;
			}
		}

		if (freePosition > -1 && !csLookup(fp)) {
			// Write to free slot
			this.array.set(freePosition, fp);
			this.tblCnt.getAndIncrement();
			return false;
		} else {
			// Index slot (bucket) overflow, thus add to collisionBucket
			boolean success = csInsert(fp);
			if (success) {
				this.tblCnt.getAndIncrement();
			}
			return !success;
		}
	}
	
	/**
	 * Inserts the given fingerprint into the {@link OffHeapDiskFPSet#collisionBucket}.
	 * @param fp
	 * @return true iff fp has been added to the collision bucket
	 */
	protected final boolean csInsert(long fp) {
		try {
			csRWLock.writeLock().lock();
			return collisionBucket.add(fp);
		} finally {
			csRWLock.writeLock().unlock();
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.DiskFPSet#getTblCapacity()
	 */
	public long getTblCapacity() {
		return maxTblCnt;
	}

	public static class Indexer {
		protected final int bcktCapacity;
		
		protected final long prefixMask;
		/**
		 * Number of bits to right shift bits during index calculation
		 * @see MSBDiskFPSet#moveBy
		 */
		protected final int moveBy;
		/**
		 * Number of bits to right shift bits during index calculation of
		 * striped lock.
		 */
		protected final int lockMoveBy;

		public Indexer(final int bucketCapacity, final long positions, int prefixBits) {
			this.bcktCapacity = bucketCapacity;
			this.prefixMask = 0xFFFFFFFFFFFFFFFFL >>> prefixBits;

			// Move n as many times to the right to calculate moveBy. moveBy is the
			// number of bits the (fp & mask) has to be right shifted to make the
			// logical bucket index.
			long n = (0xFFFFFFFFFFFFFFFFL >>> prefixBits) - (positions - 1);
			int moveBy = 0;
			while (n >= positions) {
				moveBy++;
				n = n >>> 1; // == (n/2)
			}
			this.moveBy = moveBy;
			
			// ...same for lock index.
			n = (0xFFFFFFFFFFFFFFFFL >>> prefixBits) - ((1 << LogLockCnt) - 1);
			moveBy = 0;
			while (n >= 1 << LogLockCnt) {
				moveBy++;
				n = n >>> 1; // == (n/2)
			}
			this.lockMoveBy = moveBy;
		}
		
		/* (non-Javadoc)
		 * @see tlc2.tool.fp.DiskFPSet#getLockIndex(long)
		 */
		protected int getLockIndex(long fp) {
			return (int) ((fp & prefixMask) >> lockMoveBy);
		}

		/**
		 * @param fp
		 * @return The logical bucket position in the table for the given fingerprint.
		 */
		protected long getLogicalPosition(final long fp) {
			// push MSBs for moveBy positions to the right and align with a bucket address
			long position = (fp & prefixMask) >> moveBy;
			position = floorToBucket(position);
			return position;
		}

		public long getNextBucketBasePosition(long logicalPosition) {
			return floorToBucket(logicalPosition + bcktCapacity);
		}
		
		/**
		 * Returns the largest position that
		 * is less than or equal to the argument and is equal to bucket base address.
		 * 
		 * @param logicalPosition
		 * @return
		 */
		private long floorToBucket(long logicalPosition) {
			long d = (long) Math.floor(logicalPosition / bcktCapacity);
			return bcktCapacity * d;
		}

		/**
		 * @param logicalPosition
		 * @return true iff logicalPosition is a multiple of bucketCapacity
		 */
		public boolean isBucketBasePosition(long logicalPosition) {
			return logicalPosition % bcktCapacity == 0;
		}
	}
	
	/**
	 * A {@link BitshiftingIndexer} uses the more efficient AND operation
	 * compared to MODULO and DIV used by {@link Indexer}. Since indexing is
	 * executed on every {@link FPSet#put(long)} or {@link FPSet#contains(long)}
	 * , it is worthwhile to minimize is execution overhead.
	 */
	public static class BitshiftingIndexer extends Indexer {
		
		/**
		 * Mask used to round of to a bucket address which is a power of 2.
		 */
		protected final long bucketBaseIdx;

		public BitshiftingIndexer(final int bucketCapacity, final long memoryInFingerprintCnt, final int prefixBits) throws RemoteException {
			super(bucketCapacity, memoryInFingerprintCnt, prefixBits);
			this.bucketBaseIdx = 0xFFFFFFFFFFFFFFFFL - (bcktCapacity - 1);
		}
		
		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.Indexer#getLogicalPosition(long)
		 */
		@Override
		protected long getLogicalPosition(final long fp) {
			// push MSBs for moveBy positions to the right and align with a bucket address
			return ((fp & prefixMask) >> moveBy)  & bucketBaseIdx; 
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.Indexer#getNextBucketPosition(long)
		 */
		@Override
		public long getNextBucketBasePosition(long logicalPosition) {
			return (logicalPosition + bcktCapacity) & bucketBaseIdx;
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.Indexer#isBucketBase(long)
		 */
		@Override
		public boolean isBucketBasePosition(long logicalPosition) {
			return (logicalPosition & (bcktCapacity - 1)) == 0;
		}
	}
	
	public class OffHeapMSBFlusher extends Flusher {
		
		/* (non-Javadoc)
		 * @see tlc2.tool.fp.DiskFPSet.Flusher#flushTable()
		 */
		@Override
		void flushTable() throws IOException {
			super.flushTable();
			
			// garbage old values in collision bucket
			collisionBucket.clear();
		}
		
		/* (non-Javadoc)
		 * @see tlc2.tool.fp.MSBDiskFPSet#mergeNewEntries(java.io.RandomAccessFile, java.io.RandomAccessFile)
		 */
		@Override
		protected void mergeNewEntries(RandomAccessFile inRAF, RandomAccessFile outRAF) throws IOException {
			final long buffLen = tblCnt.get();
			ByteBufferIterator itr = new ByteBufferIterator(array, collisionBucket, buffLen);

			// Precompute the maximum value of the new file
			long maxVal = itr.getLast();
			if (index != null) {
				maxVal = Math.max(maxVal, index[index.length - 1]);
			}

			int indexLen = calculateIndexLen(buffLen);
			index = new long[indexLen];
			index[indexLen - 1] = maxVal;
			currIndex = 0;
			counter = 0;

			// initialize positions in "buff" and "inRAF"
			long value = 0L; // initialize only to make compiler happy
			boolean eof = false;
			if (fileCnt > 0) {
				try {
					value = inRAF.readLong();
				} catch (EOFException e) {
					eof = true;
				}
			} else {
				eof = true;
			}

			// merge while both lists still have elements remaining
			boolean eol = false;
			long fp = itr.next();
			while (!eof || !eol) {
				if ((value < fp || eol) && !eof) {
					writeFP(outRAF, value);
					try {
						value = inRAF.readLong();
					} catch (EOFException e) {
						eof = true;
					}
				} else {
					// prevent converting every long to String when assertion holds (this is expensive)
					if (value == fp) {
						//MAK: Commented cause a duplicate does not pose a risk for correctness.
						// It merely indicates a bug somewhere.
						//Assert.check(false, EC.TLC_FP_VALUE_ALREADY_ON_DISK,
						//		String.valueOf(value));
						MP.printWarning(EC.TLC_FP_VALUE_ALREADY_ON_DISK, String.valueOf(value));
					}
					writeFP(outRAF, fp);
					// we used one fp up, thus move to next one
					try {
						fp = itr.next();
					} catch (NoSuchElementException e) {
						// has read all elements?
						Assert.check(!itr.hasNext(), EC.GENERAL);
						eol = true;
					}
				}
			}
			
			// both sets used up completely
			Assert.check(eof && eol, EC.GENERAL);

			// currIndex is amount of disk writes
			Assert.check(currIndex == indexLen - 1, EC.SYSTEM_INDEX_ERROR);

			// maintain object invariants
			fileCnt += buffLen;
		}
	}
	
	/**
	 * A non-thread safe Iterator 
	 */
	public class ByteBufferIterator {

		private final CollisionBucket cs;
		/**
		 * Number of elements in the buffer
		 */
		private long bufferElements;
		/**
		 * Total amount of elements in both the buffer as well as the collisionBucket. 
		 */
		private final long totalElements;
		/**
		 * The logical position is the position inside the {@link LongBuffer} and
		 * thus reflects a fingerprints
		 */
		private long logicalPosition = 0;
		/**
		 * Used to verify that the elements we hand out are strictly monotonic
		 * increasing.
		 */
		private long previous = -1l;
		/**
		 * Number of elements read with next()
		 */
		private long readElements = 0L;

		private long cache = -1L;
		private final LongArray array;

		public ByteBufferIterator(LongArray helper, CollisionBucket collisionBucket, long expectedElements) {
			this.array = helper;
			this.logicalPosition = 0L;
			this.totalElements = expectedElements;
			
			// Do calculation before prepareForFlush() potentially empties the cs causing size() to return 0 
			this.bufferElements = expectedElements - collisionBucket.size();
			
			this.cs = collisionBucket;
			this.cs.prepareForFlush();
		}

	    /**
	     * Returns the next element in the iteration.
	     *
	     * @return the next element in the iteration.
	     * @exception NoSuchElementException iteration has no more elements.
	     */
		public long next() {
			long result = -1l;

			if (cache < 0L && bufferElements > 0) {
				result = getNextFromBuffer();
				bufferElements--;
			} else {
				result = cache;
				cache = -1L;
			}

			if (!cs.isEmpty()) {
				long first = cs.first();
				if (result > first || result == -1L) {
					cs.remove(first);
					cache = result;
					result = first;
				}
			}
			
			// adhere to the general Iterator contract to fail fast and not hand out
			// meaningless values
			if (result == -1L) {
				throw new NoSuchElementException();
			}
			
			// hand out strictly monotonic increasing elements
			Assert.check(previous < result, EC.GENERAL);
			previous = result;
			
			// maintain read statistics
			readElements++;
			
			return result;
		}

		private long getNextFromBuffer() {
			sortNextBucket();
			
			long l = array.get(logicalPosition);
			if (l > 0L) {
				array.set(logicalPosition++, l | 0x8000000000000000L);
				return l;
			}
			
			while ((l = array.get(logicalPosition)) <= 0L && logicalPosition < maxTblCnt) {
				// increment position to next bucket
				logicalPosition = indexer.getNextBucketBasePosition(logicalPosition);
				sortNextBucket();
			}
			
			if (l > 0L) {
				array.set(logicalPosition++, l | 0x8000000000000000L);
				return l;
			}
			throw new NoSuchElementException();
		}

		// sort the current logical bucket if we reach the first slot of the
		// bucket
		private void sortNextBucket() {
			if (indexer.isBucketBasePosition(logicalPosition)) {
				long[] longBuffer = new long[bucketCapacity];
				int i = 0;
				for (; i < bucketCapacity; i++) {
					long l = array.get(logicalPosition + i);
					if (l <= 0L) {
						break;
					} else {
						longBuffer[i] = l;
					}
				}
				if (i > 0) {
					Arrays.sort(longBuffer, 0, i);
					for (int j = 0; j < i; j++) {
						array.set(logicalPosition + j,
								longBuffer[j]);
					}
				}
			}
		}

	    /**
	     * Returns <tt>true</tt> if the iteration has more elements. (In other
	     * words, returns <tt>true</tt> if <tt>next</tt> would return an element
	     * rather than throwing an exception.)
	     *
	     * @return <tt>true</tt> if the iterator has more elements.
	     */
		public boolean hasNext() {
			// hasNext does not move the indices at all!
			return readElements < totalElements;
		}
		
		/**
		 * @return The last element in the iteration.
	     * @exception NoSuchElementException if iteration is empty.
		 */
		public long getLast() {
			// Remember current position
			final long tmpLogicalPosition = logicalPosition;

			// Calculate last bucket position and have it sorted 
			logicalPosition = maxTblCnt - bucketCapacity;
			sortNextBucket();

			// Reverse the current bucket to obtain last element (More elegantly
			// this could be achieved recursively, but this can cause a
			// stack overflow).
			long l = 1L;
			while ((l = array.get(logicalPosition-- + bucketCapacity - 1)) <= 0L) {
				sortNextBucket();
			}
			
			// Done searching in-memory storage backwards, reset position to
			// original value.
			logicalPosition = tmpLogicalPosition;
			
			// Compare max element found in main in-memory buffer to man
			// element in collisionBucket. Return max of the two.
			if (!cs.isEmpty()) {
				l = Math.max(cs.last(), l);
			}
			
			// Either return the maximum element or fail fast.
			if (l > 0L) {
				return l;
			}
			throw new NoSuchElementException();
		}
	}
	
	public interface CollisionBucket {
		void clear();

		void prepareForFlush();

		void remove(long first);

		long first();
		
		long last();

		boolean isEmpty();

		/**
		 * @param fp
	     * @return {@code true} if this set did not already contain the specified
	     *         fingerprint
		 */
		boolean add(long fp);

		boolean contains(long fp);

		long size();
	}
	
	public class TreeSetCollisionBucket implements CollisionBucket {
		private final TreeSet<Long> set;

		public TreeSetCollisionBucket(int initialCapacity) {
			this.set = new TreeSet<Long>();
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#clear()
		 */
		public void clear() {
			set.clear();
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#prepareForFlush()
		 */
		public void prepareForFlush() {
			// no-op
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#remove(long)
		 */
		public void remove(long first) {
			set.remove(first);
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#first()
		 */
		public long first() {
			return set.first();
		}
		
		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#last()
		 */
		public long last() {
			return set.last();
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#isEmpty()
		 */
		public boolean isEmpty() {
			return set.isEmpty();
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#add(long)
		 * 
		 * If this set already contains the element, the call leaves the set
		 * unchanged and returns false.
		 */
		public boolean add(long fp) {
			return set.add(fp);
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#contains(long)
		 */
		public boolean contains(long fp) {
			return set.contains(fp);
		}

		/* (non-Javadoc)
		 * @see tlc2.tool.fp.OffHeapDiskFPSet.CollisionBucket#size()
		 */
		public long size() {
			return set.size();
		}
	}
}
