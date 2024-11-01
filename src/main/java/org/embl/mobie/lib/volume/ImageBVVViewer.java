package org.embl.mobie.lib.volume;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.embl.mobie.lib.serialize.display.VisibilityListener;

import bdv.viewer.SourceAndConverter;
import bvv.vistools.Bvv;
import bvv.vistools.BvvFunctions;
import bvv.vistools.BvvHandleFrame;
import bvv.vistools.BvvStackSource;
import sc.fiji.bdvpg.services.SourceAndConverterServices;


public class ImageBVVViewer
{
	public static final String BVV_VIEWER = "BigVolumeViewer: ";
	private final List< ? extends SourceAndConverter< ? > > sourceAndConverters;
	private ConcurrentHashMap< SourceAndConverter, BvvStackSource > sacToBvvSource;

	private List< VisibilityListener > listeners = new ArrayList<>(  );
	private boolean showImages;
	public Bvv bvv = null;
	public BvvHandleFrame handle = null;
	
	//BVV rendering parameters, can be changed/adjusted somewhere else
	double dCam = 2000.;
	
	double dClipNear = 1000.;
	double dClipFar = 15000.;			
	int renderWidth = 800;
	int renderHeight = 600;
	int numDitherSamples = 3; 
	int cacheBlockSize = 32;
	int  maxCacheSizeInMB = 500;
	int ditherWidth = 3;

	
	public ImageBVVViewer(
			final List< ? extends SourceAndConverter< ? > > sourceAndConverters)
	{
		this.sourceAndConverters = sourceAndConverters;
		sacToBvvSource = new ConcurrentHashMap<>();
	}
	
	/// is it really needed for now?
	/// seems related to meshes rendering
//	public void updateView()
//	{
//		if ( bvv == null ) return;
//
//		for ( SourceAndConverter< ? > sac : sourceAndConverters )
//		{
//			if ( sacToBvvSource.containsKey( sac ) )
//			{
//				BvvStackSource<?> bvvSource = sacToBvvSource.get( sac );
//				bvvSource.removeFromBdv();
//				sacToBvvSource.remove( sac );
//				addSourceToBVV(sac);
//			}
//		}
//	}

	public synchronized < T > void showImagesBVV( boolean show )
	{
		
		this.showImages = show;

		if ( showImages && bvv == null )
		{
			initBVV();
		}
		
		for ( SourceAndConverter< ? > sac : sourceAndConverters )
		{
			if ( sacToBvvSource.containsKey( sac ) )
			{
				sacToBvvSource.get( sac ).setActive( show );
			}
			else
			{
				if ( show )
				{
					addSourceToBVV(sac);
				}
			}
		}		

	}
	
	void addSourceToBVV(SourceAndConverter< ? > sac)
	{
		//assume it is always one source
		BvvStackSource< ? >  bvvSource = BvvFunctions.show(BVVSourceToSpimDataWrapper.spimDataSourceWrap(sac.getSpimSource()), Bvv.options().addTo( bvv )).get( 0 );		
		
		
		final double displayRangeMin = SourceAndConverterServices.getSourceAndConverterService().getConverterSetup( sac ).getDisplayRangeMin();
		final double displayRangeMax = SourceAndConverterServices.getSourceAndConverterService().getConverterSetup( sac ).getDisplayRangeMax();
		bvvSource.setDisplayRange( displayRangeMin, displayRangeMax );
		
		handle.getBigVolumeViewer().getViewerFrame().setTitle( BVV_VIEWER + sac.getSpimSource().getName());		
		sacToBvvSource.put( sac, bvvSource );
		//sacToContent.put( sac, content ); ???
		
	}
	
	void initBVV()
	{
		bvv = BvvFunctions.show( Bvv.options().frameTitle( "BVV_VIEWER" ).
				dCam(dCam).
				dClipNear(dClipNear).
				dClipFar(dClipFar).				
				renderWidth(renderWidth).
				renderHeight(renderHeight).
				numDitherSamples(numDitherSamples ).
				cacheBlockSize(cacheBlockSize ).
				maxCacheSizeInMB( maxCacheSizeInMB ).
				ditherWidth(ditherWidth)
				);
		handle = (BvvHandleFrame)bvv.getBvvHandle();
		handle.getBigVolumeViewer().getViewerFrame().addWindowListener(  
				new WindowAdapter()
				{
					@Override
					public void windowClosing( WindowEvent ev )
					{
						bvv = null;
						handle = null;
						sacToBvvSource.clear();
						showImages = false;
						for ( VisibilityListener listener : listeners )
						{
							listener.visibility( false );
						}
					}
				});
	}
	
	public void close()
	{
		if(handle!=null)
		{
			bvv = null;
			sacToBvvSource.clear();
			// not really sure how to close it without Painter thread exception,
			// but in reality it can just be ignored
//			handle.getViewerPanel().stop();
//			try
//			{
//				Thread.sleep( 100 );
//			}
//			catch ( InterruptedException exc )
//			{
//				exc.printStackTrace();
//			}
//			handle.getBigVolumeViewer().getViewerFrame().dispose();
		}
	}

	public Collection< VisibilityListener > getListeners()
	{
		return listeners;
	}
	
	public boolean getShowImages() { return showImages; }	
}
