package org.janelia.saalfeldlab.mirrormicroscope.analysis;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.mirrormicroscope.CameraModel;
import org.janelia.saalfeldlab.mirrormicroscope.FieldCorrection;
import org.janelia.saalfeldlab.mirrormicroscope.Normalization;
import org.janelia.saalfeldlab.mirrormicroscope.OpticalModel;

import mpicbg.models.Affine3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;

public class PairwiseModelFit {
	
	static double rx = 0.157;
	static double ry = 0.157;
	static double rz = 1.0;

	public static void main(String[] args) {
		
		URI uri = URI.create(args[0]);
		String detectionName = args[1];
		
		SpimData2 data;
		try {
			data = new XmlIoSpimData2().load( uri );
		} catch (SpimDataException e) {
			e.printStackTrace();
			return;
		}

//		System.out.println("uri: " + uri);
//		System.out.println("mvg id: " + setupIdMvg);
//		System.out.println("fix id: " + setupIdFix);
//		System.out.println("name: " + detectionName);

		PairwiseModelFit alg = new PairwiseModelFit(uri, detectionName);
		alg.run(i -> identity());
//		alg.run(i -> alg.totalDistortionCorrectionTransform(i, false));

//		alg.iterativeAffineFit(new File("allPointsCumulativeTransformed.csv"));
//		alg.iterativeAffineFit(new File("originCumulativeTransformed.csv"));

//		alg.writeTransformedCorrespondences(
//				new File("/home/john/for/keller/fly_brain_3/orig/analysis/tformSetup10-7.csv"),
//				10, 7);
//		alg.writeTransformedCorrespondences(
//				new File("/home/john/for/keller/fly_brain_3/orig/analysis/tformSetup10-13.csv"),
//				10, 13);
//
//		alg.writeTransformedCorrespondences(
//				new File("/home/john/for/keller/fly_brain_3/orig/analysis/tformSetup82-85.csv"),
//				82, 85);
//		alg.writeTransformedCorrespondences(
//				new File("/home/john/for/keller/fly_brain_3/orig/analysis/tformSetup82-79.csv"),
//				82, 79);
	}

	SpimData2 data;
	String detectionName;

	final int minDataPoints = 4;
	
	CameraModel cameraModel;
	Interval itvl;

//	int setupIdMvg;
//	int setupIdFix;

	public PairwiseModelFit( URI uri, String detectionName ) {

		try {
			data = new XmlIoSpimData2().load( uri );
		} catch (SpimDataException e) {
			e.printStackTrace();
		}

		cameraModel = CameraModel.fromArgs(3, "1-8");
		itvl = new FinalInterval(new long[]{4096, 2560, 3300});

		this.detectionName = detectionName;

//		for( int i = 0; i < 96; i++ ) {
////			System.out.println( "setup " + i + " camera " + cameraModel.setupToCamera(i));
//			System.out.println( "setup " + i + " camera " + setupToCamera(i));
//		}

//		int i = 36;
//		System.out.println( "setup " + i + " camera " + cameraModel.setupToCameraBetter(i));

	}
	
	private static int setupToCamera(int i) {
		return (i / 12) + 1;
	}
	
	public InvertibleRealTransform totalDistortionCorrectionTransform(int setupId, boolean inverse) {
		final int cameraId = setupToCamera(setupId);
		InvertibleRealTransformSequence tf = FieldCorrection.concatenate(
				cameraModel.cameraToImage(cameraId),
				OpticalModel.distortionTransform(true),
				cameraModel.imageToCamera(cameraId));

		final double[] minMax = Normalization.minMaxOffsetsCorners(tf, itvl);
		addNormalizationOffset(tf, minMax);

		if (inverse)
			return tf.inverse();
		else
			return tf;
	}
	
	private void addNormalizationOffset( InvertibleRealTransformSequence totalDistortion, double[] minMax ) {
        final double min = minMax[0];
        final double max = minMax[1];
//        final double d = Math.abs( min ) > Math.abs( max ) ? max : min;
        final double d = (min + max) / 2.0;
		totalDistortion.add( new Translation3D(new double[] {0, 0, -d}) );
	}

