package org.janelia.saalfeldlab.mirrormicroscope;

import java.util.Arrays;
import java.util.HashMap;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import ij.IJ;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.realtransform.distortion.SphericalCurvatureZDistortion;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.fluent.RandomAccessibleIntervalView;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
	name = "FieldDisplacements", 
	mixinStandardHelpOptions = true,
	version = "0.1", 
	description = "Optical field correction for mirror microscope.")
public class FieldDisplacements implements Runnable
{

	@Option( names = { "-s", "--setup-id" }, description = "Setup ID number", required = true )
	private int setupId;

	@Option(names = {"-o", "--output-base"}, description = "Base output file. Will append <-(setup-id).tif>", required = false)
	private String outputBase;

	@Option( names = { "-i", "--inverse" }, fallbackValue = "true", arity = "0..1", description = "Flag to invert distortion transformation.", required = false )
	private boolean inverse = false;

//	@Option( names = { "-v", "--view" }, fallbackValue = "true", arity = "0..1", description = "Flag to view the transformed result.", required = false )
//	private boolean view = false;
//
//	@Option( names = { "-j", "--num-jobs" }, description = "Number of threads", required = false )
//	private int nThreads=1;

//	private N5Reader n5r;
//	private N5Writer n5w;
//
//	private DatasetAttributes inputAttributes;
//	private String inputDatasetPath;

	/*
	 * Optical system parameters
	 */
	private final double m0 = 0.02891;		// nominal magnification (image to object)
	private final double m1 = 8.318e-9;		// magnification distortion
	private final double R 	= 47.14 * 1000; // um

	/*
	 *  camera / imaging parameters
	 */
	// pixel dimension
	long nx;
	long ny;
	long nz;

	// pixel to physical resolution (after magnification)
	final double rxBase = 0.157; 	// um / pix 
	final double ryBase = 0.157;	// um / pix
	final double rzBase = 1.0;		// um / pix
	
	final int factor = 8;
	final double rx = factor * rxBase;
	final double ry = factor * ryBase;
	final double rz = factor * rzBase;

	// camera to image scaling factors
	double pixSpacingX;
	double pixSpacingY;
	double pixSpacingZ;

	double wx; 	// um
	double wy; 	// um
	double wz; 	// um

	public static void main( String[] args )
	{
		int exitCode = new CommandLine( new FieldDisplacements() ).execute( args );
	}

	@Override
	public void run()
	{
		process();
	}

	private < T extends NumericType< T > & NativeType< T > > void process()
	{
		System.out.println( "Processing application with:" );
		System.out.println( "  - Setup ID: " + setupId );
		System.out.println( "  - inverse: " + inverse );

		final Interval interval = Intervals.createMinMax(0, 0, 0, 4096 / factor, 2650 / factor, 4101 / factor);
		RandomAccessibleInterval< T > displacements = displacements(interval);

		if( outputBase != null ) {
			IJ.save(ImageJFunctions.wrap(displacements, "img-setup-"+setupId), outputBase + "-" + setupId + ".tif" );
		}
		else {
			BdvOptions opts = BdvOptions.options().numRenderingThreads(8);
			BdvFunctions.show( displacements, "z-displacements", opts);
		}
	}

	@SuppressWarnings("unchecked")
	public < T extends NumericType< T > & NativeType< T > > RandomAccessibleInterval< T > displacements( final Interval interval )
	{
        System.out.println( "  setupId     : " + setupId);
		System.out.println( "  tlation (um): " + Arrays.toString(CameraUtils.offset(setupId)));

		final InvertibleRealTransformSequence totalDistortion = totalDistortionCorrectionTransform( setupId );
		final double[] minMax = computeMinMaxOffsets( totalDistortion, interval );
		System.out.println( "  min offset: " + minMax[ 0 ] );
		System.out.println( "  max offset: " + minMax[ 1 ] );

		addNormalizationOffset(totalDistortion, minMax);
		final double[] minMaxAfter = computeMinMaxOffsets( totalDistortion, interval );
		System.out.println( "  min offset: " + minMaxAfter[ 0 ] );
		System.out.println( "  max offset: " + minMaxAfter[ 1 ] );

		final RealPoint y = new RealPoint(3);
		final FunctionRandomAccessible<DoubleType> ra = new FunctionRandomAccessible<>(
			3,
			(p,v) -> {
				totalDistortion.apply(p, y);
				v.set(y.getDoublePosition(2) - p.getDoublePosition(2));
			},
			DoubleType::new);

		final RandomAccessibleIntervalView<DoubleType> zDispFun = ra.view().interval(interval);
		final ArrayImg<DoubleType, DoubleArray> zDisplacements = ArrayImgs.doubles(interval.dimensionsAsLongArray());
		LoopBuilder.setImages(zDispFun, zDisplacements).forEachPixel( (src,dst) -> {
			dst.set(src.get());
		});
		return (RandomAccessibleInterval<T>)zDisplacements;
	}

