package org.embl.mobie.lib.volume;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;

import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

import org.embl.mobie.lib.playground.BdvPlaygroundHelper;

import bdv.AbstractViewerSetupImgLoader;
import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import bdv.cache.CacheControl;
import bdv.img.cache.CacheArrayLoader;
import bdv.img.cache.VolatileCachedCellImg;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.viewer.Source;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;

public class BVVSourceToViewerSetupImgLoader extends AbstractViewerSetupImgLoader< UnsignedShortType, VolatileUnsignedShortType > implements ViewerImgLoader
{
	final Source<?> src;
	final int numScales;
	final AffineTransform3D [] mipmapTransforms;
	final double [][] mipmapResolutions; 
	private VolatileGlobalCellCache cache;
	private long[][] imageDimensions;

	
	private final CacheArrayLoader<VolatileShortArray> loader;
	
	public BVVSourceToViewerSetupImgLoader(final Source<?> source_)
	{
		super( new UnsignedShortType(), new VolatileUnsignedShortType() );
		src = source_;
		numScales = src.getNumMipmapLevels();

		cache = new VolatileGlobalCellCache( numScales+1, 1 );

	
		mipmapTransforms = new AffineTransform3D[numScales];
		imageDimensions = new long[ numScales ][];
		
		for(int i=0;i<numScales;i++)
		{
			imageDimensions[ i ] = src.getSource( 0, i ).dimensionsAsLongArray();
		}
		mipmapResolutions = new double[ numScales ][];
		AffineTransform3D transformSource = new AffineTransform3D();
		src.getSourceTransform( 0, 0, transformSource );
		
		final double [] zeroScale = BdvPlaygroundHelper.getScale( transformSource);
		//double [] currMipMapRes = new double [3];
		for(int i=0;i<numScales;i++)
		{
			AffineTransform3D transform = new AffineTransform3D();
			src.getSourceTransform( 0, i, transform );			
			mipmapTransforms[i] = transform;
			
			double [] currScale = BdvPlaygroundHelper.getScale( transform );
			mipmapResolutions[i] = new double [3];
			for(int d=0;d<3;d++)
			{
				mipmapResolutions[i][d] = currScale[d]/zeroScale[d];
			}		
		}

		loader = new SourceArrayLoader(src);
	}

	@Override
	public int numMipmapLevels()
	{
		return numScales;
	}
	
	@Override
	public double[][] getMipmapResolutions()
	{
		return mipmapResolutions;
	}

	@Override
	public AffineTransform3D[] getMipmapTransforms()
	{
		return mipmapTransforms;
	}
	
	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage( int timepointId, int level, ImgLoaderHint... hints )
	{
		final RandomAccessibleInterval< ? > raiXYZ = src.getSource( timepointId, level );
				
		return convertRAIToShort(raiXYZ);

	}
	
	protected <T extends NativeType<T>> VolatileCachedCellImg<T, VolatileShortArray>
	prepareCachedImage(final int timepointId, final int level, final int setupId,
					   final LoadingStrategy loadingStrategy, final T typeCache)
	{
		final long[] dimensions = imageDimensions[ level ];
		final int priority = numScales - 1 - level;
		
		final CacheHints cacheHints = new CacheHints( loadingStrategy, priority, false );
		
		//final int[] cellDimensions = new int [] {(int)imageDimensions[level][0],(int)imageDimensions[level][1],1};
		final int[] cellDimensions = new int [] {32,32,32};
		
		final CellGrid grid = new CellGrid(dimensions, cellDimensions);
		return cache.createImg(grid, timepointId, setupId, level, cacheHints,
				loader, typeCache);
	}
	
	@Override
	public RandomAccessibleInterval< VolatileUnsignedShortType > getVolatileImage( int timepointId, int level, ImgLoaderHint... hints )
	{		
		return prepareCachedImage(timepointId, level, 0, LoadingStrategy.VOLATILE, volatileType);
	}
	

	@Override
	public CacheControl getCacheControl()
	{		
		return cache;
	}
	
	@Override
	public ViewerSetupImgLoader< ?, ? > getSetupImgLoader( int setupId )
	{
		return this;
	}
	
	public void setCache( final VolatileGlobalCellCache cache )
	{
		this.cache = cache;
	}
		
	
	static class SourceArrayLoader implements CacheArrayLoader<VolatileShortArray> {

		
		final Source<?> src;
		public SourceArrayLoader (final Source<?> source_)
		{
			src = source_;
		}
		
		@Override
		public int getBytesPerElement() {
			return 2;
		}

		@Override
		public VolatileShortArray loadArray( int timepoint, int setup, int level, int[] dimensions, long[] min ) throws InterruptedException
		{
			final RandomAccessibleInterval< ? > raiXYZ = src.getSource( timepoint, level );
			
			final short[] data = new short[dimensions[0]*dimensions[1]*dimensions[2]];
			
			final long[][] intRange = new long [2][3];
			for(int d=0;d<3;d++)
			{
				intRange[0][d]= min[d];
				intRange[1][d]= min[d]+dimensions[d]-1;

			}
			IterableInterval< UnsignedShortType > iterRAI = Views.flatIterable( convertRAIToShort(Views.interval( raiXYZ, new FinalInterval(intRange[0],intRange[1]))));
			int nCount = 0;
			
			Cursor< UnsignedShortType > cur = iterRAI.cursor();
			while (cur.hasNext())
			{
				cur.fwd();
				data[nCount] = cur.get().getShort();
				nCount++;
			}
			return new VolatileShortArray(data,true);
		}


	}
	@SuppressWarnings( "unchecked" )
	public static RandomAccessibleInterval< UnsignedShortType > convertRAIToShort(RandomAccessibleInterval< ? > raiXYZ)
	{
	
		Object typein = Util.getTypeFromInterval(raiXYZ);
		if ( typein instanceof UnsignedShortType )
		{
			return (RandomAccessibleInterval <UnsignedShortType >) raiXYZ;
		}
		else if ( typein instanceof UnsignedByteType )
		{
			return Converters.convert(
					raiXYZ,
					( i, o ) -> o.setInteger( ((UnsignedByteType) i).get() ),
					new UnsignedShortType( ) );
		}
		else
		{
			return null;
		}
	}


}
