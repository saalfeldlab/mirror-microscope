package org.janelia.saalfeldlab.mirrormicroscope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
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

	@Option( names = { "--offset-metadata" }, description = "Path to offset metadata csv", required = true )
	private String offsetMetadataPath;

	@Option( names = { "-o", "--output-root" }, description = "Output n5 root", required = true )
	private String outputRoot;
	
	@Option( names = { "-d", "--datset-pattern" }, description = "Dataset pattern, default: (setup\\%d)", required = false )
	private String datasetPattern = "setup%d";

	@Option( names = { "-i", "--inverse" }, fallbackValue = "true", description = "Flag to inverst distortion transformation.", required = false )
	private boolean inverse = false;

	@Option( names = { "-j", "--num-jobs" }, description = "Number of threads", required = false )
	private int nThreads=1;

	@Option( names = { "--skip-write" }, fallbackValue = "false", description = "Skip write, debug only.", required = false )
	private boolean skipWrite;

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
	long nx = 4096;
	long ny = 2560;
	long nz = 4101;

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

	HashMap<Integer,double[]>  cameraTranslationsPixelUnits;
	HashMap<Integer,double[]>  cameraTranslationsMicronUnits;

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
		// Example business logic
		System.out.println( "Processing application with:" );
		System.out.println( "  - Root directory: " + inputRoot );
		System.out.println( "  - Setup ID: " + setupId );
		System.out.println( "  - inverse: " + inverse );
		
		loadCameraTranslations();
		RandomAccessibleInterval< T > rawImg = read();
		RandomAccessibleInterval< T > correctedImg = runCorrection(rawImg);

		if ( !skipWrite )
			write( correctedImg );
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
		n5w = new N5Factory().openWriter( outputRoot );
		final String outputDset = String.format( datasetPattern, setupId );
		if ( nThreads == 1 )
			N5Utils.save( img, n5w, outputDset, inputAttributes.getBlockSize(), inputAttributes.getCompression() );
		else
			try
			{
				N5Utils.save( img, n5w, outputDset, inputAttributes.getBlockSize(), inputAttributes.getCompression(), Executors.newFixedThreadPool( nThreads ) );
			}
			catch ( InterruptedException e )
			{
				e.printStackTrace();
			}
			catch ( ExecutionException e )
			{
				e.printStackTrace();
			}
	}

	public < T extends NumericType< T > & NativeType< T > > RandomAccessibleInterval< T > runCorrection( RandomAccessibleInterval< T > rawImg)
	{
		System.out.println( "  setupId: " + setupId);
		System.out.println( "  tlation: " + Arrays.toString( cameraTranslationsMicronUnits.get( setupId )));

		final double[] minMax = computeMinMaxOffsets( totalDistortionCorrectionTransform( setupId ), rawImg );
		System.out.println( "  min offset: " + minMax[ 0 ] );
		System.out.println( "  max offset: " + minMax[ 1 ] );

		return Views.interval( 
			Views.raster( RealViews.transform( 
				Views.interpolate( Views.extendZero(rawImg), new NLinearInterpolatorFactory<>()),
				totalDistortionCorrectionTransform( setupId ))),
			rawImg);
	}

	/**
	 * Pixel to physical coordinates
	 * 
	 * @return the camera transform
	 */
	public ScaleAndTranslation cameraToImage(int cameraId)
	{
		return new ScaleAndTranslation(
				new double[] {pixSpacingX, pixSpacingY, pixSpacingZ},
				cameraTranslationsMicronUnits.get(cameraId));
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
	public InvertibleRealTransform totalDistortionCorrectionTransform(int setupId) {
		return concatenate( 
				cameraToImage( setupId ),
				distortionTransform(),
				imageToCamera( setupId ));
	}
	
	public InvertibleRealTransformSequence concatenate( InvertibleRealTransform... transforms) {

		InvertibleRealTransformSequence renderingTransform = new InvertibleRealTransformSequence();
		for( InvertibleRealTransform t : transforms )
			renderingTransform.add(t);

		return renderingTransform;
	}

	public void loadCameraTranslations() {
		
		List< String > lines;
		try
		{
			lines = Files.readAllLines( Paths.get( offsetMetadataPath ) );
			// create and fill cameraTranslations
			cameraTranslationsPixelUnits = new HashMap<>();
			cameraTranslationsMicronUnits = new HashMap<>();

			int setupId = 0;
			for ( String line : lines )
			{
				String[] split = line.split( "," );

				double[] translation = new double[ 3 ];
				translation[ 0 ] = Double.parseDouble( split[ 0 ] );
				translation[ 1 ] = Double.parseDouble( split[ 1 ] );
				translation[ 2 ] = Double.parseDouble( split[ 2 ] );

				cameraTranslationsPixelUnits.put( setupId, translation );

				double[] translationUm = new double[ 3 ];
				translationUm[ 0 ] = rx * translation[ 0 ];
				translationUm[ 1 ] = ry * translation[ 1 ];
				translationUm[ 2 ] = rz * translation[ 2 ];

				cameraTranslationsMicronUnits.put( setupId, translationUm );

				setupId++;
			}
		}
		catch ( IOException e )
		{
			e.printStackTrace();
		}
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
			distortion.apply( iterator, transformedPoint );

			// Calculate z-translation (difference in z-coordinate)
			double zTranslation = transformedPoint.getDoublePosition( 2 ) - iterator.getDoublePosition( 2 );

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
				p.setPosition( 0, i );
		}

	}

}

