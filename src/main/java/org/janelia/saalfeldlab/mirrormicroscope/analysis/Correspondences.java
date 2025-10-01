package org.janelia.saalfeldlab.mirrormicroscope.analysis;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.mirrormicroscope.CameraModel;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;

public class Correspondences {
	
	public static void main(String[] args) {
		
		String uriString = args[0];
		if (!uriString.startsWith("file:"))
			uriString = "file:" + uriString;

		URI uri = URI.create(uriString);
		String detectionName = args[1];

		int mvgId = -1;
		int fixId = -1;
		if( args.length > 2) {
			mvgId = Integer.parseInt(args[2]);
			fixId = Integer.parseInt(args[3]);
		}

		SpimData2 data;
		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
			return;
		}

		final Correspondences alg = new Correspondences(uri, detectionName);
		alg.setupIdExport = mvgId;
		alg.setupIdOther = fixId;

		alg.run();
	}

	SpimData2 data;
	String detectionName;

	int setupIdExport = -1; 
	int setupIdOther = -1; 

	CameraModel cameraModel;
	Interval itvl;

	public Correspondences( URI uri, String detectionName ) {

		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
		}

		cameraModel = CameraModel.fromArgs(3, "1-8");
		itvl = new FinalInterval(new long[]{4096, 2560, 3300});

		this.detectionName = detectionName;
	}

	public void run() {
		System.out.println("viewId,cameraId,viewIdCorr,cameraIdCorr,x,y,z");
		for( int[] pair : allOrderedTilePairs() ) {

			final ViewId viewIdExport = new ViewId(0, pair[0]);
			final int cameraExport = setupToCamera(viewIdExport.getViewSetupId());

			final ViewId viewIdOther = new ViewId(0, pair[1]);
			final int cameraOther = setupToCamera(viewIdOther.getViewSetupId());
	
			final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
			final InterestPoints ipExport = iMap.get(viewIdExport).getInterestPointList(detectionName);

			if (ipExport == null) {
				System.out.println("no interest points found for " + viewIdExport + " ↔ " + viewIdOther);
				continue;
			}

			List<InterestPoint> ipExportList = ipExport.getInterestPointsCopy();
			List<CorrespondingInterestPoints> allCorrespondences = ipExport.getCorrespondingInterestPointsCopy();
			List<CorrespondingInterestPoints> res = allCorrespondences.stream().filter( c -> {
				return c.getCorrespondingViewId().getViewSetupId() == viewIdOther.getViewSetupId();
			}).collect(Collectors.toList());

			for (CorrespondingInterestPoints cpt : res) {
				final double[] pt = ipExportList.get(cpt.getDetectionId()).getL();
				System.out.println(String.format("%d,%d,%d,%d,%f,%f,%f", 
						viewIdExport.getViewSetupId(), cameraExport,
						viewIdOther.getViewSetupId(), cameraOther,
						pt[0], pt[1], pt[2]));
			}
		}
	}

	public ArrayList<int[]> allOrderedTilePairs() {
		
		ArrayList<int[]> pairs = new ArrayList<>();
		if( setupIdExport >= 0 && setupIdOther >= 0)
		{
			pairs.add(new int[]{ setupIdExport, setupIdOther});
			return pairs;
		}

		int nc = 3;
		int nr = 32;

		for (int r = 0; r < nr; r++) {
			for (int c = 0; c < nc; c++) {

				int setup = setup(r,c,nc);

				// horizontal
				if ( c < nc-1 ) {
					pairs.add(new int[]{
						setup,
						setup(r, c + 1, nc)
					});

					pairs.add(new int[]{
						setup(r, c + 1, nc),
						setup
					});
				}
				
				// vertical
				if ( r < nr-1 ) {
					pairs.add(new int[]{
						setup,
						setup(r + 1, c, nc)
					});
					pairs.add(new int[]{
						setup(r + 1, c, nc),
						setup
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

}
