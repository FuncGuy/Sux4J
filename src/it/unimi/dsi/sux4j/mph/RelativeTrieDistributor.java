package it.unimi.dsi.sux4j.mph;

/*		 
 * Sux4J: Succinct data structures for Java
 *
 * Copyright (C) 2008 Sebastiano Vigna 
 *
 *  This library is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as published by the Free
 *  Software Foundation; either version 2.1 of the License, or (at your option)
 *  any later version.
 *
 *  This library is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 */

import it.unimi.dsi.Util;
import it.unimi.dsi.bits.BitVector;
import it.unimi.dsi.bits.Fast;
import it.unimi.dsi.bits.LongArrayBitVector;
import it.unimi.dsi.bits.TransformationStrategies;
import it.unimi.dsi.bits.TransformationStrategy;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongFunction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.sux4j.bits.Rank9;
import it.unimi.dsi.util.LongBigList;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;

import org.apache.log4j.Logger;

/** A distributor based on a relative trie.
 *
 * <p>A relative trie behaves like a trie, but only on a subset of all possible keys.
 */

public class RelativeTrieDistributor<T> extends AbstractObject2LongFunction<T> {
	private final static Logger LOGGER = Util.getLogger( RelativeTrieDistributor.class );
	private static final long serialVersionUID = 1L;
	private static final boolean DEBUG = false;
	private static final boolean DDEBUG = false;
	private static final boolean DDDEBUG = false;
	private static final boolean ASSERTS = false;

	/** An integer representing the exit-on-the-left behaviour. */
	private final static int LEFT = 0;
	/** An integer representing the exit-on-the-right behaviour. */
	private final static int RIGHT = 1;
	/** A ranking structure on the vector containing leaves plus p0,p1, etc. */
	private final Rank9 leaves;
	/** The transformation used to map object to bit vectors. */
	private final TransformationStrategy<? super T> transformationStrategy;
	/** For each external node and each possible path, the related behaviour. */
	private final MWHCFunction<BitVector> behaviour;
	/** The number of elements of the set upon which the trie is built. */
	private final int size;
	private MWHCFunction<BitVector> signatures;
	private TwoStepsLcpMonotoneMinimalPerfectHashFunction<BitVector> ranker;
	private long logWMask;
	private int logW;
	private long signatureMask;
	private int numDelimiters;
	private IntOpenHashSet mistakeSignatures;
	private MWHCFunction<BitVector> corrections;
	/** Whether the underlying probabilistic trie is empty. */
	private boolean emptyTrie;
	/** Whether there are no delimiters. */
	private boolean noDelimiters;
	private long seed;
	
	/** An intermediate class containing the compacted trie generated by the delimiters. */
	private final static class IntermediateTrie<T> {
		/** The root of the trie. */
		protected final Node root;
		/** The number of elements of the set upon which the trie is built. */
		protected final int numElements;
		/** The values associated to the keys in {@link #externalKeysFile}. */
		private LongBigList externalValues;
		/** The string representing the parent of each key in {@link #externalKeysFile}. */
		private IntArrayList externalParentRepresentations;
		private long w;
		private int logW;
		private int logLogW;
		private long logWMask;
		private int signatureSize;
		private long signatureMask;
		private ObjectArrayList<LongArrayBitVector> internalNodeKeys;
		private ObjectArrayList<LongArrayBitVector> internalNodeRepresentations;
		private LongArrayList internalNodeSignatures;
		private ObjectLinkedOpenHashSet<BitVector> delimiters;
		private ObjectLinkedOpenHashSet<BitVector> leaves;
		
		/** A node in the trie. */
		private static class Node {
			/** Left child. */
			private Node left;
			/** Right child. */
			private Node right;
			/** The path compacted in this node (<code>null</code> if there is no compaction at this node). */
			private final LongArrayBitVector path;
			/** The index of this node in breadth-first order. */
			private int index;

			/** Creates a node. 
			 * 
			 * @param left the left child.
			 * @param right the right child.
			 * @param path the path compacted at this node.
			 */
			public Node( final Node left, final Node right, final LongArrayBitVector path ) {
				this.left = left;
				this.right = right;
				this.path = path;
			}