	private void addNormalizationOffset( InvertibleRealTransformSequence totalDistortion, double[] minMax ) {
        final double min = minMax[0];
        final double max = minMax[1];
//        final double d = Math.abs( min ) > Math.abs( max ) ? max : min;
        final double d = (min + max) / 2.0;
        System.out.println("normalization offset: " + -d);
		totalDistortion.add( new Translation3D(new double[] {0, 0, -d}) );
	}

	/**
	 * Pixel to physical coordinates
	 *
	 * @return the camera transform
	 */
	public ScaleAndTranslation cameraToImage(int cameraId)
	{
		final double[] tlation = CameraUtils.offset(setupId);
		System.out.println("translation: " + Arrays.toString(tlation));
		return new ScaleAndTranslation(
				new double[] {rx, ry, rz}, tlation);
	}

	public ScaleAndTranslation imageToCamera(int cameraId)
	{
		return cameraToImage(cameraId).inverse();
	}

	public Scale3D imageToObject()
	{
		return new Scale3D( m0, m0, 1 );
	}

	public Scale3D objectToImage()
	{
		return imageToObject().inverse();
	}

	public InvertibleRealTransform distortionTransform() {

		if ( inverse )
			return new SphericalCurvatureZDistortion( 3, 2, R );
		else
			return new SphericalCurvatureZDistortion( 3, 2, R ).inverse();
	}
	
	/**
	 * Returns a transformation that corrects distortion.
	 * <p>.
	 * The distortion transformation is modeled in physical-image space, and
	 * the input and output are in camera space. This method then transforms
	 * the camera image to physical image space, applies the inverse of
	 * the distortion, and goes back to camera space.
	 *
	 * (cam distorted) -> (img distorted) -> (img undistorted) -> (cam undistorted)
	 *
	 * @return
	 */
	public InvertibleRealTransformSequence totalDistortionCorrectionTransform(int setupId) {
		return concatenate( 
				cameraToImage( setupId ),
				distortionTransform(),
				imageToCamera( setupId ));
	}

	public static InvertibleRealTransformSequence concatenate( InvertibleRealTransform... transforms) {

		InvertibleRealTransformSequence renderingTransform = new InvertibleRealTransformSequence();
		for( InvertibleRealTransform t : transforms )
			renderingTransform.add(t);

		return renderingTransform;
	}

	public static double[] computeMinMaxOffsets(InvertibleRealTransform distortion, Interval interval) {

	    // Ensure we have at least 3 dimensions
	    if (interval.numDimensions() < 3)
	        throw new IllegalArgumentException("Interval must have at least 3 dimensions for z-translation computation");

	    double minZTranslation = Double.POSITIVE_INFINITY;
	    double maxZTranslation = Double.NEGATIVE_INFINITY;

		// only need to check corners
		IntervalIterator iterator = new IntervalIterator( Intervals.createMinMax( 0, 0, 0, 1, 1, 1 ) );
		RealPoint cornerPoint = new RealPoint( interval.numDimensions() );
		RealPoint transformedPoint = new RealPoint( interval.numDimensions() );

		while ( iterator.hasNext() )
		{
			iterator.fwd();
			corner(interval, iterator, cornerPoint);

			// Apply distortion transformation
			distortion.apply( cornerPoint, transformedPoint );

			// Calculate z-translation (difference in z-coordinate)
			double zTranslation = transformedPoint.getDoublePosition( 2 ) - cornerPoint.getDoublePosition( 2 );

		    // Update min/max
	        minZTranslation = Math.min(minZTranslation, zTranslation);
	        maxZTranslation = Math.max(maxZTranslation, zTranslation);
		}

	    return new double[]{minZTranslation, maxZTranslation};
	}

	private static void corner(Interval interval, IntervalIterator cornerIterator, RealPoint p) {

		int nd = interval.numDimensions();
		for ( int i = 0; i < nd; i++ )
		{
			if ( cornerIterator.getLongPosition( i ) > 0 )
				p.setPosition( interval.max( i ), i );
			else
				p.setPosition( interval.min( i ), i );
		}
	}

}
