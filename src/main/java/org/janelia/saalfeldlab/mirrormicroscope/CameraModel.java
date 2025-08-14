package org.janelia.saalfeldlab.mirrormicroscope;

import java.util.stream.DoubleStream;

import net.imglib2.realtransform.ScaleAndTranslation;

public class CameraModel {

	public static final double RX = 0.157;
	public static final double RY = 0.157;
	public static final double RZ = 1.0;

	public static final int SIZE_X_PIX = 4096;
	public static final int SIZE_Y_PIX = 2560;
	public static final int SIZE_Z_PIX = 4101;

	public static final int ROWS_PER_CAM = 4;
	public static final int NUM_CAMS = 10;
	public static final double WIDTH_NO_OVERLAP_UM = 328;
	public static final double WIDTH_UM = 402;

	public static final double CAMERA_FOV = ROWS_PER_CAM * WIDTH_NO_OVERLAP_UM;
	public static final double TOTAL_FOV = (NUM_CAMS - 1) * CAMERA_FOV + WIDTH_UM;

	public static final double[] varyingPositionsPhysical = DoubleStream
			.iterate(
					(TOTAL_FOV / 2),
					x -> x - CAMERA_FOV)
			.limit(NUM_CAMS)
			.toArray();

	public static final double[] constantPositionsPhysical = DoubleStream
			.generate(() -> (-SIZE_X_PIX / 2) * RX)
			.limit(NUM_CAMS)
			.toArray();

	public static double[] position(final int setupId) {

		int row = setupId / 10;
		int camera = (row / 4) % 10;

		return new double[]{
				constantPositionsPhysical[camera],
				varyingPositionsPhysical[camera],
				0
		};
	}

	/**
	 * Pixel to physical coordinates
	 * 
	 * @return the camera transform
	 */
	public static ScaleAndTranslation cameraToImage(int cameraId) {
		return new ScaleAndTranslation(
				new double[]{RX, RY, RZ},
				CameraModel.position(cameraId));
	}

	public static ScaleAndTranslation imageToCamera(int cameraId) {
		return cameraToImage(cameraId).inverse();
	}

}