			/** Returns true if this node is a leaf.
			 * 
			 * @return true if this node is a leaf.
			 */
			public boolean isLeaf() {
				return right == null && left == null;
			}
			
			public String toString() {
				return "[" + path + "]";
			}
		}
		
		void labelIntermediateTrie( final Node node, final LongArrayBitVector path,
				final ObjectLinkedOpenHashSet<BitVector> delimiters, 
				final ObjectArrayList<LongArrayBitVector> representations, 
				final ObjectArrayList<LongArrayBitVector>keys, 
				final LongArrayList values,
				final long seed,
				final boolean left ) {
			if ( ASSERTS ) assert ( node.left != null ) == ( node.right != null );

			long parentPathLength = Math.max( 0, path.length() - 1 );

			if ( node.left != null ) {
				path.append( node.path );

				labelIntermediateTrie( node.left, path.append( 0, 1 ), delimiters, representations, keys, values, seed, true );
				path.remove( (int)( path.length() - 1 ) );

				final long h = Hashes.jenkins( path, seed );
				final long p = ( -1L << Fast.mostSignificantBit( parentPathLength ^ path.length() ) & path.length() );

				if ( ASSERTS ) assert p <= path.length() : p + " > " + path.length();
				if ( ASSERTS ) assert path.length() == 0 || p > parentPathLength : p + " <= " + parentPathLength;

				keys.add( LongArrayBitVector.copy( path.subVector( 0, p ) ) );
				representations.add( path.copy() );
				if ( ASSERTS ) assert Fast.length( path.length() ) <= logW;
				if ( DDDEBUG ) System.err.println( "Entering " + path + " with key (" + p + "," + path.subVector( 0, p ).hashCode() + ") " + path.subVector( 0, p ) + ", signature " + ( h & signatureMask ) + " and length " + ( path.length() & logWMask ) + "(value: " + (( h & signatureMask ) << logW | ( path.length() & logWMask )) + ")" );

				values.add( ( h & signatureMask ) << logW | ( path.length() & logWMask ) );
				
				labelIntermediateTrie( node.right, path.append( 1, 1 ), delimiters, representations, keys, values, seed, false );

				path.length( path.length() - node.path.length() - 1 );
			}
			else {
				if ( left ) delimiters.add( LongArrayBitVector.copy( path.subVector( 0, path.lastOne() + 1 ) ) );
				else delimiters.add( LongArrayBitVector.copy( path ) );
			}
		}

		
		/** Creates a partial compacted trie using given elements, bucket size and transformation strategy.
		 * 
		 * @param elements the elements among which the trie must be able to rank.
		 * @param log2BucketSize the logarithm of the size of a bucket.
		 * @param transformationStrategy a transformation strategy that must turn the elements in <code>elements</code> into a list of
		 * distinct, prefix-free, lexicographically increasing (in iteration order) bit vectors.
		 */
		
