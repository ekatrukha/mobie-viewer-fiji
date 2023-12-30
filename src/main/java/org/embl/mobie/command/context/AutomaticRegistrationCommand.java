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
package org.embl.mobie.command.context;

import bdv.tools.transformation.TransformedSource;
import bdv.util.BdvHandle;
import bdv.viewer.SourceAndConverter;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import net.imglib2.realtransform.AffineTransform3D;
import org.embl.mobie.command.CommandConstants;
import org.embl.mobie.lib.MoBIEHelper;
import org.embl.mobie.lib.bdv.ScreenShotMaker;
import org.embl.mobie.lib.registration.SIFT2DAligner;
import org.scijava.Initializable;
import org.scijava.command.DynamicCommand;
import org.scijava.command.Interactive;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import sc.fiji.bdvpg.bdv.BdvHandleHelper;
import sc.fiji.bdvpg.scijava.command.BdvPlaygroundActionCommand;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Plugin(type = BdvPlaygroundActionCommand.class, menuPath = CommandConstants.CONTEXT_MENU_ITEMS_ROOT + "Transform>Registration - Automatic")
public class AutomaticRegistrationCommand extends DynamicCommand implements BdvPlaygroundActionCommand, Interactive, Initializable
{

	public static final String TRANSLATION = "Translation";
	public static final String RIGID = "Rigid";
	public static final String SIMILARITY = "Similarity";
	public static final String AFFINE = "Affine";

	static { net.imagej.patcher.LegacyInjector.preinit(); }

	@Parameter
	public BdvHandle bdvHandle;

	@Parameter ( label = "Registration method", choices = {"TurboReg", "SIFT"})
	private String registrationMethod;

	@Parameter(label="Registration voxel size", persist = false, min = "0.0", style="format:#.00000")
	public Double voxelSize = 1D;

	@Parameter ( label = "Transformation", choices = { TRANSLATION, RIGID, SIMILARITY, AFFINE })
	private String transformationType;

	@Parameter ( label = "Image A (fixed)", choices = {""} )
	private String imageA;

	@Parameter ( label = "Image B (transformed)", choices = {""} )
	private String imageB;

	@Parameter ( label = "Compute Alignment", callback = "compute")
	private Button compute;

	@Parameter ( label = "Toggle Alignment", callback = "toggle")
	private Button toggle;

	private AffineTransform3D previousTransform;
	private AffineTransform3D newTransform;
	private TransformedSource< ? > transformedSource;
	private boolean isAligned;
	private List< SourceAndConverter< ? > > sourceAndConverters;
	private AffineTransform3D alignmentTransform3D;

	@Override
	public void initialize()
	{
		sourceAndConverters = MoBIEHelper.getVisibleSacs( bdvHandle );

		if ( sourceAndConverters.size() < 2 )
		{
			IJ.showMessage( "There must be at least two images visible." );
			return;
		}

		final List< String > imageNames = sourceAndConverters.stream()
				.map( sac -> sac.getSpimSource().getName() )
				.collect( Collectors.toList() );

		getInfo().getMutableInput( "imageA", String.class )
				.setChoices( imageNames );

		getInfo().getMutableInput( "imageB", String.class )
				.setChoices( imageNames );

		getInfo().getMutableInput("voxelSize", Double.class)
				.setValue( this, 2 * BdvHandleHelper.getViewerVoxelSpacing( bdvHandle ) );
	}

	@Override
	public void run()
	{
		//
	}

	private void compute()
	{
		SourceAndConverter< ? > sacA = sourceAndConverters.stream()
				.filter( sac -> sac.getSpimSource().getName().equals( imageA ) )
				.findFirst().get();

		SourceAndConverter< ? > sacB = sourceAndConverters.stream()
				.filter( sac -> sac.getSpimSource().getName().equals( imageB ) )
				.findFirst().get();

		if ( ! ( sacB.getSpimSource() instanceof TransformedSource ) )
		{
			IJ.log("Cannot apply transformations to image of type " + sacB.getSpimSource().getClass() );
			return;
		}

		ScreenShotMaker screenShotMaker = new ScreenShotMaker( bdvHandle, sacA.getSpimSource().getVoxelDimensions().unit() );
		screenShotMaker.run( Arrays.asList( sacA, sacB ), voxelSize );
		CompositeImage compositeImage = screenShotMaker.getCompositeImagePlus();
		AffineTransform3D canvasToGlobalTransform = screenShotMaker.getCanvasToGlobalTransform();

		ImageStack stack = compositeImage.getStack();
		ImagePlus impA = new ImagePlus( imageA + " (fixed)", stack.getProcessor( 1 ) );
		ImagePlus impB = new ImagePlus( imageB + " (moving)", stack.getProcessor( 2 ) );

		// Setting the display ranges is important
		// as those will be used by the SIFT for normalising the pixel values
		compositeImage.setPosition( 1 );
		impA.getProcessor().setMinAndMax( compositeImage.getDisplayRangeMin(), compositeImage.getDisplayRangeMax() );
		compositeImage.setPosition( 2 );
		impB.getProcessor().setMinAndMax( compositeImage.getDisplayRangeMin(), compositeImage.getDisplayRangeMax() );

		// the transformation the aligns the two images in 2D
		AffineTransform3D canvasTransformation = new AffineTransform3D();
		if ( registrationMethod.equals( "SIFT" ) )
		{
			SIFT2DAligner sift2DAligner = new SIFT2DAligner( bdvHandle, impA, impB, transformationType );
			if ( ! sift2DAligner.showUI() ) return;
			canvasTransformation = sift2DAligner.getSIFTAlignmentTransform();
		}
		else if ( registrationMethod.equals( "TurboReg" ) )
		{
			// TODO
		}

		// convert the transformation that aligns the images
		// within the screenshot canvas to the global 3D coordinate system
		AffineTransform3D globalAlignmentTransform = new AffineTransform3D();

		// global to target canvas...
		globalAlignmentTransform.preConcatenate( canvasToGlobalTransform.inverse() );

		// ...registration within canvas...
		globalAlignmentTransform.preConcatenate( canvasTransformation );

		// ...canvas back to global
		globalAlignmentTransform.preConcatenate( canvasToGlobalTransform );

		// apply transformation
		//
		transformedSource = ( TransformedSource< ? > ) sacB.getSpimSource();
		previousTransform = new AffineTransform3D();
		transformedSource.getFixedTransform( previousTransform );
		newTransform = previousTransform.copy();
		newTransform.preConcatenate( globalAlignmentTransform );
		transformedSource.setFixedTransform( newTransform );
		IJ.log( "Transforming " + transformedSource.getName() );
		IJ.log( "Previous Transform: " + previousTransform );
		IJ.log( "Additional SIFT Transform: " + globalAlignmentTransform );
		IJ.log( "Combined Transform: " + newTransform );

		isAligned = true;
		bdvHandle.getViewerPanel().requestRepaint();
	}


	private void toggle()
	{
		if ( transformedSource == null )
		{
			IJ.showMessage( "Please first [ Compute Alignment ]." );
			return;
		}

		if ( isAligned )
		{
			transformedSource.setFixedTransform( previousTransform );
		}
		else
		{
			transformedSource.setFixedTransform( newTransform );
		}

		bdvHandle.getViewerPanel().requestRepaint();
		isAligned = ! isAligned;
	}


}
