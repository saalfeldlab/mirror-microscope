package org.janelia.saalfeldlab.mirrormicroscope.demo;

import java.util.Arrays;
import java.util.HashMap;

import org.janelia.saalfeldlab.mirrormicroscope.CameraUtils;
import org.janelia.saalfeldlab.mirrormicroscope.CameraUtils.CAM_DIRECTION;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import ij.IJ;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.realtransform.distortion.SphericalCurvatureZDistortion;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

public class IllustrativeExampleTif {

	// pixel to physical resolution (after magnification)
	final double rx = 2.512; 	// um / pix 
	final double ry = 2.512;	// um / pix
	final double rz = 8.0;		// um / pix

	private final double R 	= 47.14 * 1000; // um

	HashMap<Integer, double[]> cameraTranslationsMicronUnits;

	public static void main(String[] args) {
		new IllustrativeExampleTif(true).run();
	}

	public IllustrativeExampleTif( boolean inverse) {
		cameraTranslationsMicronUnits = new HashMap<>();
		loadCameraTranslations();
		CameraUtils.camDirection = CAM_DIRECTION.Y;
	}

	public <T extends NumericType<T> & NativeType<T>> void run() {

		final String setupPattern = "/nrs/saalfeld/john/for/keller/danio_1_488/dataset-orig-tifs/4/setup%d.tif";

		Img<T> camImg01 = ImageJFunctions.wrap( IJ.openImage(String.format(setupPattern, 392)));
		Img<T> camImg10 = ImageJFunctions.wrap( IJ.openImage(String.format(setupPattern, 402)));

		BdvOptions opts = BdvOptions.options().numRenderingThreads(32);
		final BdvStackSource<?> bdv = BdvFunctions.show(camImg01, "original 392", opts);
		opts = opts.addTo(bdv);

		final boolean norm = false;

		final InvertibleRealTransformSequence fwdTform392 = genTransform(392, false, camImg01, norm);
		BdvFunctions.show(transform(camImg01, fwdTform392) , "fwd-transformed 392", opts);

		final InvertibleRealTransformSequence invTform392 = genTransform(392, true, camImg01, norm);
		BdvFunctions.show(transform(camImg01, invTform392) , "inv-transformed 392", opts);

		BdvFunctions.show(camImg10, "original 402", opts);
		final InvertibleRealTransformSequence fwdTform402 = genTransform(402, false, camImg10, norm);
		BdvFunctions.show(transform(camImg10, fwdTform402) , "fwd-transformed 402", opts);

		final InvertibleRealTransformSequence invTform402 = genTransform(402, true, camImg10, norm);
		BdvFunctions.show(transform(camImg10, invTform402) , "inv-transformed 402", opts);


//		compareFwdInv();

		System.out.println("run done");
	}
	
	private void compareFwdInv( ) {

//		RealPoint p = new RealPoint(3);
//		RealPoint q = new RealPoint(3);
		
//		System.out.println("");
//		fwdTform392.apply(p, q);
//		System.out.println(q);
//
//		invTform392.apply(p, q);
//		System.out.println(q);
//		System.out.println("");
		
	}

	public InvertibleRealTransformSequence genTransform(int setupId, boolean inverse, Interval itvl ) {
		return genTransform(setupId, inverse, itvl, false );
	}

	public InvertibleRealTransformSequence genTransform(int setupId, boolean inverse, Interval itvl, boolean normalize) {

		InvertibleRealTransformSequence tform = physicalDistortionCorrectionTransform(setupId, inverse);

//		final double[] minMax = computeMinMaxOffsets(tform, itvl);
//		final double[] minMax = computeOriginOffsets(tform, itvl);
		final double[] minMax = computeMiddleOffsets(tform, itvl);
		System.out.println("  min offset: " + minMax[0]);
		System.out.println("  max offset: " + minMax[1]);

		if (normalize) {
			addNormalizationOffset(tform, minMax);
//			final double[] minMaxAfter = computeMinMaxOffsets(tform, itvl);
//			final double[] minMaxAfter = computeOriginOffsets(tform, itvl);
			final double[] minMaxAfter = computeMiddleOffsets(tform, itvl);
			System.out.println("  min offset: " + minMaxAfter[0]);
			System.out.println("  max offset: " + minMaxAfter[1]);
		}
		tform.add(imageToCamera(setupId));

		return tform;
	}