		public IntermediateTrie( final Iterable<? extends T> elements, final int log2BucketSize, final TransformationStrategy<? super T> transformationStrategy, final long seed ) {
			
			Iterator<? extends T> iterator = elements.iterator(); 
			final long bucketSizeMask = ( 1L << log2BucketSize ) - 1;
			
			leaves = DDEBUG ? new ObjectLinkedOpenHashSet<BitVector>() : null;
			if ( iterator.hasNext() ) {
				LongArrayBitVector prev = LongArrayBitVector.copy( transformationStrategy.toBitVector( iterator.next() ) );
				LongArrayBitVector prevDelimiter = LongArrayBitVector.getInstance();
				
				Node node, root = null;
				BitVector curr;
				int cmp, pos, prefix, count = 1;
				long maxLength = prev.length();
				
				while( iterator.hasNext() ) {
					// Check order
					curr = transformationStrategy.toBitVector( iterator.next() ).fast();
					cmp = prev.compareTo( curr );
					if ( cmp == 0 ) throw new IllegalArgumentException( "The input bit vectors are not distinct" );
					if ( cmp > 0 ) throw new IllegalArgumentException( "The input bit vectors are not lexicographically sorted" );
					if ( curr.longestCommonPrefixLength( prev ) == prev.length() ) throw new IllegalArgumentException( "The input bit vectors are not prefix-free" );

					if ( ( count & bucketSizeMask ) == 0 ) {
						// Found delimiter. Insert into trie.
						if ( root == null ) {
							root = new Node( null, null, prev.copy() );
							if ( DDEBUG ) leaves.add( prev.copy() );
							prevDelimiter.replace( prev );
						}
						else {
							prefix = (int)prev.longestCommonPrefixLength( prevDelimiter );

							pos = 0;
							node = root;
							Node n = null;
							while( node != null ) {
								final long pathLength = node.path.length();
								if ( prefix < pathLength ) {
									n = new Node( node.left, node.right, node.path.copy( prefix + 1, pathLength ) );
									node.path.length( prefix );
									node.path.trim();
									node.left = n;
									node.right = new Node( null, null, prev.copy( pos + prefix + 1, prev.length() ) ); 
									break;
								}

								prefix -= pathLength + 1;
								pos += pathLength + 1;
								node = node.right;
								if ( ASSERTS ) assert node == null || prefix >= 0 : prefix + " <= " + 0;
							}

							if ( ASSERTS ) assert node != null;

							if ( DDEBUG ) leaves.add( prev.copy() );
							prevDelimiter.replace( prev );
						}
					}
					prev.replace( curr );
					maxLength = Math.max( maxLength, prev.length() );
					count++;
				}

				
				numElements = count;
				logLogW = Fast.ceilLog2( Fast.ceilLog2( maxLength ) );
				logW = 1 << logLogW;
				w = 1L << logW;
				logWMask = ( 1L << logW ) - 1;
				signatureSize = logLogW + log2BucketSize; // A small additive constant to avoid excessively small signatures
				signatureMask = ( 1L << signatureSize ) - 1;
				
				if ( ASSERTS ) assert logW + signatureSize <= Long.SIZE;
				
				this.root = root;
				
				if ( DEBUG ) System.err.println( "w: " + w );
				if ( DDEBUG ) {
					System.err.println( "Leaves (" + leaves.size() + "): " + leaves );
					System.err.println( this );
				}
				
				internalNodeRepresentations = new ObjectArrayList<LongArrayBitVector>();

				if ( root != null ) {
					LOGGER.info( "Computing approximate structure..." );

					internalNodeSignatures = new LongArrayList();
					internalNodeKeys = new ObjectArrayList<LongArrayBitVector>();
					delimiters = new ObjectLinkedOpenHashSet<BitVector>();
					labelIntermediateTrie( root, LongArrayBitVector.getInstance(), delimiters, internalNodeRepresentations, internalNodeKeys, internalNodeSignatures, seed, true );

					if ( DDEBUG ) {
						System.err.println( "Delimiters (" + delimiters.size() + "): " + delimiters );
						Iterator<BitVector> d = delimiters.iterator(), l = leaves.iterator();
						for( int i = 0; i < delimiters.size(); i++ ) {
							BitVector del = d.next(), leaf = l.next();
							assert del.longestCommonPrefixLength( leaf ) == del.length() : del.longestCommonPrefixLength( leaf ) + " != " + del.length() + "\n" + del + "\n" + leaf + "\n";
						}
						assert ! l.hasNext();
						
						System.err.println( "Internal node representations: " + internalNodeRepresentations );
						System.err.println( "Internal node signatures: " + internalNodeSignatures );
					}

					LOGGER.info( "Computing function keys..." );

					externalValues = LongArrayBitVector.getInstance().asLongBigList( 1 );
					externalParentRepresentations = new IntArrayList( numElements );
					iterator = elements.iterator();

					// The stack of nodes visited the last time
					final Node stack[] = new Node[ (int)maxLength ];
					// The length of the path compacted in the trie up to the corresponding node, excluded
					final int[] len = new int[ (int)maxLength ];
					stack[ 0 ] = root;
					int depth = 0, behaviour, c = 0;
					boolean first = true;
					BitVector currFromPos, path;
					LongArrayBitVector nodePath;

					while( iterator.hasNext() ) {
						curr = transformationStrategy.toBitVector( iterator.next() ).fast();
						if ( DDDEBUG ) System.err.println( "Analysing key " + curr + "..." );
						if ( ! first )  {
							// Adjust stack using lcp between present string and previous one
							prefix = (int)prev.longestCommonPrefixLength( curr );
							while( depth > 0 && len[ depth ] > prefix ) depth--;
						}
						else first = false;
						node = stack[ depth ];
						pos = len[ depth ];

						for(;;) {
							nodePath = node.path;
							currFromPos = curr.subVector( pos ); 
							prefix = (int)currFromPos.longestCommonPrefixLength( nodePath );

							if ( DDDEBUG ) System.err.println( "prefix: " + prefix + " nodePath.length(): " + nodePath.length() + ( prefix < nodePath.length() ? " bit: " + String.valueOf( nodePath.getBoolean( prefix ) ) : "" ) + " node.isLeaf(): " + node.isLeaf() );
							
							if ( prefix < nodePath.length() || node.isLeaf() ) {
								// Exit. LEFT or RIGHT, depending on the bit at the end of the common prefix. The
								// path is the remaining path at the current position for external nodes, or a prefix of length
								// at most pathLength for internal nodes.
								behaviour = prefix < nodePath.length() && ! nodePath.getBoolean( prefix ) ? RIGHT : LEFT;
								path = curr;

								externalValues.add( behaviour );
								externalParentRepresentations.add( depth == 0 ? pos : pos - 1 );
								
								if ( DDDEBUG ) {
									System.err.println( "Computed " + ( node.isLeaf() ? "leaf " : "" ) + "mapping " + c + " <" + node.index + ", " + path + "> -> " + behaviour );
									System.err.println( "Root: " + root + " node: " + node + " representation length: " + ( depth == 0 ? pos : pos - 1 ) );
								}

								break;
							}

							pos += nodePath.length() + 1;
							if ( pos > curr.length() ) {
								assert false;
								break;
							}
							// System.err.println( curr.getBoolean( pos - 1 ) ? "Turning right" : "Turning left" );
							node = curr.getBoolean( pos - 1 ) ? node.right : node.left;
							// Update stack
							len[ ++depth ] = pos;
							stack[ depth ] = node;
						}

						prev.replace( curr );
						c++;
					}

				}
			}
			else {
				// No elements.
				this.root = null;
				this.numElements = 0;
			}
		}

