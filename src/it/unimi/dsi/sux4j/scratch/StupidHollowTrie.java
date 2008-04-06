package it.unimi.dsi.sux4j.scratch;

import static it.unimi.dsi.bits.Fast.length;
import it.unimi.dsi.Util;
import it.unimi.dsi.bits.BitVector;
import it.unimi.dsi.bits.BitVectors;
import it.unimi.dsi.bits.Fast;
import it.unimi.dsi.bits.LongArrayBitVector;
import it.unimi.dsi.bits.TransformationStrategies;
import it.unimi.dsi.bits.TransformationStrategy;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.objects.AbstractObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.io.FastBufferedReader;
import it.unimi.dsi.io.LineIterator;
import it.unimi.dsi.sux4j.bits.Rank9;
import it.unimi.dsi.sux4j.bits.SimpleSelect;
import it.unimi.dsi.sux4j.mph.AbstractHashFunction;
import it.unimi.dsi.sux4j.mph.HollowTrie;
import it.unimi.dsi.sux4j.util.TwoSizesLongBigList;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Iterator;
import java.util.Random;

import org.apache.log4j.Logger;

import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.Parameter;
import com.martiansoftware.jsap.SimpleJSAP;
import com.martiansoftware.jsap.Switch;
import com.martiansoftware.jsap.stringparsers.ForNameStringParser;

public class StupidHollowTrie<T> extends AbstractHashFunction<T> implements Serializable {
	private static final Logger LOGGER = Util.getLogger( StupidHollowTrie.class );
	private static final long serialVersionUID = 0L;

	private static final boolean ASSERTS = false;
	private static final boolean DEBUG = false;
	
	private TwoSizesLongBigList skips;
	private transient BitVector trie;
	public final Rank9 rank9;
	public final SimpleSelect select;
	public final double avgDepth;
	private final TransformationStrategy<? super T> transform;
	private int size;
	
	private final static class Node {
		Node left, right;
		int skip;
		int weight;
		
		public Node( final Node left, final Node right, final int skip ) {
			this.left = left;
			this.right = right;
			this.skip = skip;
			if ( ASSERTS ) assert skip >= 0;
		}
	}
	
	
	@SuppressWarnings("unchecked")
	public long getLong( final Object object ) {
		if ( size <= 1 ) return size - 1;
		final BitVector bitVector = transform.toBitVector( (T)object );
		long p = 0, r = 0, length = bitVector.length(), index = 0, a = 0, b = 0, t;
		int s = 0;
		
		for(;;) {
			if ( ( s += (int)skips.getLong( r ) ) >= length ) return -1;

			//System.out.print( "Turning " + ( bitVector.getBoolean( s ) ? "right" : "left" ) + " at bit " + s + "... " );
			if ( bitVector.getBoolean( s ) ) p = 2 * r + 2;
			else p = 2 * r + 1;

			t = 2 * rank9.rank( a, b + 1 );
			a = b + 1;
			b += t;
			
			index += p - a - rank9.rank( a, p );

			//System.err.println( a + " " + b + " " + p + " " + index );
			
			if ( ASSERTS ) assert p < trie.length();
			if ( ! trie.getBoolean( p ) ) break;

			r = rank9.rank( p + 1 ) - 1;
			
			s++;
		}
		
		//System.out.println();
		// Complete computation of leaf index
		
		for(;;) {
			p = select.select( ( r = rank9.rank( p + 1 ) ) - 1 );
			if ( p < a ) break;
			p = r * 2;
			
			t = 2 * rank9.rank( a, b + 1 );
			a = b + 1;
			b += t;
			
			index += p - a + 1 - rank9.rank( a, p + 1 );
			
			//System.err.println( a + " " + b + " " + p + " " + index );
		}

		return index;
	}
	
	public StupidHollowTrie( final Iterable<? extends T> iterable, final TransformationStrategy<? super T> transform, int maxWeight ) {
		this( iterable.iterator(), transform, maxWeight );
	}
		
