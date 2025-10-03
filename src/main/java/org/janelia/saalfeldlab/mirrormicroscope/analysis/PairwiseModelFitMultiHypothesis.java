package org.janelia.saalfeldlab.mirrormicroscope.analysis;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.mirrormicroscope.CameraModel;
import org.janelia.saalfeldlab.mirrormicroscope.OpticalModel;

import mpicbg.models.AbstractAffineModel3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;

public class PairwiseModelFitMultiHypothesis {
	
	static double rx = 0.157;
	static double ry = 0.157;
	static double rz = 1.0;
	
	static String TRANSLATION = "translation";
	static String AFFINE = "affine";

	public static void main(String[] args) {

		String uriString = args[0];
		if (!uriString.startsWith("file:")) {
			uriString = "file:" + uriString;
		}

		URI uri = URI.create(uriString);
		String detectionName = args[1];
		String baseDestination = args[2];

		String modelType = "affine";
		if( args.length > 3 )
			modelType = args[3];

		PairwiseModelFitMultiHypothesis alg = new PairwiseModelFitMultiHypothesis(uri, detectionName);
		alg.baseDestination = baseDestination;
		alg.run(getModel(modelType));
	}

	SpimData2 data;
	String detectionName;
	String baseDestination;


	CameraModel cameraModel;
	double radius = OpticalModel.R;
	Interval itvl;

	// matching parameters
	final int numNeighbors = 3; // number of neighbors the descriptor is built from
	final int redundancy = 1; // redundancy of the descriptor (adds more neighbors and tests all combinations)
	final float ratioOfDistance = 3.0f; // how much better the best than the second best descriptor need to be
	final boolean limitSearchRadius = true; // limit search to a radius
	final float searchRadius = 1000.0f; // the search radius

	final int minNumCorrespondences = 50;
	final int numIterations = 10_000;
	final double maxEpsilon = 2.5; // setting this very low so we get multi-consensus
	final double minInlierRatio = 0.1;

	public PairwiseModelFitMultiHypothesis( URI uri, String detectionName ) {

		try {
			data = new XmlIoSpimData2().load( uri );
		} catch (SpimDataException e) {
			e.printStackTrace();
		}

		cameraModel = CameraModel.fromArgs(3, "1-8");
		itvl = new FinalInterval(new long[]{4096, 2560, 3300});

		this.detectionName = detectionName;
	}

	private static Model<?> getModel( String modelType ) {

		final String modelTypeNorm = modelType.toLowerCase();
		if( modelTypeNorm.equals(TRANSLATION))
			return new TranslationModel3D();
		else if( modelTypeNorm.equals(AFFINE))
			return new AffineModel3D();
		else
		{
			System.out.println("Unknown model type: " + modelType);
			return null;
		}
	}

