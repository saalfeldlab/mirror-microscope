package org.janelia.saalfeldlab.mirrormicroscope.analysis;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.mirrormicroscope.CameraModel;

import mpicbg.models.AffineModel3D;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.Scale3D;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;

public class PointMatches {

	static double rx = 0.157;
	static double ry = 0.157;
	static double rz = 1.0;

	public static void main(String[] args) {

		URI uri = URI.create(args[0]);
		String detectionName = args[1];

		SpimData2 data;
		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
			return;
		}

		PointMatches alg = new PointMatches(uri, detectionName);
		alg.run();
	}

	SpimData2 data;
	String detectionName;

	final int minDataPoints = 4;

	CameraModel cameraModel;
	Interval itvl;

	public PointMatches( URI uri, String detectionName ) {

		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
		}

		cameraModel = CameraModel.fromArgs(3, "1-8");
		itvl = new FinalInterval(new long[]{4096, 2560, 3300});

		this.detectionName = detectionName;
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

	private static int setupToCamera(int i) {
		return (i / 12) + 1;
	}

	public void run() {

		System.out.println("mvgId,mvgCamera,fixId,fixCamera,numCorrespondences," +
				"min-x,max-x,min-y,max-y,min-z,max-z");

		tilePairs().forEach( p -> {

			int setupIdMvg = p[0];
			int setupIdFix = p[1];

			final ViewId viewIdMvg = new ViewId(0, setupIdMvg);
			final ViewId viewIdFix = new ViewId(0, setupIdFix);

			int cameraMvg = setupToCamera(setupIdMvg);
			int cameraFix = setupToCamera(setupIdFix);

//			final AffineTransform3D mvgTform = data.getViewRegistrations().getViewRegistration(viewIdMvg).getModel(); 
//			final AffineTransform3D fixTform = data.getViewRegistrations().getViewRegistration(viewIdFix).getModel(); 

			// identity
			final AffineTransform3D mvgTform = new AffineTransform3D();
			final AffineTransform3D fixTform = new AffineTransform3D();

			final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
			final InterestPoints ipMvg = iMap.get(viewIdMvg).getInterestPointList(detectionName);
			final InterestPoints ipFix = iMap.get(viewIdFix).getInterestPointList(detectionName);

			final List<CorrespondingInterestPoints> corr = getCorrespondences(viewIdMvg, viewIdFix);
			if( corr == null )
				return;

			int N = corr.size();

			String errorStats = coordStats(corr, ipMvg, mvgTform, ipFix, fixTform, setupIdFix);
			if (errorStats == null)
				return;			

			System.out.println(String.format("%d,%d,%d,%d,%d,"
					+ "%s", 
						setupIdMvg, cameraMvg, setupIdFix, cameraFix, N,
						errorStats));
		});
	}
	
	public String coordStats(
			final List<CorrespondingInterestPoints> mvgToFixedCorrespondences,
			final InterestPoints ipMvg,
			final InvertibleRealTransform mvgTform,
			final InterestPoints ipFix,
			final InvertibleRealTransform fixTform,
			int debugSetupIdFix) {
		
		int N = mvgToFixedCorrespondences.size();
		
		if( N < minDataPoints )
			return null;

		final double[] mvg = new double[3];
		final double[] fix = new double[3];
		final double[] tmpS = new double[3];
		final double[] tmpD = new double[3];

		final List<InterestPoint> ptsMvg = ipMvg.getInterestPointsCopy();
		final List<InterestPoint> ptsFix = ipFix.getInterestPointsCopy();

		final Stats statsX = new Stats();
		final Stats statsY = new Stats();
		final Stats statsZ = new Stats();
		for( int i = 0; i < N; i++ ) {

			CorrespondingInterestPoints cpoint = mvgToFixedCorrespondences.get( i );

			int mvgId = cpoint.getDetectionId();
			String otherLabel = cpoint.getCorrespodingLabel();
			ViewId otherViewId = cpoint.getCorrespondingViewId();
			int otherId = cpoint.getCorrespondingDetectionId();

			if( otherViewId.getViewSetupId() != debugSetupIdFix)
				System.out.println("unexpected otherViewId: " + otherViewId);

			if( !otherLabel.equals(detectionName))
				System.out.println("unexpected otherLabel: " + otherLabel);

			final InterestPoint mvgPoint = 
					transformPoint(mvgTform, ptsMvg.get( mvgId ), tmpS, tmpD);	
			mvg[0] = mvgPoint.getDoublePosition(0);
			mvg[1] = mvgPoint.getDoublePosition(1);
			mvg[2] = mvgPoint.getDoublePosition(2);

			final InterestPoint fixPoint = 
					transformPoint(fixTform, ptsFix.get( otherId ), tmpS, tmpD);	
			fix[0] = fixPoint.getDoublePosition(0);
			fix[1] = fixPoint.getDoublePosition(1);
			fix[2] = fixPoint.getDoublePosition(2);

			statsX.addSample(mvg[0]);
			statsY.addSample(mvg[1]);
			statsZ.addSample(mvg[2]);
		}

		return statsX.toString() + "," + statsY.toString() + "," + statsZ.toString();
	}

	public ArrayList<int[]> tilePairs() {

		int nc = 3;
		int nr = 32;
		int N = nr * nc;

		ArrayList<int[]> pairs = new ArrayList<>();

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
	
	public List<CorrespondingInterestPoints> getCorrespondences(
			final ViewId viewIdMvg, final ViewId viewIdFix ) {

		Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();

		ViewInterestPointLists iplists = iMap.get( viewIdMvg );

		// this is net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5
		InterestPoints ips = iplists.getInterestPointList( detectionName ); 

		if (ips == null) {
			System.out.println("no interest points found for " + viewIdMvg + " <-> " + viewIdFix);
			return null;
		}

		List<CorrespondingInterestPoints> corr = ips.getCorrespondingInterestPointsCopy();
		return corr.stream().filter( c -> {
			return c.getCorrespondingViewId().getViewSetupId() == viewIdFix.getViewSetupId();
		}).collect(Collectors.toList());
	}


	public static double[] transformPointToArray( RealTransform tform, InterestPoint pt) {

		double[] tmpSrc = new double[3];
		double[] tmpDst = new double[3];
		pt.localize(tmpSrc);
		tform.apply(tmpSrc, tmpDst);
		return tmpDst;
	}
	
	public static InterestPoint transformPoint( InvertibleRealTransform tform, InterestPoint pt,
			double[] tmpSrc, double[] tmpDst ) {

		pt.localize(tmpSrc);
//		tmpSrc = pt.getL();

		tform.apply(tmpSrc, tmpDst);
		return new InterestPoint(pt.getId(), tmpDst);
	}
	
	public static double distance( double[] x, double[] y )  {
		double d = 0;
		for( int i = 0; i < x.length; i++ ) {
			double diff = x[i] - y[i];
			d += diff*diff;
		}
		return Math.sqrt(d);
	}


	public static InvertibleRealTransform identity() {
		return new Scale3D(1, 1, 1);
	}

	public static AffineTransform3D fromAffineModel(AffineModel3D model) {

		AffineTransform3D out = new AffineTransform3D();
		double[] params = new double[12];
		model.getMatrix(params);
		out.set(params);
		return out;
	}
	
	private static class Stats  {
		
		private double min = Double.MAX_VALUE;
		private double max = -1;

		private double sum = 0;
		private double sumSquares = 0;

		private double N = 0;

		public void addSample( final double x ) {
	
			min = x < min ? x : min;
			max = x > max ? x : max;

			sum += x;
			sumSquares += x;

			N++;
		}

		@Override
		public String toString() {
			return String.format("%d,%f,%f,%f,%f",
					N,  sum, sumSquares, min, max);
		}

	}

}