	public StupidHollowTrie( final Iterator<? extends T> iterator, final TransformationStrategy<? super T> transform, int maxWeight ) {

		this.transform = transform;

		int size = 0;
		
		Node root = null, node, parent;
		int prefix, numNodes = 0, cmp;

		if ( iterator.hasNext() ) {
			BitVector prev = transform.toBitVector( iterator.next() ).copy(), curr;
			size++;

			while( iterator.hasNext() ) {
				size++;
				curr = transform.toBitVector( iterator.next() );
				cmp = prev.compareTo( curr );
				if ( cmp == 0 ) throw new IllegalArgumentException( "The input bit vectors are not distinct" );
				if ( cmp > 0 ) throw new IllegalArgumentException( "The input bit vectors are not lexicographically sorted" );
				prefix = (int)curr.longestCommonPrefixLength( prev );
				if ( prefix == prev.length() ) throw new IllegalArgumentException( "The input bit vectors are not prefix-free" );

				node = root;
				parent = null;
				Node n = null;
				while( node != null ) {
					if ( prefix < node.skip ) {
						n = new Node( node, null, prefix );
						numNodes++;
						if ( parent == null ) {
							root.skip -= prefix + 1;
							if ( ASSERTS ) assert root.skip >= 0;
							root = n;
						}
						else {
							parent.right = n;
							node.skip -= prefix + 1;
							if ( ASSERTS ) assert node.skip >= 0;
						}
						break;
					}

					prefix -= node.skip + 1;
					parent = node;
					node = node.right;
				}

				if ( node == null ) {
					if ( parent == null ) root = new Node( null, null, prefix );
					else parent.right = new Node( null, null, prefix );
					numNodes++;
				}

				if ( ASSERTS ) {
					long s = 0;
					Node m = root;
					while( m != null ) {
						s += m.skip;
						if ( curr.getBoolean( s ) ) {
							if ( m.right == null ) break;
						}
						else if ( m.left == null ) break;
						m = curr.getBoolean( s ) ? m.right : m.left;
						s++;
					}
					assert parent == null || ( node == null ? m == parent.right : m == n );
				}

				prev = curr.copy();
			}
		}
		
		
		avgDepth = sumDepth( root, 0 ) / (double)size;
		// Reduced
		setWeight( root );
		LOGGER.debug( "Original size: " + size );
		numNodes = size = cutLarge( root, maxWeight );
		LOGGER.debug( "After cutting: " + size );

		if ( size <= 1 ) {
			rank9 = new Rank9( LongArrays.EMPTY_ARRAY, 0 );
			select = new SimpleSelect( LongArrays.EMPTY_ARRAY, 0 );
			trie = BitVectors.EMPTY_VECTOR;
			return;
		}

		final BitVector bitVector = LongArrayBitVector.getInstance( 2 * numNodes + 1 );
		final ObjectArrayList<Node> queue = new ObjectArrayList<Node>();
		final IntArrayList skips = new IntArrayList();
		int p = 0, maxSkip = Integer.MIN_VALUE;
		long skipsLength = 0;
		bitVector.add( 1 );
		queue.add( root );

		root = null;
		Node n;
		
		while( p < queue.size() ) {
			n = queue.get( p );
			if ( maxSkip < n.skip ) maxSkip = n.skip;
			skips.add( n.skip );
			skipsLength += length( n.skip );
			bitVector.add( n.left != null );
			bitVector.add( n.right != null );
			if ( n.left != null ) queue.add( n.left );
			if ( n.right != null ) queue.add( n.right );
			p++;
		}
		
		trie = bitVector;
		rank9 = new Rank9( bitVector );
		select = new SimpleSelect( bitVector );
		final int skipWidth = Fast.ceilLog2( maxSkip );

		LOGGER.info( "Max skip: " + maxSkip );
		LOGGER.info( "Max skip width: " + skipWidth );
		/*this.skips = LongArrayBitVector.getInstance( skipsLength );
		final LongArrayBitVector borders = LongArrayBitVector.getInstance( skipsLength );
		int s = skips.size();
		int x;
		for( IntIterator i = skips.iterator(); s-- != 0; ) {
			x = i.nextInt();
			this.skips.append( x, length( x ) );
			borders.append( 1, length( x ) );
		}
		
		borders.append( 1, 1 ); // Sentinel
		if ( this.skips.trim() ) throw new AssertionError();
		if ( borders.trim() ) throw new AssertionError();*/
		
		this.skips = new TwoSizesLongBigList( skips );
		LOGGER.info( "Bits per skip: " + (double)this.skips.numBits() / ( numNodes - 1 ) );
		
		if ( DEBUG ) {
			System.err.println( skips );
			System.err.println( this.skips );
			//System.err.println( borders );
		}
		
		//LOGGER.info( "Bits for skips: " +(  this.skips.length() + skipLocator.numBits() ))
		LOGGER.info( "Bits: " + numBits() + " bits/string: " + (double)numBits() / size );
	}
	
	public long numBits() {
		return rank9.numBits() + select.numBits() + trie.length() + this.skips.numBits() + transform.numBits();
	}
	
	public int size() {
		return size;
	}
	
	private int setWeight( final Node n ) {
		if ( n == null ) return 0;
		return n.weight = 1 + setWeight( n.left ) + setWeight( n.right );
	}
	