	public void run(Model<?> modelType) {
		
		Model model = modelType.copy();
		System.out.println( "mvgId,mvgCamera,fixId,fixCamera,numCorrespondences," +
				"affine-xx,affine-xy,affine-xz,affine-xt," + 
				"affine-yx,affine-yy,affine-yz,affine-yt," +
				"affine-zx,affine-zy,affine-zz,affine-zt");

		tilePairs().forEach( p -> {
			 
			int setupId1 = p[0];
			int setupId2 = p[1];

			PrintWriter pointWriter;
			PrintWriter modelWriter;
			try {

				pointWriter = new PrintWriter(new FileWriter(
						new File(String.format("%s_%d-%d-pts.csv", baseDestination, setupId1, setupId2))));

				modelWriter = new PrintWriter(new FileWriter(
						new File(String.format("%s_%d-%d-models.csv", baseDestination, setupId1, setupId2))));

			} catch (IOException e) {
				e.printStackTrace();
				return;
			}

			final ViewId viewId1 = new ViewId(0, setupId1);
			final int camera1 = setupToCamera(setupId1);

			final ViewId viewId2 = new ViewId(0, setupId2);
			final int camera2 = setupToCamera(setupId2);

			// calibration etc
			final SequenceDescription sd = data.getSequenceDescription();

			// get interest points
			final List<InterestPoint> ipListLocal1 = getInterestPoints( data, viewId1, detectionName );
			final List<InterestPoint> ipListLocal2 = getInterestPoints( data, viewId2, detectionName );
			
			System.out.println( "Loaded " + ipListLocal1.size() + " and " + ipListLocal2.size() + " interest points (in local coordinates of each view)." );

			// current transforms for the views
			final AffineTransform3D t1 = getTransform( data, viewId1 );
			final AffineTransform3D t2 = getTransform( data, viewId2 );
			
			System.out.println( "Transform1: " + t1 );
			System.out.println( "Transform2: " + t2 );
			
			// transform interest points into global coordinate space
			final List<InterestPoint> ip1 = TransformationTools.applyTransformation( ipListLocal1, t1 );
			final List<InterestPoint> ip2 = TransformationTools.applyTransformation( ipListLocal2, t2 );
			
			// find which points currently overlap with the other view
			final Dimensions dim1 = sd.getViewDescription( viewId1 ).getViewSetup().getSize();
			final Dimensions dim2 = sd.getViewDescription( viewId2 ).getViewSetup().getSize();

			// expanding the interval a bit to be more robust
			final List<InterestPoint> ipOverlap1 =
					overlappingPoints( ip1, Intervals.expand( new FinalInterval( dim2 ), 10 ), t2 );
			final List<InterestPoint> ipOverlap2 =
					overlappingPoints( ip2, Intervals.expand( new FinalInterval( dim1 ), 10 ), t1 );

			System.out.println( "Overlapping points 1: " + ipOverlap1.size() );
			System.out.println( "Overlapping points 2: " + ipOverlap2.size() );

			final RGLDMMatcher<InterestPoint> matcher = new RGLDMMatcher<>();
			List< PointMatchGeneric< InterestPoint > > candidates = matcher.extractCorrespondenceCandidates(
					ipOverlap1,
					ipOverlap2,
					numNeighbors,
					redundancy,
					ratioOfDistance,
					Float.MAX_VALUE,
					limitSearchRadius,
					searchRadius);

			System.out.println( "Found " + candidates.size() + " correspondence candidates." );

			// perform RANSAC (warning: not safe for multi-threaded over pairs of images, this needs point duplication)
			final ArrayList< PointMatchGeneric< InterestPoint > > inliers = new ArrayList<>();
			
			final ArrayList< PointMatchGeneric< InterestPoint > > allMatches = new ArrayList<>();

			int consensusSetId = 0;

			boolean multiConsenus = true;
			boolean modelFound = false;
			do
			{
				inliers.clear();
				try
				{
					modelFound = model.filterRansac(
							candidates,
							inliers,
							numIterations,
							maxEpsilon, minInlierRatio ); 
				}
				catch ( NotEnoughDataPointsException e )
				{
					System.out.println( "Not enough points for matching. stopping.");
					System.exit( 1 );
				}
		
				if ( modelFound && inliers.size() >= minNumCorrespondences )
				{
					// highly suggested in general
					// inliers = RANSAC.removeInconsistentMatches( inliers );

					System.out.println( "Found " + inliers.size() + "/" + candidates.size() + " inliers with model: " + model );

					allMatches.addAll(inliers);
					writeInliers(pointWriter, consensusSetId, inliers );
					writeModel( modelWriter, consensusSetId, (AbstractAffineModel3D<?>)model);
					consensusSetId++;

					if ( multiConsenus )
						candidates = removeInliers( candidates, inliers );
				}
				else if ( modelFound )
				{
					System.out.println( "Model found, but not enough points (" + inliers.size() + "/" + minNumCorrespondences + ").");
				}
				else
				{
					System.out.println( "NO model found.");
				}
			} while ( multiConsenus && modelFound && inliers.size() >= minNumCorrespondences );
			

			try {
				System.out.println("Fitting model with all points, total: " + allMatches.size());
				model.fit(allMatches);
					writeModel( modelWriter, -1, (AbstractAffineModel3D<?>)model);
			} catch (NotEnoughDataPointsException e) {
				e.printStackTrace();
			} catch (IllDefinedDataPointsException e) {
				e.printStackTrace();
			}

			pointWriter.close();
			modelWriter.close();
		});
	}

