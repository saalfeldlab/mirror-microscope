package org.janelia.saalfeldlab.mirrormicroscope.analysis;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.saalfeldlab.mirrormicroscope.CameraModel;
import org.janelia.saalfeldlab.mirrormicroscope.FieldCorrection;
import org.janelia.saalfeldlab.mirrormicroscope.Normalization;
import org.janelia.saalfeldlab.mirrormicroscope.OpticalModel;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.Translation3D;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

public class TransformInterestPoints {

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

		final TransformInterestPoints alg = new TransformInterestPoints(uri, detectionName);
		alg.xmlURI = uri;
		alg.run();
	}

	URI xmlURI;
	SpimData2 data;

	String detectionName;

	CameraModel cameraModel;
	Interval itvl;

	final double radius = OpticalModel.R;

	public TransformInterestPoints( URI uri, String detectionName ) {

		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
		}

		cameraModel = CameraModel.fromArgs(3, "1-8");
		itvl = new FinalInterval(new long[]{4096, 2560, 3300});

		this.detectionName = detectionName;
	}

	public TransformInterestPoints() {
		this(null,null);
	}

	public void run() {

//		setupIds().forEach(s -> {
//			final int c = setupToCamera(s);
//			System.out.println("setup: " + s + " camera: " + c);
//		});

		final HashMap< ViewId, List< InterestPoint > > points = new HashMap<>();

		String parameters = "";

		int nc = 3;
		int nr = 32;
		int N = nr * nc;

		for (int id = 0; id < N; id++) {
	
			System.out.println("id: " + id);
			final ViewId viewId = new ViewId(0,id);
			final InvertibleRealTransformSequence tform = totalDistortionCorrectionTransform(id);

			final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
			final InterestPoints ip = iMap.get(viewId).getInterestPointList(detectionName);
			parameters = ip.getParameters();
			List<InterestPoint> pts = ip.getInterestPointsCopy();

			final ArrayList<InterestPoint> transformedPts = new ArrayList<>(pts.size());
			for (InterestPoint p : pts) {
				double[] tmpB = new double[3];
				transformedPts.add(transformPoint(tform, p));
			}

			points.put(viewId, transformedPts);

		}

		final String newName = detectionName + "-transformed";
		InterestPointTools.addInterestPoints(data, newName, points, "transformed: " + parameters);

		XmlIoSpimData2.saveInterestPointsInParallel(data);
		new XmlIoSpimData2().save( data, xmlURI );
	}
	
	public static InterestPoint transformPoint( InvertibleRealTransform tform, InterestPoint pt) {

		final double[] out = new double[3];
		tform.apply(pt.getL(), out);
		return new InterestPoint(pt.getId(), out);
	}

	private static int setupToCamera(int i) {
		return (i / 12) + 1;
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

	public InvertibleRealTransformSequence totalDistortionCorrectionTransform(int setupId) {

		final int cameraId = setupToCamera(setupId);
		InvertibleRealTransformSequence tform = FieldCorrection.concatenate(
				cameraModel.cameraToImage(cameraId),
				new OpticalModel(radius).distortionTransform(true),
				cameraModel.imageToCamera(cameraId));

		final double[] minMax = Normalization.minMaxOffsetsCorners(tform, itvl);
		addNormalizationOffset(tform, minMax);
		return tform;
	}
	
	private void addNormalizationOffset( InvertibleRealTransformSequence totalDistortion, double[] minMax ) {
        final double min = minMax[0];
        final double max = minMax[1];
        final double d = (min + max) / 2.0;
		totalDistortion.add( new Translation3D(new double[] {0, 0, -d}) );
	}

}
