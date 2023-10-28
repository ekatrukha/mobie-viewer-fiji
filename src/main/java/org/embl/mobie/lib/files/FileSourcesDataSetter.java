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
package org.embl.mobie.lib.files;

import ij.IJ;
import org.embl.mobie.io.ImageDataFormat;
import org.embl.mobie.lib.DataStore;
import org.embl.mobie.lib.annotation.AnnotatedRegion;
import org.embl.mobie.lib.annotation.AnnotatedSegment;
import org.embl.mobie.lib.io.StorageLocation;
import org.embl.mobie.lib.serialize.Dataset;
import org.embl.mobie.lib.serialize.ImageDataSource;
import org.embl.mobie.lib.serialize.RegionTableSource;
import org.embl.mobie.lib.serialize.SegmentationDataSource;
import org.embl.mobie.lib.serialize.View;
import org.embl.mobie.lib.serialize.display.Display;
import org.embl.mobie.lib.serialize.display.ImageDisplay;
import org.embl.mobie.lib.serialize.display.RegionDisplay;
import org.embl.mobie.lib.serialize.display.SegmentationDisplay;
import org.embl.mobie.lib.serialize.transformation.*;
import org.embl.mobie.lib.source.Metadata;
import org.embl.mobie.lib.table.ColumnNames;
import org.embl.mobie.lib.table.TableDataFormat;
import org.embl.mobie.lib.table.TableSource;
import org.embl.mobie.lib.transform.GridType;
import org.embl.mobie.lib.transform.viewer.ImageZoomViewerTransform;
import tech.tablesaw.api.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

public class FileSourcesDataSetter
{
	private final List< ImageFileSources > images;
	private final List< LabelFileSources > labels;
	private final Table regionTable;

	public FileSourcesDataSetter( List< ImageFileSources > images, List< LabelFileSources > labels, Table regionTable )
	{
		this.images = images;
		this.labels = labels;
		this.regionTable = regionTable;
	}