		private void recToString( final Node n, final MutableString printPrefix, final MutableString result, final MutableString path, final int level ) {
			if ( n == null ) return;
			
			result.append( printPrefix ).append( '(' ).append( level ).append( ')' );
			
			if ( n.path != null ) {
				path.append( n.path );
				result.append( " path: (" ).append( n.path.length() ).append( ") :" ).append( n.path );
			}

			result.append( '\n' );
			
			path.append( '0' );
			recToString( n.left, printPrefix.append( '\t' ).append( "0 => " ), result, path, level + 1 );
			path.charAt( path.length() - 1, '1' ); 
			recToString( n.right, printPrefix.replace( printPrefix.length() - 5, printPrefix.length(), "1 => "), result, path, level + 1 );
			path.delete( path.length() - 1, path.length() ); 
			printPrefix.delete( printPrefix.length() - 6, printPrefix.length() );
			
			path.delete( (int)( path.length() - n.path.length() ), path.length() );
		}
		
		public String toString() {
			MutableString s = new MutableString();
			recToString( root, new MutableString(), s, new MutableString(), 0 );
			return s.toString();
		}

	}
	

	/** Creates a partial compacted trie using given elements, bucket size, transformation strategy, and temporary directory.
	 * 
	 * @param elements the elements among which the trie must be able to rank.
	 * @param log2BucketSize the logarithm of the size of a bucket.
	 * @param transformationStrategy a transformation strategy that must turn the elements in <code>elements</code> into a list of
	 * distinct, lexicographically increasing (in iteration order) bit vectors.
	 * @param tempDir the directory where temporary files will be created, or <code>null</code> for the default directory.
	 */
	public RelativeTrieDistributor( final Iterable<? extends T> elements, final int log2BucketSize, final TransformationStrategy<? super T> transformationStrategy, final TripleStore<BitVector> tripleStore ) {
		this.transformationStrategy = transformationStrategy;
		this.seed = tripleStore.seed();
		final IntermediateTrie<T> intermediateTrie = new IntermediateTrie<T>( elements, log2BucketSize, transformationStrategy, seed );

		size = intermediateTrie.numElements;
		emptyTrie = intermediateTrie.internalNodeRepresentations.isEmpty();
		noDelimiters = intermediateTrie.delimiters == null || intermediateTrie.delimiters.isEmpty();

		if ( noDelimiters ) {
			behaviour = null;
			signatures = null;
			leaves = null;
			return;
		}
		
		logWMask = intermediateTrie.logWMask;
		logW =  intermediateTrie.logW;
		signatureMask =  intermediateTrie.signatureMask;

		behaviour = new MWHCFunction<BitVector>( TransformationStrategies.wrap( elements, transformationStrategy ), TransformationStrategies.identity(), intermediateTrie.externalValues, 1, tripleStore );
		intermediateTrie.externalValues = null;

		if ( ! emptyTrie ) {
			numDelimiters = intermediateTrie.delimiters.size();
			final int numInternalNodeRepresentations = intermediateTrie.internalNodeRepresentations.size();

			if ( DDEBUG ) {
				System.err.println( "Internal node representations: " + intermediateTrie.internalNodeRepresentations );
				System.err.println( "Internal node keys: " + intermediateTrie.internalNodeKeys );
			}

			signatures = new MWHCFunction<BitVector>( intermediateTrie.internalNodeKeys, TransformationStrategies.identity(), intermediateTrie.internalNodeSignatures, intermediateTrie.logW + intermediateTrie.signatureSize );
			intermediateTrie.internalNodeKeys = null;
			intermediateTrie.internalNodeSignatures = null;

			ObjectOpenHashSet<LongArrayBitVector> rankers = new ObjectOpenHashSet<LongArrayBitVector>();

			for( BitVector bv: intermediateTrie.internalNodeRepresentations ) {
				rankers.add( LongArrayBitVector.copy( bv.subVector( 0, bv.lastOne() + 1 ) ) );
				rankers.add( LongArrayBitVector.copy( bv ).append( 1, 1 ) );
				LongArrayBitVector plus1 = LongArrayBitVector.copy( bv );
				long lastZero = plus1.lastZero();
				if ( lastZero != -1 ) {
					plus1.length( lastZero + 1 );
					plus1.set( lastZero );
					rankers.add( plus1 );
				}
			}

			intermediateTrie.internalNodeRepresentations = null;

			LongArrayBitVector[] rankerArray = rankers.toArray( new LongArrayBitVector[ rankers.size() ] );
			rankers = null;
			Arrays.sort( rankerArray );

			if ( DDEBUG ) {
				System.err.println( "Rankers: " );
				for( BitVector bv: rankerArray ) System.err.println( bv );
				System.err.println();
			}

			LongArrayBitVector leavesBitVector = LongArrayBitVector.ofLength( numInternalNodeRepresentations * 3 );
			int q = 0;

			for( BitVector v : rankerArray ) {
				if ( intermediateTrie.delimiters.contains( v ) ) leavesBitVector.set( q );
				q++;
			}
			leavesBitVector.length( q ).trim();
			leaves = new Rank9( leavesBitVector );

			if ( DDEBUG ) System.err.println( "Rank bit vector: " + leavesBitVector );

			ranker = new TwoStepsLcpMonotoneMinimalPerfectHashFunction<BitVector>( Arrays.asList( rankerArray ), TransformationStrategies.prefixFree() );

			// Compute errors to be corrected
			this.mistakeSignatures = new IntOpenHashSet();

			final IntOpenHashSet mistakeSignatures = new IntOpenHashSet();
			int c;

			Iterator<BitVector>iterator = TransformationStrategies.wrap( elements.iterator(), transformationStrategy );
			c = 0;
			int mistakes = 0;
			while( iterator.hasNext() ) {
				BitVector curr = iterator.next();
				if ( DEBUG ) System.err.println( "Checking element number " + c + ( ( c + 1 ) % ( 1L << log2BucketSize ) == 0 ? " (bucket)" : "" ));
				if ( getNodeStringLength( curr ) != intermediateTrie.externalParentRepresentations.getInt( c ) ){
					if ( DEBUG ) System.err.println( "Error! " + getNodeStringLength( curr ) + " != " + intermediateTrie.externalParentRepresentations.getInt( c ) );
					long h = Hashes.jenkins( curr );
					mistakeSignatures.add( (int)( h ^ h >>> 32 ) );
					mistakes++;
				}

				c++;
			}
			LOGGER.info( "Errors: " + mistakes + " (" + ( 100.0 * mistakes / size ) + "%)" );
			if ( ASSERTS ) assert (double)mistakes / size < .5 : 100.0 * mistakes / size + "% >= 50%";  
			
			ObjectArrayList<BitVector> positives = new ObjectArrayList<BitVector>();
			LongArrayList results = new LongArrayList();
			c = 0;

			for( BitVector curr: TransformationStrategies.wrap( elements, transformationStrategy ) ) {
				long h = Hashes.jenkins( curr );
				if ( mistakeSignatures.contains( (int)( h ^ h >>> 32 ) ) ) {
					positives.add( curr.copy() );
					results.add( intermediateTrie.externalParentRepresentations.getInt( c ) ); 
				}
				c++;
			}

			LOGGER.info( "False errors: " + ( positives.size() - mistakes ) + ( positives.size() != 0 ? " (" +  100 * ( positives.size() - mistakes ) / ( positives.size() ) + "%)" : "" ) );
			this.mistakeSignatures.addAll( mistakeSignatures );
			corrections = new MWHCFunction<BitVector>( positives, TransformationStrategies.identity(), results, logW );

			LOGGER.debug( "Signature bits per element: " + (double)signatures.numBits() / size );
			LOGGER.debug( "Ranker bits per element: " + (double)ranker.numBits() / size );
			LOGGER.debug( "Leaves bits per element: " + (double)leaves.numBits() / size );
			LOGGER.debug( "Mistake bits per element: " + (double)numBitsForMistakes() / size );
			LOGGER.debug( "Behaviour bits per element: " + (double)behaviour.numBits() / size );
		}
		else {
			signatures = null;
			leaves = null;
		}
		

		if ( ASSERTS ) {
			final Iterator<BitVector> iterator = TransformationStrategies.wrap( elements.iterator(), transformationStrategy );
			int c = 0;
			while( iterator.hasNext() ) {
				BitVector curr = iterator.next();
				if ( DEBUG ) System.err.println( "Checking element number " + c + ( ( c + 1 ) >>> log2BucketSize == 0 ? " (bucket)" : "" ));
				long t = getLong( curr );
				assert t == c >>> log2BucketSize : "At " + c + ": " + ( c >>> log2BucketSize ) + " != " + t;
				c++;
			}		
		}

		
	}

	
	private long getNodeStringLength( BitVector v ) {
		if ( DEBUG ) System.err.println( "getNodeStringLength(" + v + ")..." );
		
		long state[][] = Hashes.preprocessJenkins( v, seed );
		final long[] a = state[ 0 ], b = state[ 1 ], c = state[ 2 ];
		
		final long corr = Hashes.jenkins( v, v.length(), a, b, c );
		if ( mistakeSignatures.contains( (int)( corr ^ corr >>> 32 ) ) ) {
			if ( DEBUG ) System.err.println( "Correcting..." );
			return corrections.getLong( v );
		}
		
		long r = v.length();
		long l = 0;
		int i = Fast.mostSignificantBit( r );
		long mask = 1L << i;
		final long[] triple = new long[ 3 ];
		while( r - l > 1 ) {
			if ( ASSERTS ) assert i > -1;
			if ( DDDEBUG ) System.err.println( "[" + l + ".." + r + "]; i = " + i );
			
			if ( ( l & mask ) != ( r - 1 & mask ) ) {
				final long f = ( r - 1 ) & ( -1L << i );
				Hashes.jenkins( v, f, a, b, c, triple );
				if ( ASSERTS ) assert seed == signatures.globalSeed : seed + " != " + signatures.globalSeed;
				long data = signatures.getLongByTriple( triple );
				if ( ASSERTS ) assert data == signatures.getLong( v.subVector( 0, f ) );
				
				if ( DDDEBUG ) System.err.println( "Recalled (" + f + "," + ( triple[ 0 ] & signatureMask ) + ") " + v.subVector( 0, f ) + " (signature: " + ( data >>> logW ) + " length: " + ( data & logWMask ) + " data: " + data + ")" );
				
				if ( data == -1 ) {
					if ( DDDEBUG ) System.err.println( "Missing " + v.subVector( 0, f )  );
					r = f;
				}
				else {
					long g = data & logWMask;

					if ( g > v.length() ) {
						if ( DDDEBUG ) System.err.println( "Excessive length for " + v.subVector( 0, f )  );
						r = f;
					}
					else {
						long h = Hashes.jenkins( v, g, a, b, c );
						
						if ( ASSERTS ) assert h == Hashes.jenkins( v.subVector( 0, g ), seed );

						if ( DDDEBUG ) System.err.println( "Testing signature " + ( h & signatureMask ) );

						if ( ( data >>> logW ) == ( h & signatureMask ) && g >= f ) l = g;
						else r = f;
					}
				}
			}
				
			i--;
			mask >>= 1;
		}
		
		if ( DEBUG ) System.err.println( "getNodeStringLength(" + v + ") => " + l );
		return l;
	}
	
