package org.janelia.saalfeldlab.mirrormicroscope.analysis;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;

public class TilePositions {

	public static void main(String[] args) {

		URI uri = URI.create(args[0]);

		SpimData2 data;
		try {
			data = new XmlIoSpimData2().load(uri);
		} catch (SpimDataException e) {
			e.printStackTrace();
			return;
		}

		TilePositions tps = new TilePositions(data);
//		tps.overlapPositions();
		tps.firstTileCenter();
	}

	SpimData2 data;
	Interval itvl;

	public TilePositions(SpimData2 data) {
		this.data = data;
		itvl = new FinalInterval(new long[]{4096, 2560, 3300});
	}
	
	public void firstTileCenter() {

		final int time = 0;
		final int setupId = 94;
		final ViewId viewIdMvg = new ViewId(time, 1);
		
		ViewRegistrations regs = data.getViewRegistrations();
		AffineTransform3D model = regs.getViewRegistration(time, setupId).getModel();

		double[] tileCenter = new double[] {
				midpoint(itvl,0), midpoint(itvl,1), midpoint(itvl,2)
		};
		double[] tileCenterWorld = new double[3];

//		System.out.println(model);
//		System.out.println(Arrays.toString(tileCenter));
		
		model.apply(tileCenter, tileCenterWorld);
		System.out.println(Arrays.toString(tileCenterWorld));

	}

	public void overlapPositions() {
		
		System.out.println("mvgId,fixId,overlap-mid-x,overlap-mid-y,overlap-mid-z");

		final int time = 0;
		tilePairs().stream().forEach( p -> {

			final int id1 = p[0];
			final int id2 = p[1];
			final ViewId viewIdMvg = new ViewId(time, id1);
			final ViewId viewIdFix = new ViewId(time, id2);	

			SimpleBoundingBoxOverlap<ViewId> bboxOverlap = new SimpleBoundingBoxOverlap<>(data);
			RealInterval overlap = bboxOverlap.getOverlapInterval(viewIdMvg, viewIdFix);

			System.out.println( String.format("%d,%d,%f,%f,%f", 
					id1, id2, 
					midpoint(overlap,0), midpoint(overlap,1), midpoint(overlap,2) ));
		});

	}

	public double midpoint(RealInterval itvl, int dim) {
		return itvl.realMin(dim) + (itvl.realMax(dim) - itvl.realMin(dim)) / 2.0;
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

}
