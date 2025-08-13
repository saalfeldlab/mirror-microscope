package org.janelia.saalfeldlab.mirrormicroscope;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.realtransform.distortion.SphericalCurvatureZDistortion;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
	name = "FieldCorrection", 
	mixinStandardHelpOptions = true,
	version = "0.1", 
	description = "Optical field correction for mirror microscope.")
public class FieldCorrection implements Runnable
{

	@Option( names = { "-r", "--root-directory" }, description = "Root directory path", required = true )
	private String inputRoot;

	@Option( names = { "-s", "--setup-id" }, description = "Setup ID number", required = true )
	private int setupId;

	@Option( names = { "-o", "--output-root" }, description = "Output n5 root", required = false )
	private String outputRoot;
	
	@Option( names = { "-d", "--datset-pattern" }, description = "Dataset pattern, default: (setup\\%d)", required = false )
	private String datasetPattern = "setup%d/timepoint0/s0";

	@Option( names = { "-do", "--datset-output-pattern" }, description = "Dataset output pattern, default: (setup\\%d)", required = false )
	private String datasetOutputPattern = "setup%d";

	@Option( names = { "-i", "--inverse" }, fallbackValue = "true", arity = "0..1", description = "Flag to invert distortion transformation.", required = false )
	private boolean inverse = false;

	@Option( names = { "-v", "--view" }, fallbackValue = "true", arity = "0..1", description = "Flag to view the transformed result.", required = false )
	private boolean view = false;

	@Option( names = { "-j", "--num-jobs" }, description = "Number of threads", required = false )
	private int nThreads=1;

	private N5Reader n5r;
	private N5Writer n5w;

	private DatasetAttributes inputAttributes;
	private String inputDatasetPath;

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
	final double rx = 0.157; 	// um / pix 
	final double ry = 0.157;	// um / pix
	final double rz = 1.0;		// um / pix

	// camera to image scaling factors
	double pixSpacingX;
	double pixSpacingY;
	double pixSpacingZ;

	double wx; 	// um
	double wy; 	// um
	double wz; 	// um

	public static void main( String[] args )
	{
		int exitCode = new CommandLine( new FieldCorrection() ).execute( args );
		System.exit( exitCode );
	}

	@Override
	public void run()
	{
		process();
	}

	private < T extends NumericType< T > & NativeType< T > > void process()
	{
		System.out.println( "Processing application with:" );
		System.out.println( "  - Root directory: " + inputRoot );
		System.out.println( "  - Setup ID: " + setupId );
		System.out.println( "  - inverse: " + inverse );
		
		RandomAccessibleInterval< T > rawImg = read();
		RandomAccessibleInterval< T > correctedImg = to5d(runCorrection(rawImg));

		if ( outputRoot != null )
			write( correctedImg );
		else if( view ){
			final BdvOptions opts = BdvOptions.options().numRenderingThreads( nThreads );
			final BdvStackSource< T > bdv = BdvFunctions.show( rawImg, "raw", opts );
			BdvFunctions.show( correctedImg, "corrected", opts.addTo( bdv ));
		}
	}

	private < T extends NumericType< T > & NativeType< T > > RandomAccessibleInterval< T > read() {

		n5r = new N5Factory().openReader(inputRoot);
		inputDatasetPath = String.format( datasetPattern, setupId );

		inputAttributes	= n5r.getDatasetAttributes( inputDatasetPath );
		final CachedCellImg< T, ? > img = N5Utils.open( n5r, inputDatasetPath );
		nx = img.dimension( 0 );
		ny = img.dimension( 1 );
		nz = img.dimension( 2 );

		wx = nx * rx;
		wy = ny * ry;
		wz = nz * rz;

		pixSpacingX = rx / m0;
		pixSpacingY = ry / m0;
		pixSpacingZ = rz;

		return img;
	}