	public void writeModel(final PrintWriter writer, int id, AbstractAffineModel3D<?> model ) {
		final double[] params = new double[12];
		model.getMatrix(params);
		writer.println( String.format("%d,%s",
				id, print(params)));
	}

	public void writeInliers(final PrintWriter writer, int id, List<PointMatchGeneric< InterestPoint >> inliers ) {

		for (int i = 0; i < inliers.size(); i++) {

			final double[] p1 = inliers.get(i).getP1().getL();
			final double[] p2 = inliers.get(i).getP2().getL();

			String line = String.format("%s,%s,%s,%s,%s", 
					InterestPointsToCsv.quotes("Pt-"+i),
					InterestPointsToCsv.quotes("true"),
					InterestPointsToCsv.quotes(Integer.toString(id)),
					InterestPointsToCsv.print(p1),
					InterestPointsToCsv.print(p2));

			writer.println(line);
		}

		writer.flush();
	}
	
	public static AffineTransform3D getTransform( final SpimData data, final ViewId viewId )
	{
		final Map<ViewId, ViewRegistration> rMap = data.getViewRegistrations().getViewRegistrations();
		final ViewRegistration reg = rMap.get( viewId );
		reg.updateModel();
		return reg.getModel();
	}

	public static List<InterestPoint> getInterestPoints( final SpimData2 data, final ViewId viewId, final String label )
	{
		final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
		final ViewInterestPointLists iplists = iMap.get( viewId );

		// this is net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5
		final InterestPoints ips = iplists.getInterestPointList( label );

		// load interest points
		return ips.getInterestPointsCopy();
	}

	public static List<InterestPoint> overlappingPoints( final List<InterestPoint> ip1, final Interval intervalImg2, final AffineTransform3D t2 )
	{
		// use the inverse affine transform of the other view to map the points into the local interval of img2
		final AffineTransform3D t2inv = t2.inverse();

		final RealPoint p = new RealPoint( intervalImg2.numDimensions() );

		return ip1.stream().filter( ip -> {
			ip.localize( p );
			t2inv.apply( p, p );
			return Intervals.contains( intervalImg2 , p );
		} ).collect( Collectors.toList() );
	}

	public static < P extends PointMatch > List< P > removeInliers( final List< P > candidates, final List< P > matches )
	{
		final HashSet< P > matchesSet = new HashSet<>( matches );
		return candidates.stream().filter( c -> !matchesSet.contains( c ) ).collect( Collectors.toList() );
	}

	public ArrayList<Integer> setupIds() {

		int nc = 3;
		int nr = 32;
		int N = nr * nc;

		ArrayList<Integer> setupIds = new ArrayList<>();
		for( int i = 0; i < N; i++ )
			setupIds.add(i);

		return setupIds;
	}

	public ArrayList<int[]> tilePairs() {

		int nc = 3;
		int nr = 32;
		ArrayList<int[]> pairs = new ArrayList<>();
		
//		pairs.add(new int[] {46,49});
//		if (true)
//			return pairs;

		for (int r = 0; r < nr; r++) {
			for (int c = 0; c < nc; c++) {

				int setup = setup(r,c,nc);

				// horizontal
				if ( c < nc-1 ) {
					pairs.add(new int[]{
						setup,
						setup(r, c + 1, nc)
					});
				}
				
				// vertical
				if ( r < nr-1 ) {
					pairs.add(new int[]{
						setup,
						setup(r + 1, c, nc)
					});
				}
			}
		}		
		return pairs;
	}

	public int setup(int r, int c, int nc) {
		return c + nc * r;
	}

	private static int setupToCamera(int i) {
		return (i / 12) + 1;
	}

	public static String print(final double[] x) {
		return Arrays.stream(x)
			.mapToObj(Double::toString)
			.collect(Collectors.joining(","));
	}

}
