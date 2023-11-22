/*-
 * #%L
 * Fiji viewer for MoBIE projects
 * %%
 * Copyright (C) 2018 - 2023 EMBL
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.embl.mobie.lib.hcs;

import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.measure.Calibration;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import mpicbg.spim.data.generic.base.Entity;
import org.embl.mobie.io.ImageDataFormat;
import org.embl.mobie.io.SpimDataOpener;
import org.embl.mobie.io.toml.TPosition;
import org.embl.mobie.io.util.IOHelper;
import org.embl.mobie.lib.MoBIEHelper;
import org.embl.mobie.lib.color.ColorHelper;

import ch.epfl.biop.bdv.img.bioformats.entity.SeriesIndex;
import org.embl.mobie.lib.hcs.omezarr.OMEZarrHCSHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Plate
{
	private final String hcsDirectory;
	private HCSPattern hcsPattern;
	private HashMap< Channel, Map< Well, Set< Site > > > channelWellSites;
	private double[] siteRealDimensions;
	private int sitesPerWell;
	private int[] siteDimensions;
	private VoxelDimensions voxelDimensions;
	private Set< TPosition > tPositions;
	private int wellsPerPlate;
	private ImageDataFormat imageDataFormat;
	private OperettaMetadata operettaMetadata;
	private AbstractSpimData< ? > spimDataPlate;


	public Plate( String hcsDirectory ) throws IOException
	{
		this.hcsDirectory = hcsDirectory;

		// FIXME: fetch operetta paths from XML?!
		// FIXME: fetch OME-Zarr paths entry point JSON?!
		List< String > imageSitePaths;

		if ( hcsDirectory.endsWith( ".zarr" ) )
		{
			hcsPattern = HCSPattern.OMEZarr;

			if ( IOHelper.getType( hcsDirectory ).equals( IOHelper.ResourceType.S3 ) )
				imageDataFormat = ImageDataFormat.OmeZarrS3;
			else
				imageDataFormat = ImageDataFormat.OmeZarr;

			imageSitePaths = OMEZarrHCSHelper.sitePathsFromMetadata( hcsDirectory );

			// determine the number of channels
			try
			{
				final String firstImagePath = imageSitePaths.get( 0 );
				AbstractSpimData< ? > spimData = new SpimDataOpener().open( firstImagePath, imageDataFormat );
				final int numChannels = spimData.getSequenceDescription().getViewSetupsOrdered().size();
				final List< String > channels = IntStream.range( 0, numChannels )
						.mapToObj( i -> ( ( Integer ) i ).toString() )
						.collect( Collectors.toList() );
				hcsPattern.setChannels( channels );
			}
			catch ( SpimDataException e )
			{
				throw new RuntimeException( e );
			}
		}
		else
		{
			imageSitePaths = Files.walk( Paths.get( hcsDirectory ), 3 )
					.map( p -> p.toString() )
					.collect( Collectors.toList() );
			hcsPattern = determineHCSPattern( hcsDirectory, imageSitePaths );
			imageSitePaths = imageSitePaths.stream()
					.filter( path -> hcsPattern.setMatcher( path ) ) // skip files like .DS_Store a.s.o.
					.collect( Collectors.toList() );

			if ( hcsPattern == HCSPattern.Operetta )
			{
				// only keep paths that are also in the XML
				//final File xml = new File( hcsDirectory, "Index.idx.xml" );
				final File xml = new File( hcsDirectory, "Index.xml" );
				operettaMetadata = new OperettaMetadata( xml );
				imageSitePaths = imageSitePaths.stream()
						.filter( path -> operettaMetadata.contains( path ) ) // skip files like .DS_Store a.s.o.
						.collect( Collectors.toList() );
			}
		}

		buildPlateMap( imageSitePaths );
	}

	private void buildPlateMap( List< String > paths )
	{
		channelWellSites = new HashMap<>();
		tPositions = new HashSet<>();

		IJ.log("Parsing " + paths.size() + " images...");

		for ( String path : paths )
		{
			hcsPattern.setMatcher( path );

			// some formats contain multiple channels in one file
			List< String > channelIDs = hcsPattern.getChannels();
			for ( String channelName : channelIDs )
			{
				Channel channel = getChannel( channelWellSites, channelName );

				if ( channel == null )
				{
					// configure channel properties
					//
					channel = new Channel( channelName );
					channelWellSites.put( channel, new HashMap<>() );

					// FIXME Replace with MoBIEHelper.getMetadataFromImageFile
					ImagePlus singleChannelImagePlus = operettaMetadata == null ? openImagePlus( path, channelName ) : null;

					// set channel metadata
					//
					if ( operettaMetadata != null )
					{
						final String color = operettaMetadata.getColor( path );
						channel.setColor( color );

						// TODO: There does not always seem to be enough metadata for the
						//   contrast limits, thus opening one image may be worth it
						//   then convert to image plus and run once auto contrast on it
						final double[] contrastLimits = operettaMetadata.getContrastLimits( path );
						channel.setContrastLimits( contrastLimits );
					}
					else // from image file
					{
						final String color = ColorHelper.getString( singleChannelImagePlus.getLuts()[ 0 ] );
						channel.setColor( color );

						final double[] contrastLimits = new double[]{
								singleChannelImagePlus.getDisplayRangeMin(),
								singleChannelImagePlus.getDisplayRangeMax()
						};
						channel.setContrastLimits( contrastLimits );
					}

					// determine spatial metadata (for all channels the same)
					//
					if ( voxelDimensions == null )
					{
						if ( operettaMetadata != null )
						{
							voxelDimensions = operettaMetadata.getVoxelDimensions( path );
							siteDimensions = operettaMetadata.getSiteDimensions( path );
						}
						else // from image file
						{
							final Calibration calibration = singleChannelImagePlus.getCalibration();
							voxelDimensions = new FinalVoxelDimensions( calibration.getUnit(), calibration.pixelWidth, calibration.pixelHeight, calibration.pixelDepth );
							siteDimensions = new int[]{ singleChannelImagePlus.getWidth(), singleChannelImagePlus.getHeight() };
						}

						// compute derived spatial metadata
						//
						siteRealDimensions = new double[]{
								siteDimensions[ 0 ] * voxelDimensions.dimension( 0 ),
								siteDimensions[ 1 ] * voxelDimensions.dimension( 1 ) };

						siteRealDimensions = new double[]{
								siteDimensions[ 0 ] * voxelDimensions.dimension( 0 ),
								siteDimensions[ 1 ] * voxelDimensions.dimension( 1 ) };
					}
				}
				// well
				//
				String wellGroup = hcsPattern.getWellGroup();
				Well well = getWell( channelWellSites, channel, wellGroup );
				if ( well == null )
				{
					well = new Well( wellGroup );
					channelWellSites.get( channel ).put( well, new HashSet<>() );
					final int numWells = channelWellSites.get( channel ).size();
					if ( numWells > wellsPerPlate )
						wellsPerPlate = numWells;
				}

				// site
				//
				final String siteGroup = hcsPattern.getSiteGroup();
				Site site = getSite( channelWellSites, channel, well, siteGroup );
				if ( site == null )
				{
					if ( imageDataFormat.equals( ImageDataFormat.SpimData ) )
					{
						final int imageIndex = operettaMetadata.getImageIndex( path );
						final BasicViewSetup viewSetup = spimDataPlate.getSequenceDescription().getViewSetupsOrdered().get( imageIndex );
						IJ.log( "" );
						final Map< String, Entity > attributes = viewSetup.getAttributes();
						IJ.log( "Image index:" + imageIndex );
						IJ.log( "Series index: " + ( ( SeriesIndex ) attributes.get( "seriesindex" ) ).getId() );
						IJ.log( "Setup name: " + viewSetup.getName() );
						IJ.log( "File name: " + new File( path ).getName() );
						site = new Site( siteGroup, imageDataFormat, spimDataPlate, imageIndex );
					}
					else
					{
						site = new Site( siteGroup, imageDataFormat );
					}
					site.setDimensions( siteDimensions );
					site.setVoxelDimensions( voxelDimensions );
					channelWellSites.get( channel ).get( well ).add( site );
					final int numSites = channelWellSites.get( channel ).get( well ).size();
					if ( numSites > sitesPerWell )
						sitesPerWell = numSites; // needed to compute the site position within a well
				}

				if ( hcsPattern.equals( hcsPattern.OMEZarr ) )
				{
					site.absolutePath = path;
					site.channel = Integer.parseInt( channelName );
				}
				else
				{
					final String t = hcsPattern.getT();
					final String z = hcsPattern.getZ();
					site.addPath( t, z, path );
					tPositions.add( new TPosition( t ) );
				}
			}
		}

		IJ.log( "Initialised HCS plate: " + getName() );
		IJ.log( "Images: " + paths.size() );
		IJ.log( "Channels: " + channelWellSites.keySet().size() );
		IJ.log( "Wells: " + wellsPerPlate );
		IJ.log( "Sites per well: " + sitesPerWell );
	}

	private ImagePlus openImagePlus( String path, String channelName )
	{
		if ( this.imageDataFormat.equals( ImageDataFormat.Tiff ) )
		{
			final File file = new File( path );
			return ( new Opener() ).openTiff( file.getParent(), file.getName() );
		}
		else if ( this.imageDataFormat.equals( ImageDataFormat.OmeZarr )
				|| this.imageDataFormat.equals( ImageDataFormat.OmeZarrS3 ) )
		{
			final int setupID = Integer.parseInt( channelName );
			return MoBIEHelper.openAsImagePlus( path, setupID, imageDataFormat );
		}
		else
		{
			return IJ.openImage( path );
		}
	}

	private Channel getChannel( HashMap< Channel, Map< Well, Set< Site > > > channelWellSites, String channelName )
	{
		try
		{
			return channelWellSites.keySet().stream().filter( c -> c.getName().equals( channelName ) ).findFirst().get();
		}
		catch ( NoSuchElementException e )
		{
			return null;
		}

	}

	private Well getWell( HashMap< Channel, Map< Well, Set< Site > > > channelWellSites, Channel channel, String wellName )
	{
		try
		{
			return channelWellSites.get( channel ).keySet().stream().filter( w -> w.getName().equals( wellName ) ).findFirst().get();
		}
		catch ( NoSuchElementException e )
		{
			return null;
		}
	}

	private Site getSite( HashMap< Channel, Map< Well, Set< Site > > > channelWellSites, Channel channel, Well well, String siteName )
	{
		try
		{
			return channelWellSites.get( channel ).get( well ).stream().filter( s -> s.getId().equals( siteName ) ).findFirst().get();
		}
		catch ( NoSuchElementException e )
		{
			return null;
		}
	}

	private HCSPattern determineHCSPattern( String hcsDirectory, List< String > paths )
	{
		for ( String path : paths )
		{
			final HCSPattern hcsPattern = HCSPattern.fromPath( path );
			if ( hcsPattern != null )
			{
				IJ.log( "HCS Pattern: " + hcsPattern );
				return hcsPattern;
			}
		}

		throw new RuntimeException( "Could not determine HCSPattern for " + hcsDirectory );
	}

	public Set< Channel > getChannels()
	{
		return channelWellSites.keySet();
	}

	public Set< Well > getWells( Channel channel )
	{
		return channelWellSites.get( channel ).keySet();
	}

	public Set< Site > getSites( Channel channel, Well well )
	{
		return channelWellSites.get( channel ).get( well );
	}

	public int[] getGridPosition( Site site )
	{
		switch ( hcsPattern )
		{
			case Operetta:
				return getOperettaGridPosition( site );
			case MolecularDevices:
				return getMolecularDevicesGridPosition( site );
			default:
				return getDefaultGridPosition( site );
		}
	}

	public int[] getMolecularDevicesGridPosition( Site site )
	{
		if ( sitesPerWell == 1 )
			return new int[]{ 0, 0 };

		int siteIndex = Integer.parseInt( site.getId() ) - 1;
		int numColumns = (int) Math.ceil( Math.sqrt( sitesPerWell ) );

		int[] gridPosition = new int[ 2 ];
		gridPosition[ 0 ] = siteIndex % numColumns; // column
		gridPosition[ 1 ] = siteIndex / numColumns; // row

		return gridPosition;
	}

	private int[] getOperettaGridPosition( Site site )
	{
		final String path = site.getPaths().values().iterator().next().values().iterator().next();
		final double[] realPosition = operettaMetadata.getRealPosition( path );

		final int[] position = new int[ 2 ];
		for ( int d = 0; d < 2; d++ )
		{
			position[ d ] = (int) ( realPosition[ d ] / siteRealDimensions [ d ] );
		}

		return position;
	}

	public int[] getDefaultGridPosition( Site site )
	{
		if ( sitesPerWell == 1 )
			return new int[]{ 0, 0 };

		int siteIndex = Integer.parseInt( site.getId() ) - 1;
		int numSiteColumns = (int) Math.sqrt( sitesPerWell );

		int[] gridPosition = new int[ 2 ];
		gridPosition[ 0 ] = siteIndex % numSiteColumns; // column
		gridPosition[ 1 ] = siteIndex / numSiteColumns; // row

		return gridPosition;
	}

	public int[] getWellGridPosition( Well well )
	{
		return hcsPattern.decodeWellGridPosition( well.getName() );
	}

	public boolean is2D()
	{
		return true; // for now...
	}

	public String getName()
	{
		return new File( hcsDirectory ).getName();
	}

	public HCSPattern getHcsPattern()
	{
		return hcsPattern;
	}

	public double[] getSiteRealDimensions()
	{
		return siteRealDimensions;
	}

	public int[] getSiteDimensions()
	{
		return siteDimensions;
	}

	public Set< TPosition > getTPositions()
	{
		return tPositions;
	}

	public int getSitesPerWell()
	{
		return sitesPerWell;
	}
}