	private long sumDepth( final Node n, int depth ) {
		if ( n == null ) return depth;
		return sumDepth( n.left, depth + 1 ) + sumDepth( n.right, depth + 1 );
	}
	
	private int cutLarge( final Node n, final int bucketSize ) {
		if ( n == null ) return 0;
		if ( n.weight <= bucketSize ) {
			n.left = n.right = null;
			return 1;
		}
		else return 1 + cutLarge( n.left, bucketSize ) + cutLarge( n.right, bucketSize );
	}
	
	private void recToString( final Node n, final StringBuilder printPrefix, final StringBuilder result, final StringBuilder path, final int level ) {
		if ( n == null ) return;
		
		result.append( printPrefix ).append( '(' ).append( level ).append( ')' );
		
		if ( n.skip >= 0 ) result.append( " skip: " ).append( n.skip );

		result.append( '\n' );
		
		path.append( '0' );
		recToString( n.left, printPrefix.append( '\t' ).append( "0 => " ), result, path, level + 1 );
		path.setCharAt( path.length() - 1, '1' ); 
		recToString( n.right, printPrefix.replace( printPrefix.length() - 5, printPrefix.length(), "1 => "), result, path, level + 1 );
		path.delete( path.length() - 1, path.length() ); 
		printPrefix.delete( printPrefix.length() - 6, printPrefix.length() );
	}
	
	private void readObject( final ObjectInputStream s ) throws IOException, ClassNotFoundException {
		s.defaultReadObject();
		trie = rank9.bitVector();
	}

	
	public static void main( String arg[] ) throws SecurityException, JSAPException, NoSuchMethodException {
		final SimpleJSAP jsap = new SimpleJSAP( HollowTrie.class.getName(), "Builds a hollow trie reading a newline-separated list of terms.",
				new Parameter[] {
					new FlaggedOption( "bufferSize", JSAP.INTSIZE_PARSER, "64Ki", JSAP.NOT_REQUIRED, 'b',  "buffer-size", "The size of the I/O buffer used to read terms." ),
					new FlaggedOption( "encoding", ForNameStringParser.getParser( Charset.class ), "UTF-8", JSAP.NOT_REQUIRED, 'e', "encoding", "The term file encoding." ),
					new Switch( "zipped", 'z', "zipped", "The term list is compressed in gzip format." ),
					//new Switch( "huTucker", 'h', "hu-tucker", "Use Hu-Tucker coding to increase entropy (only available for offline construction)." ),
					//new UnflaggedOption( "trie", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, JSAP.NOT_GREEDY, "The filename for the serialised hollow trie." )
		});
		
		JSAPResult jsapResult = jsap.parse( arg );
		if ( jsap.messagePrinted() ) return;
		
		final int bufferSize = jsapResult.getInt( "bufferSize" );
		final Charset encoding = (Charset)jsapResult.getObject( "encoding" );
		//final boolean huTucker = jsapResult.getBoolean( "huTucker" );
		
		ObjectList<BigInteger> input = new ObjectArrayList<BigInteger>();
		LineIterator line = new LineIterator( new FastBufferedReader( new InputStreamReader( System.in, encoding ), bufferSize ) );
		final int width = Integer.parseInt( line.next().toString() );
		
		BigInteger modulo = BigInteger.ONE.shiftLeft( width );
		
		while( line.hasNext() ) {
			BigInteger bi = new BigInteger( line.next().toString() );
			input.add( bi );
		}

		int numShifts = 1000;
		
		BigInteger[] shift = new BigInteger[ numShifts ];
		Random r = new Random();
		for( int i = 0; i < numShifts; i++ ) shift[ i ] = new BigInteger( width, r );
		
		for( int i = 0; i < numShifts; i++ ) {
			final ObjectList<BigInteger> shiftedList = new ObjectArrayList<BigInteger>();
			for( BigInteger b: input ) shiftedList.add( b.add( shift[ i ] ).remainder( modulo ) );
			Collections.sort( shiftedList );
			
			StupidHollowTrie<BitVector> t = new StupidHollowTrie<BitVector>( new AbstractObjectIterator<BitVector>() {
				final Iterator<BigInteger> iterator = shiftedList.iterator();

				public boolean hasNext() {
					return iterator.hasNext();
				}

				public BitVector next() {
					LongArrayBitVector bv = LongArrayBitVector.getInstance( width );
					BigInteger bi = iterator.next();
					for( int j = width; j-- != 0; ) bv.add( bi.testBit( j ) );
					return bv;
				}
			}, TransformationStrategies.identity(), 8 );
			
			System.out.println( t.avgDepth );
		}
	}
}