package org.janelia.saalfeldlab.mirrormicroscope;

import java.util.stream.DoubleStream;

public class CameraUtils {

    public static final double RX = 0.157;
    public static final double RY = 0.157;

    public static final int SIZE_X_PIX = 4096;
    public static final int SIZE_Y_PIX = 2560;
    public static final int SIZE_Z_PIX = 4101;

    public static final int ROWS_PER_CAM = 4;
    public static final int NUM_CAMS = 10;
    public static final double WIDTH_NO_OVERLAP_UM = 328;
    public static final double WIDTH_UM = 402;

    public static final double[] xPositionsPhysical = DoubleStream
    		.iterate(
    				-(WIDTH_NO_OVERLAP_UM) * ROWS_PER_CAM * NUM_CAMS / 2, 
					x -> x + ROWS_PER_CAM * WIDTH_NO_OVERLAP_UM)
    		.limit(NUM_CAMS)
    		.toArray();

    public static final double[] yPositionsPhysical = DoubleStream
    		.generate( () -> (-SIZE_X_PIX / 2) * RX )
    		.limit(NUM_CAMS)
    		.toArray();

    public static double[] offset( final int setupId ) {

		int row = setupId / 10;
		int camera = (row / 4) % 10;
		return new double[] {
				xPositionsPhysical[camera],
				yPositionsPhysical[camera],
				0
		};
    }


}