	private < T extends NumericType< T > & NativeType< T > > void write( RandomAccessibleInterval< T > img )
	{
		n5w = new N5Factory()
				.zarrDimensionSeparator("/")
				.openWriter( outputRoot );

		final String baseDset = String.format( datasetOutputPattern, setupId );
		// bigstitcher needs the array for an ome-zarr dataset to be "/0"
		final String arrayDset = baseDset + "/0";
		if ( nThreads == 1 )
			N5Utils.save( img, n5w, arrayDset, to5d(inputAttributes.getBlockSize()), inputAttributes.getCompression() );
		else
			try
			{
				N5Utils.save( img, n5w, arrayDset, to5d(inputAttributes.getBlockSize()), inputAttributes.getCompression(), Executors.newFixedThreadPool( nThreads ) );
			}
			catch ( InterruptedException e )
			{
				e.printStackTrace();
			}
			catch ( ExecutionException e )
			{
				e.printStackTrace();
			}

		// set metadata
		n5w.setAttribute(baseDset, "/", buildNgffMeta());
	}

	public < T extends NumericType< T > & NativeType< T > > RandomAccessibleInterval< T > runCorrection( RandomAccessibleInterval< T > rawImg)
	{
        System.out.println( "  setupId     : " + setupId);
		System.out.println( "  tlation (um): " + Arrays.toString(CameraUtils.offset(setupId)));

		final InvertibleRealTransformSequence totalDistortion = totalDistortionCorrectionTransform( setupId );
		final double[] minMax = computeMinMaxOffsets( totalDistortion, rawImg );
		System.out.println( "  min offset: " + minMax[ 0 ] );
		System.out.println( "  max offset: " + minMax[ 1 ] );

		addNormalizationOffset(totalDistortion, minMax);
		final double[] minMaxAfter = computeMinMaxOffsets( totalDistortion, rawImg );
		System.out.println( "  min offset: " + minMaxAfter[ 0 ] );
		System.out.println( "  max offset: " + minMaxAfter[ 1 ] );

		return Views.interval( 
			Views.raster( RealViews.transform( 
				Views.interpolate( Views.extendZero(rawImg), new NLinearInterpolatorFactory<>()),
				totalDistortion)),
			rawImg);
	}

	public <T extends NumericType<T> & NativeType<T>> RandomAccessibleInterval<T> to5d(RandomAccessibleInterval<T> img)
	{
		return Views.addDimension(Views.addDimension(img, 0, 0), 0, 0);
	}

	public int[] to5d( int[] blkSize ) {
		return new int[]{blkSize[0], blkSize[1], blkSize[2], 1, 1};
	}

	private void addNormalizationOffset( InvertibleRealTransformSequence totalDistortion, double[] minMax ) {
        final double min = minMax[0];
        final double max = minMax[1];
//        final double d = Math.abs( min ) > Math.abs( max ) ? max : min;
        final double d = (min + max) / 2.0;
		totalDistortion.add( new Translation3D(new double[] {0, 0, -d}) );
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
				CameraUtils.offset(setupId));
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
	
	public Scale3D identity()
	{
		return new Scale3D( 1, 1, 1 );
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

	private static JsonElement buildNgffMeta() {
		Gson gson = new Gson();
		String s = "{\n"
				+ "  \"multiscales\": [\n"
				+ "    {\n"
				+ "      \"name\": \"\",\n"
				+ "      \"type\": \"Sample\",\n"
				+ "      \"version\": \"0.4\",\n"
				+ "      \"axes\": [ \n"
				+ "        { \"type\": \"time\", \"name\": \"t\", \"unit\": \"second\" },\n"
				+ "        { \"type\": \"channel\", \"name\": \"c\" },\n"
				+ "        { \"type\": \"space\", \"name\": \"z\", \"unit\": \"pixel\" },\n"
				+ "        { \"type\": \"space\", \"name\": \"y\", \"unit\": \"pixel\" },\n"
				+ "        { \"type\": \"space\", \"name\": \"x\", \"unit\": \"pixel\" }\n"
				+ "      ],\n"
				+ "      \"datasets\": [\n"
				+ "        {\n"
				+ "          \"path\": \"0\",\n"
				+ "          \"coordinateTransformations\": [\n"
				+ "            { \"scale\": [ 1, 1, 1, 0.157, 0.157 ], \"type\": \"scale\" },\n"
				+ "            { \"translation\": [ 0, 0, 0, 0, 0 ], \"type\": \"translation\" }\n"
				+ "          ]\n"
				+ "        }\n"
				+ "      ],\n"
				+ "      \"coordinateTransformations\": []\n"
				+ "    }\n"
				+ "  ]\n"
				+ "}\n";

		return gson.fromJson(s, JsonElement.class);
	}

}