	@SuppressWarnings("unchecked")
	public long getLong( final Object o ) {
		if ( noDelimiters ) return 0;
		final BitVector v = (BitVector)o;
		final int b = (int)behaviour.getLong( o );
		if ( emptyTrie ) return b;
		final long length = getNodeStringLength( v );
		final BitVector key = v.subVector( 0, length ).copy();
		final boolean bit = v.getBoolean( length );
		
		if ( b == LEFT ) {
			//System.err.println( "LEFT: " + bit );
			if ( bit ) key.add( true );
			else key.length( key.lastOne() + 1 );
			long pos = ranker.getLong( key );
			//System.err.println( key.length() + " " + pos);
			return leaves.rank( pos ); 
		}
		else {
			//System.err.println( "RIGHT: " + bit );
			if ( bit ) {
				final long lastZero = key.lastZero();
				//System.err.println( lastZero );
				if ( lastZero == -1 ) return numDelimiters;	// We are exiting at the right of 1^k (k>=0).
				key.length( lastZero + 1 ).set( lastZero );
				long pos = ranker.getLong( key );
				//System.err.println( "pos: " + pos + " rank: " + leaves.rank( pos ) );
				return leaves.rank( pos ); 
			}
			else {
				key.add( true );
				long pos = ranker.getLong( key );
				return leaves.rank( pos ); 
			}
		}
	}

	private long numBitsForMistakes() {
		if ( emptyTrie ) return 0;
		return corrections.numBits() + mistakeSignatures.size() * Integer.SIZE;
	}
	
	public long numBits() {
		if ( emptyTrie ) return 0;
		return behaviour.numBits() + signatures.numBits() + ranker.numBits() + leaves.numBits() + transformationStrategy.numBits() + numBitsForMistakes(); 
	}
	
	public boolean containsKey( Object o ) {
		return true;
	}

	public int size() {
		return size;
	}
}