	public static void addTransformedCorrespondences(
			final ArrayList<double[]> allPts, 
			final CorrespondingInterestPoints corrs, 
			final InterestPoints ips, 
			final RealTransform tform) {

		final List<InterestPoint> pts = ips.getInterestPointsCopy();

		int mvgId = corrs.getDetectionId();
		String otherLabel = corrs.getCorrespodingLabel();
		ViewId otherViewId = corrs.getCorrespondingViewId();
		int otherId = corrs.getCorrespondingDetectionId();

		final double[] arr = transformPointToArray(tform, pts.get( mvgId ));	
		allPts.add(arr);
	}

	public void run(final Function<Integer,InvertibleRealTransform> cameraToTransform ) {

		System.out.println( "mvgId,mvgCamera,fixId,fixCamera,numCorrespondences," +
				"affine-xx,affine-xy,affine-xz,affine-xt," + 
				"affine-yx,affine-yy,affine-yz,affine-yt," +
				"affine-zx,affine-zy,affine-zz,affine-zt");
		
		tilePairs().forEach( p -> {

			int setupIdMvg = p[0];
			int setupIdFix = p[1];

			final ViewId viewIdMvg = new ViewId(0, setupIdMvg);
			final ViewId viewIdFix = new ViewId(0, setupIdFix);

			int cameraMvg = setupToCamera(setupIdMvg);
			int cameraFix = setupToCamera(setupIdFix);

			final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
			final InterestPoints ipMvg = iMap.get(viewIdMvg).getInterestPointList(detectionName);
			final InterestPoints ipFix = iMap.get(viewIdFix).getInterestPointList(detectionName);

			final List<CorrespondingInterestPoints> corr = getCorrespondences(viewIdMvg, viewIdFix);
			int N = corr.size();
			AffineModel3D model = fitAffine(corr,
					ipMvg, cameraToTransform.apply(setupIdMvg),
					ipFix, cameraToTransform.apply(setupIdFix),
					setupIdFix);
	
			if( model != null ) {
				double[] affineParams = new double[12];
				model.getMatrix(affineParams);

				System.out.println(String.format("%d,%d,%d,%d,%d,"
						+ "%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f", 
						setupIdMvg, cameraMvg, setupIdFix, cameraFix, N,
						affineParams[0], affineParams[1], affineParams[2], affineParams[3],
						affineParams[4], affineParams[5], affineParams[6], affineParams[7],
						affineParams[8], affineParams[9], affineParams[10], affineParams[11]));
			}
		});
	}
	
	public void runShearOnly(final Function<Integer,InvertibleRealTransform> cameraToTransform ) {

		System.out.println( "mvgId,mvgCamera,fixId,fixCamera,numCorrespondences," +
				"affine-xx,affine-xy,affine-xz,affine-xt," + 
				"affine-yx,affine-yy,affine-yz,affine-yt," +
				"affine-zx,affine-zy,affine-zz,affine-zt");
		
		tilePairs().forEach( p -> {

			int setupIdMvg = p[0];
			int setupIdFix = p[1];

			final ViewId viewIdMvg = new ViewId(0, setupIdMvg);
			final ViewId viewIdFix = new ViewId(0, setupIdFix);
	
			int cameraMvg = setupToCamera(setupIdMvg);
			int cameraFix = setupToCamera(setupIdFix);

			final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
			final InterestPoints ipMvg = iMap.get(viewIdMvg).getInterestPointList(detectionName);
			final InterestPoints ipFix = iMap.get(viewIdFix).getInterestPointList(detectionName);

			final List<CorrespondingInterestPoints> corr = getCorrespondences(viewIdMvg, viewIdFix);
			int N = corr.size();
			AffineModel3D model = fitAffine(corr,
					ipMvg, cameraToTransform.apply(setupIdMvg),
					ipFix, cameraToTransform.apply(setupIdFix),
					setupIdFix);

			if( model != null ) {
				double[] affineParams = new double[12];
				model.getMatrix(affineParams);
				
				System.out.println(String.format("%d,%d,%d,%d,%d,"
						+ "%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f", 
						setupIdMvg, cameraMvg, setupIdFix, cameraFix, N,
						affineParams[0], affineParams[1], affineParams[2], affineParams[3],
						affineParams[4], affineParams[5], affineParams[6], affineParams[7],
						affineParams[8], affineParams[9], affineParams[10], affineParams[11]));
			}
		});
	}
	
