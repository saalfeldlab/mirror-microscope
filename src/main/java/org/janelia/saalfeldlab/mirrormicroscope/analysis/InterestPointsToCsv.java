package org.janelia.saalfeldlab.mirrormicroscope.analysis;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.mirrormicroscope.CameraModel;
import org.janelia.saalfeldlab.mirrormicroscope.OpticalModel;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;

public class InterestPointsToCsv {

	public static void main(String[] args) {

//		System.out.println( print(new double[]{1.0, 2.2, 3.3}));

		URI uri = URI.create(args[0]);
		String detectionNameMvg = args[1];
		String detectionNameFix = args[2];
		int id = Integer.parseInt(args[3]);

		SpimData2 data;
		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
			return;
		}

		final InterestPointsToCsv alg = new InterestPointsToCsv(uri, detectionNameMvg, detectionNameFix, new ViewId(0,id));
		alg.xmlURI = uri;
		alg.run();
	}

	URI xmlURI;
	SpimData2 data;

	String detectionNameMvg;
	String detectionNameFix;
	ViewId viewId;

	CameraModel cameraModel;
	Interval itvl;

	final double radius = OpticalModel.R;

	public InterestPointsToCsv( URI uri, String detectionNameMvg,  String detectionNameFix, ViewId viewId) {

		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
		}

		cameraModel = CameraModel.fromArgs(3, "1-8");
		itvl = new FinalInterval(new long[]{4096, 2560, 3300});

		this.detectionNameMvg = detectionNameMvg;
		this.detectionNameFix = detectionNameFix;
		this.viewId = viewId;
	}

	public void run() {

		final HashMap<ViewId, List<InterestPoint>> points = new HashMap<>();
		final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
		final InterestPoints ipM = iMap.get(viewId).getInterestPointList(detectionNameMvg);
		final InterestPoints ipF = iMap.get(viewId).getInterestPointList(detectionNameFix);

		final List<InterestPoint> ptsM = ipM.getInterestPointsCopy();
		final List<InterestPoint> ptsF = ipF.getInterestPointsCopy();

		if (ptsM.size() != ptsF.size()) {
			System.out.println("points have different lengths " + ptsM.size() + " vs " + ptsF.size());
		}

		for (int i = 0; i < ptsM.size(); i++) {
			System.out.println(String.format("%s,%s,%s,%s", 
					quotes("Pt-"+i),
					quotes("true"),
					print(ptsM.get(i).getL()),
					print(ptsF.get(i).getL())
			));
		}
	}

	public static String print(final double[] x) {
		return Arrays.stream(x)
			.mapToObj(Double::toString)
			.map(InterestPointsToCsv::quotes)
			.collect(Collectors.joining(","));
	}

	public static String quotes(String x) {
		return "\""+x+"\"";
	}

}