	public <T extends NumericType<T> & NativeType<T>> RandomAccessibleInterval<T> transform(
			final RandomAccessibleInterval<T> img,
			final InvertibleRealTransformSequence tform ) {

		
//		Interval bbox = boundingBox(tform, img);
//		if (Arrays.stream(bbox.minAsDoubleArray()).anyMatch(x -> x < 0)) {
//			final double[] t = Arrays.stream(bbox.minAsDoubleArray()).map(x -> -x).toArray();
//			tform.add(new Translation3D(t));
//		}	
//
//		return Views.interval( 
//			Views.raster( RealViews.transform( 
//				Views.interpolate( Views.extendZero(img), new NLinearInterpolatorFactory<>()),
//				tform)),
//			Intervals.zeroMin(bbox));
		
		Interval bbox = boundingBox(tform, img);
		System.out.println(Intervals.toString(bbox));

		return Views.interval( 
			Views.raster( RealViews.transform( 
				Views.interpolate( Views.extendZero(img), new NLinearInterpolatorFactory<>()),
				tform)),
			bbox);
	}
	
	public static Interval boundingBox(final InvertibleRealTransform tform, final Interval interval ) {

	    // Ensure we have at least 3 dimensions
	    if (interval.numDimensions() < 3)
	        throw new IllegalArgumentException("Interval must have at least 3 dimensions for z-translation computation");

		int nd = interval.numDimensions();
		final long[] min = new long[nd];
		Arrays.fill(min, Long.MAX_VALUE);

		final long[] max = new long[nd];
		Arrays.fill(max, Long.MIN_VALUE);

		// only need to check corners
		IntervalIterator iterator = new IntervalIterator( Intervals.createMinMax( 0, 0, 0, 1, 1, 1 ) );
		RealPoint cornerPoint = new RealPoint( interval.numDimensions() );
		RealPoint transformedPoint = new RealPoint( interval.numDimensions() );

		while ( iterator.hasNext() )
		{
			iterator.fwd();
			corner(interval, iterator, cornerPoint);

			// Apply distortion transformation
//			tform.applyInverse(transformedPoint, cornerPoint);
			tform.apply(cornerPoint, transformedPoint);
//			System.out.println("corner: " + cornerPoint );
//			System.out.println("  ->  : " + transformedPoint );

			for( int i = 0; i < nd; i++ ) {
				min[i] = Math.min(min[i], (long)transformedPoint.getDoublePosition(i));
				max[i] = Math.max(max[i], (long)transformedPoint.getDoublePosition(i));
			}
//			System.out.println("min : " + Arrays.toString(min) );
//			System.out.println("max : " + Arrays.toString(max) );
		}

//			System.out.println("min : " + Arrays.toString(min) );
//			System.out.println("max : " + Arrays.toString(max) );
	    return new FinalInterval(min, max);
	}

	public void addNormalizationOffset( InvertibleRealTransformSequence totalDistortion, double[] minMax ) {
        final double min = minMax[0];
        final double max = minMax[1];
//        final double d = Math.abs( min ) > Math.abs( max ) ? max : min;
        final double d = (min + max) / 2.0;
		totalDistortion.add( new Translation3D(new double[] {0, 0, -d}) );
	}
	
	public static double[] computeOriginOffsets(InvertibleRealTransform distortion, Interval interval) {

	    // Ensure we have at least 3 dimensions
	    if (interval.numDimensions() < 3)
	        throw new IllegalArgumentException("Interval must have at least 3 dimensions for z-translation computation");

		RealPoint origin = new RealPoint( interval.numDimensions() );
		RealPoint transformedPoint = new RealPoint( interval.numDimensions() );

		// Apply distortion transformation
		distortion.apply( origin, transformedPoint );

		// Calculate z-translation (difference in z-coordinate)
		double zTranslation = transformedPoint.getDoublePosition( 2 ) - origin.getDoublePosition( 2 );

	    return new double[]{zTranslation, zTranslation};
	}
	