	public void addDataAndDisplaysAndViews( Dataset dataset )
	{
		final ArrayList< ImageFileSources > allSources = new ArrayList<>();
		allSources.addAll( images );
		allSources.addAll( labels );

		// create and add data sources to the dataset
		for ( ImageFileSources sources : allSources )
		{
			if ( sources.getMetadata().numZSlices > 1 )
			{
				dataset.is2D( false );
			}

			List< String > imageNames = sources.getSources();
			ImageDataFormat imageDataFormat = ImageDataFormat.fromPath( sources.getPath( imageNames.get( 0 ) ) );
			IJ.log("Format/opener for " + sources.name + ": " + imageDataFormat );
			for ( String imageName : imageNames )
			{
				final String path = sources.getPath( imageName );
				final StorageLocation storageLocation = new StorageLocation();
				storageLocation.absolutePath = path;
				storageLocation.setChannel( sources.getChannelIndex() );
				if ( sources instanceof LabelFileSources )
				{
					final TableSource tableSource = ( ( LabelFileSources ) sources ).getLabelTable( imageName );
					SegmentationDataSource segmentationDataSource = SegmentationDataSource.create( imageName, imageDataFormat, storageLocation, tableSource );
					segmentationDataSource.preInit( false );
					dataset.addDataSource( segmentationDataSource );
				}
				else
				{
					final ImageDataSource imageDataSource = new ImageDataSource( imageName, imageDataFormat, storageLocation );
					imageDataSource.preInit( false );
					dataset.addDataSource( imageDataSource );
				}
			}
		}


		RegionDisplay< AnnotatedRegion > regionDisplay = null;
		final List< Display< ? > > displays = new ArrayList<>();
		final List< Transformation > transformations = new ArrayList<>();

		for ( ImageFileSources sources : allSources )
		{
			List< String > sourceNames = sources.getSources();
			final int numRegions = sourceNames.size();

			if ( regionDisplay == null )
			{
				// init RegionDisplay
				regionDisplay = new RegionDisplay<>( regionTable.name() );

				// add table
				final StorageLocation storageLocation = new StorageLocation();
				storageLocation.data = regionTable;
				final RegionTableSource regionTableSource = new RegionTableSource( regionTable.name() );
				regionTableSource.addTable( TableDataFormat.Table, storageLocation );
				DataStore.putRawData( regionTableSource );

				// init display
				regionDisplay.sources = new LinkedHashMap<>();
				regionDisplay.tableSource = regionTableSource.getName();
				regionDisplay.showAsBoundaries( true );
				regionDisplay.setBoundaryThickness( 0.015 );
				regionDisplay.boundaryThicknessIsRelative( true );
				regionDisplay.setRelativeDilation( 0.025 );
				regionDisplay.setOpacity( 1.0 );

				Integer numTimePoints = sources.getMetadata().numTimePoints;
				if ( numTimePoints == null )
					numTimePoints = 1000; // TODO
				for ( int t = 0; t < numTimePoints; t++ )
					regionDisplay.timepoints().add( t );

				for ( int regionIndex = 0; regionIndex < numRegions; regionIndex++ )
				{
					String regionName = regionTable.getString( regionIndex, ColumnNames.REGION_ID );
					regionDisplay.sources.put( regionName, new ArrayList<>() );
				}

				displays.add( regionDisplay );
			}

			// add the images of this source to the respective region
			for ( int regionIndex = 0; regionIndex < numRegions; regionIndex++ )
			{
				String regionName = regionTable.getString( regionIndex, ColumnNames.REGION_ID );
				regionDisplay.sources.get( regionName ).add( sourceNames.get( regionIndex ) );
				System.out.println("Region: " +  regionName + "; source: " + sourceNames.get( regionIndex ) );
			}

			// create grid transformations
			//
			if ( sources.getGridType().equals( GridType.Stitched ) )
			{
				MergedGridTransformation grid = new MergedGridTransformation( sources.getName() );
				grid.sources = sourceNames;
				grid.metadataSource = sources.getMetadataSource();
				grid.lazyLoadTables = false; // TODO https://github.com/mobie/mobie-viewer-fiji/issues/1035

				if ( sources instanceof LabelFileSources )
				{
					// SegmentationDisplay
					final SegmentationDisplay< AnnotatedSegment > segmentationDisplay = new SegmentationDisplay<>( grid.getName(), Collections.singletonList( grid.getName() ) );
					final int numLabelTables = ( ( LabelFileSources ) sources ).getNumLabelTables();
					segmentationDisplay.setShowTable( numLabelTables > 0 );
					displays.add( segmentationDisplay );
				}
				else
				{
					// ImageDisplay
					final Metadata metadata = sources.getMetadata();
					displays.add( new ImageDisplay<>( grid.getName(), Collections.singletonList( grid.getName() ), metadata.color, metadata.contrastLimits ) );
				}

				transformations.add( grid );
			}
			else if ( sources.getGridType().equals( GridType.Transformed ) )
			{
				// Add the individual images to the displays
				//
				if ( sources instanceof LabelFileSources )
				{
					// SegmentationDisplay
					final SegmentationDisplay< AnnotatedSegment > segmentationDisplay = new SegmentationDisplay<>( sources.getName(), sourceNames );
					final int numLabelTables = ( ( LabelFileSources ) sources ).getNumLabelTables();
					segmentationDisplay.setShowTable( numLabelTables > 0 );
					displays.add( segmentationDisplay );
				}
				else
				{
					// ImageDisplay
					final Metadata metadata = sources.getMetadata();
					// TODO: one could set only the first one to be visible, would need a change of the constructor
					displays.add( new ImageDisplay<>( sources.getName(), sourceNames, metadata.color, metadata.contrastLimits ) );
				}

				// Add image transformations (e.g., derived from columns in the AutoMicTools table)
				for ( String sourceName : sourceNames )
				{
					if ( sources.getTransform( sourceName ) != null )
					{
						AffineTransformation affineTransformation = new AffineTransformation<>( sourceName, sources.getTransform( sourceName ), Collections.singletonList( sourceName ) );
						transformations.add( affineTransformation );
					}
				}

				GridTransformation grid = new GridTransformation( sourceNames );
				transformations.add( grid );
			}
			else
			{
				throw new RuntimeException( "Grid type not supported: " + sources.getGridType() );
			}
		}

		// construct and add the view
		//
		// FIXME: Maybe the viewerTransform could be something else?
		final ImageZoomViewerTransform viewerTransform = new ImageZoomViewerTransform( transformations.get( 0 ).getSources().get( 0 ), 0 );
		final View gridView = new View( "all", "data", displays, transformations, viewerTransform, false );
		//gridView.overlayNames( true ); // FIXME: Timepoint bug!
		dataset.views().put( gridView.getName(), gridView );
	}
}