	public void writeTransformedCorrespondences(File outputFile, int setupMvg, int setupFix) {
		
		final ViewId viewIdMvg = new ViewId(0, setupMvg);
		final ViewId viewIdFix = new ViewId(0, setupFix);

		int cameraMvg = setupToCamera(setupMvg);
		int cameraFix = setupToCamera(setupFix);

		final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
		final InterestPoints ip1 = iMap.get(viewIdMvg).getInterestPointList(detectionName);
		final InterestPoints ip2 = iMap.get(viewIdFix).getInterestPointList(detectionName);
		List<InterestPoint> pts = ip1.getInterestPointsCopy();
		

		final List<CorrespondingInterestPoints> corr = getCorrespondences(viewIdMvg, viewIdFix);
		int N = corr.size();
		
		InvertibleRealTransform tform = totalDistortionCorrectionTransform(cameraMvg, true);
		final double[] y = new double[3];

//		fitAffine(corr, ip1, ip1, setup2);
		
		PrintWriter writer = null;
		try {
			if (outputFile != null) {
				writer = new PrintWriter(new FileWriter(outputFile));
				writer.println("x,y,z,xt,yt,zt");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		double avgDist = 0;
		for( CorrespondingInterestPoints p : corr ) {

			InterestPoint detection = pts.get( p.getDetectionId());
			double[] x = detection.getL();
			tform.apply(x, y);

			avgDist += distance(x, y);
//			System.out.println(String.format("%f,%f,%f,%f,%f,%f",
//					x[0],x[1],x[2],
//					y[0],y[1],y[2]));

			writer.println(String.format("%f,%f,%f,%f,%f,%f",
					x[0],x[1],x[2],
					y[0],y[1],y[2]));

		}
		

		avgDist = avgDist / N;
		System.out.println("avg distance " + avgDist);
		System.out.println("N: " + N);
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
	
	public AffineModel3D fitTiles(int setupIdMvg, int setupIdFix) {

		final ViewId viewIdMvg = new ViewId(0, setupIdMvg);
		final ViewId viewIdFix = new ViewId(0, setupIdFix);

		final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
		final InterestPoints ipMvg = iMap.get(viewIdMvg).getInterestPointList(detectionName);
		final InterestPoints ipFix = iMap.get(viewIdFix).getInterestPointList(detectionName);

		final List<CorrespondingInterestPoints> corr = getCorrespondences(viewIdMvg, viewIdFix);
		System.out.println("Found " + corr.size() + " correspondences.");

		AffineModel3D model = fitAffine(corr, ipMvg, ipFix, setupIdFix);
		return model;
	}
	
	public List<CorrespondingInterestPoints> getCorrespondences(
			final ViewId viewIdMvg, final ViewId viewIdFix ) {

		Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();

		ViewInterestPointLists iplists = iMap.get( viewIdMvg );

		InterestPoints ips = iplists.getInterestPointList( detectionName ); // this is net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5

		List<CorrespondingInterestPoints> corr = ips.getCorrespondingInterestPointsCopy();
		return corr.stream().filter( c -> {
			return c.getCorrespondingViewId().getViewSetupId() == viewIdFix.getViewSetupId();
		}).collect(Collectors.toList());
	}

	public AffineModel3D fitAffine(
			final List<CorrespondingInterestPoints> mvgToFixedCorrespondences,
			final InterestPoints ipMvg,
			final InterestPoints ipFix,
			int debugSetupIdFix) {

		final InvertibleRealTransform identity = identity();
		return fitAffine( mvgToFixedCorrespondences, 
				ipMvg, identity,
				ipFix, identity,
				debugSetupIdFix);
	}

	public AffineModel3D fitAffine(
			final List<CorrespondingInterestPoints> mvgToFixedCorrespondences,
			final InterestPoints ipMvg,
			final InvertibleRealTransform mvgTform,
			final InterestPoints ipFix,
			final InvertibleRealTransform fixTform,
			int debugSetupIdFix) {
		
		int N = mvgToFixedCorrespondences.size();
		
		if( N < minDataPoints )
			return null;

		final double[][] mvg = new double[3][N];
		final double[][] fix = new double[3][N];
		final double[] tmpS = new double[3];
		final double[] tmpD = new double[3];

		final List<InterestPoint> ptsMvg = ipMvg.getInterestPointsCopy();
		final List<InterestPoint> ptsFix = ipFix.getInterestPointsCopy();
		
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
			mvg[0][i] = rx * mvgPoint.getDoublePosition(0);
			mvg[1][i] = ry * mvgPoint.getDoublePosition(1);
			mvg[2][i] = rz * mvgPoint.getDoublePosition(2);

			final InterestPoint fixPoint = 
					transformPoint(fixTform, ptsFix.get( otherId ), tmpS, tmpD);	
			fix[0][i] = rx * fixPoint.getDoublePosition(0);
			fix[1][i] = ry * fixPoint.getDoublePosition(1);
			fix[2][i] = rz * fixPoint.getDoublePosition(2);
		}

		final double[] w = new double[N];
		Arrays.fill(w, 1.0);

		AffineModel3D model = new AffineModel3D();
		try {
			model.fit(mvg, fix, w);
			return model;
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
			return null;
		}

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
//		System.out.println( distance(tmpSrc, tmpDst));
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

	public List<AffineModel3D> iterativeAffineFit() {
		return iterativeAffineFit(null);
	}
	
	public List<AffineModel3D> iterativeAffineFit(File outputFile) {
		
		int setupIdMoving = 1;
		int setupIdFixed = 4;
		
		AffineTransform3D cumulativeTransform = new AffineTransform3D();
		List<AffineModel3D> affines = new ArrayList<>();
		ArrayList<double[]> allFixedPoints = new ArrayList<>();
		
		PrintWriter writer = null;
		try {
			if (outputFile != null) {
				writer = new PrintWriter(new FileWriter(outputFile));
//				writer.println("setupId,pointId,x,y,z");
				writer.println("x,y,z");
			}

			while (setupIdFixed < setupIds().size()) {

				final ViewId viewIdMoving = new ViewId(0, setupIdMoving);
				final ViewId viewIdFixed = new ViewId(0, setupIdFixed);
				
				final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
				final InterestPoints ipMoving = iMap.get(viewIdMoving).getInterestPointList(detectionName);
				final InterestPoints ipFixed = iMap.get(viewIdFixed).getInterestPointList(detectionName);
				
				if (ipMoving == null || ipFixed == null) {
					System.err.println("Missing interest points for setupIds: " + setupIdMoving + " or " + setupIdFixed);
					setupIdMoving += 3;
					setupIdFixed += 3;
					continue;
				}

//				final List<InterestPoint> ptsFixed = ipFixed.getInterestPointsCopy();
//				for (InterestPoint pt : ptsFixed) {
//					double[] transformedPt = transformPointToArray(cumulativeTransform, pt);
//					allFixedPoints.add(transformedPt);
//					
//					if (writer != null) {
//						writer.printf("%d,%d,%.6f,%.6f,%.6f%n",
//								setupIdFixed, pt.getId(), 
//								transformedPt[0], transformedPt[1], transformedPt[2]);
//					}
//				}
				
				double[] x = new double[3];
				double[] xt = new double[3];
				cumulativeTransform.apply(x, xt);
				writer.printf("%.6f,%.6f,%.6f%n",
					xt[0], xt[1], xt[2]);

				
				final List<CorrespondingInterestPoints> corr = getCorrespondences(viewIdMoving, viewIdFixed);
				
				AffineModel3D model = fitAffine(corr, ipMoving, ipFixed, setupIdFixed);
				
				if (model != null) {
					affines.add(model);
					
					AffineTransform3D modelTransform = fromAffineModel(model);
					cumulativeTransform.preConcatenate(modelTransform);
//					System.out.println(modelTransform);
//					System.out.println(cumulativeTransform);
//					System.out.println("");
				}
				
				setupIdMoving += 3;
				setupIdFixed += 3;
			}
			
		} catch (IOException e) {
			System.err.println("Error writing to output file: " + e.getMessage());
		} finally {
			if (writer != null) {
				writer.close();
			}
		}
		
		return affines;
	}

}