	public static double[] computeMiddleOffsets(InvertibleRealTransform distortion, Interval interval) {

	    // Ensure we have at least 3 dimensions
	    if (interval.numDimensions() < 3)
	        throw new IllegalArgumentException("Interval must have at least 3 dimensions for z-translation computation");

		RealPoint middle = new RealPoint(interval.numDimensions());
		for (int i = 0; i < interval.numDimensions(); i++)
			middle.setPosition((interval.realMax(i) - interval.realMin(i)) / 2.0, i);

		RealPoint transformedPoint = new RealPoint( interval.numDimensions() );

		// Apply distortion transformation
		distortion.apply( middle, transformedPoint );

		// Calculate z-translation (difference in z-coordinate)
		double zTranslation = transformedPoint.getDoublePosition( 2 ) - middle.getDoublePosition( 2 );

	    return new double[]{zTranslation, zTranslation};
	}
	
	public static double[] computeMinMaxOffsetsCorners(InvertibleRealTransform distortion, Interval interval) {

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

	public static double[] computeMinMaxOffsets(InvertibleRealTransform distortion, Interval interval) {

	    // Ensure we have at least 3 dimensions
	    if (interval.numDimensions() < 3)
	        throw new IllegalArgumentException("Interval must have at least 3 dimensions for z-translation computation");

	    double minZTranslation = Double.POSITIVE_INFINITY;
	    double maxZTranslation = Double.NEGATIVE_INFINITY;

		// only need to check corners
		IntervalIterator iterator = new IntervalIterator( interval );
		RealPoint transformedPoint = new RealPoint( interval.numDimensions() );

		while ( iterator.hasNext() )
		{
			iterator.fwd();

			// Apply distortion transformation
			distortion.apply( iterator, transformedPoint );

			// Calculate z-translation (difference in z-coordinate)
			double zTranslation = transformedPoint.getDoublePosition( 2 ) - iterator.getDoublePosition( 2 );

		    // Update min/max
	        minZTranslation = Math.min(minZTranslation, zTranslation);
	        maxZTranslation = Math.max(maxZTranslation, zTranslation);
		}

	    return new double[]{minZTranslation, maxZTranslation};
	}
	
	/**
	 * Returns a transformation that corrects distortion.
	 * <p>.
	 * The distortion transformation is modeled in physical-image space, and
	 * the input and output are in camera space. This method then transforms
	 * the camera image to physical image space, applies the inverse of
	 * the distortion, and goes back to camera space.
	 * 
	 * 
	 * (cam distorted) -> (img distorted) -> (img undistorted) -> (cam undistorted)
	 * 
	 * @return
	 */
	public InvertibleRealTransformSequence totalDistortionCorrectionTransform(int setupId, boolean inverse) {
		return concatenate( 
				cameraToImage(setupId),
				distortionTransform(inverse),
				imageToCamera(setupId));
	}
	
	/**
	 * Returns a transformation that corrects distortion.
	 * <p>.
	 * The distortion transformation is modeled in physical-image space, and
	 * the input and output are in camera space. This method then transforms
	 * the camera image to physical image space, applies the inverse of
	 * the distortion, and goes back to camera space.
	 * 
	 * 
	 * (cam distorted) -> (img distorted) -> (img undistorted) -> (cam undistorted)
	 * 
	 * @return
	 */
	public InvertibleRealTransformSequence physicalDistortionCorrectionTransform(int setupId, boolean inverse) {
		return concatenate( 
				cameraToImage( setupId ),
				distortionTransform(inverse));
	}
	
	public InvertibleRealTransform distortionTransform(boolean inverse) {

		if ( inverse )
			return new SphericalCurvatureZDistortion( 3, 2, R );
		else
			return new SphericalCurvatureZDistortion( 3, 2, R ).inverse();
	}
	
	public static InvertibleRealTransformSequence concatenate( InvertibleRealTransform... transforms) {

		InvertibleRealTransformSequence renderingTransform = new InvertibleRealTransformSequence();
		for( InvertibleRealTransform t : transforms )
			renderingTransform.add(t);

		return renderingTransform;
	}
	
	/**
	 * Pixel to physical coordinates
	 * 
	 * @return the camera transform
	 */
	public ScaleAndTranslation cameraToImage(int cameraId)
	{
		return new ScaleAndTranslation(
				new double[] {rx, ry, rz},
				cameraTranslationsMicronUnits.get(cameraId));
	}

	public ScaleAndTranslation imageToCamera(int cameraId)
	{
		return cameraToImage(cameraId).inverse();
	}

	public void loadCameraTranslations() {

		cameraTranslationsMicronUnits = new HashMap<>();
		for ( int setupId = 0; setupId < 600; setupId++ )
			cameraTranslationsMicronUnits.put( setupId, CameraUtils.offset(setupId) );
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
