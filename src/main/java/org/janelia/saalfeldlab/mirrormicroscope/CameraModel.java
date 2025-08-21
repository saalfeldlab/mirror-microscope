package org.janelia.saalfeldlab.mirrormicroscope;

import java.util.Arrays;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import net.imglib2.realtransform.ScaleAndTranslation;

public class CameraModel {

	private static final int[] ALL_CAMERAS = new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

	public static final double BASE_RX = 0.157;
	public static final double BASE_RY = 0.157;
	public static final double BASE_RZ = 1.0;

	public static final int SIZE_X_PIX = 4096;
	public static final int SIZE_Y_PIX = 2560;

	public static final int TOTAL_NUM_CAMERAS = 10;

	public static final double WIDTH_NO_OVERLAP_UM = 328;
	public static final double WIDTH_UM = 402;

	public final double CAMERA_FOV;
	public final double TOTAL_FOV;

	public final double[] xPositionsPhysical;
	public final double[] yPositionsPhysical;

	private final int cameraRepeatsPerRow;
	private final int numVirtualCameraStack;

	private int[] activeCameras;

	public final double rx;
	public final double ry;
	public final double rz;

	public CameraModel(final int numVirtualCameraStack,
			final int cameraRepeatsPerRow,
			final double[] res, 
			final int[] activeCameras) {

		this.cameraRepeatsPerRow = cameraRepeatsPerRow;
		this.numVirtualCameraStack = numVirtualCameraStack;
		this.activeCameras = activeCameras;

		rx = res[0];
		ry = res[1];
		rz = res[2];

		CAMERA_FOV = numVirtualCameraStack * WIDTH_NO_OVERLAP_UM;
		TOTAL_FOV = (TOTAL_NUM_CAMERAS - 1) * CAMERA_FOV + WIDTH_UM;

		// fixed positions of cameras
		xPositionsPhysical = DoubleStream
				.generate(() -> (-SIZE_X_PIX / 2) * rx)
				.limit(TOTAL_NUM_CAMERAS)
				.toArray();

		yPositionsPhysical = DoubleStream
				.iterate(
						(TOTAL_FOV / 2),
						x -> x - CAMERA_FOV)
				.limit(TOTAL_NUM_CAMERAS)
				.toArray();	
	}

	public CameraModel(final int cameraRepeatsPerRow, final int[] activeCameras) {
		this(4, cameraRepeatsPerRow, new double[]{BASE_RX, BASE_RY, BASE_RZ}, activeCameras);
	}

	public CameraModel(final int cameraRepeatsPerRow, final double[] res) {
		this(4, cameraRepeatsPerRow, res, ALL_CAMERAS);
	}

	public CameraModel(final int cameraRepeatsPerRow) {
		this(4, cameraRepeatsPerRow, new double[]{BASE_RX, BASE_RY, BASE_RZ}, ALL_CAMERAS);
	}

	public int setupToRow(final int setupId) {
		return setupId / cameraRepeatsPerRow;
	}

	public int setupToColumn(final int setupId) {
		return setupId % cameraRepeatsPerRow;
	}

	public int setupToCamera(final int setupId) {
		final int row = setupToRow(setupId);
		final int cameraIndex = (row / numVirtualCameraStack) % cameraRepeatsPerRow;
		final int cameraId = activeCameras[cameraIndex % activeCameras.length];
		return cameraId;
	}

	/**
	 * Pixel to physical coordinates
	 * 
	 * @return the camera transform
	 */
	public ScaleAndTranslation cameraToImage(int cameraId) {
		return new ScaleAndTranslation(
				new double[]{rx, ry, rz},
				position(cameraId));
	}

	public ScaleAndTranslation imageToCamera(int cameraId) {
		return cameraToImage(cameraId).inverse();
	}

	public double[] position(final int cameraId) {
		return new double[]{ xPositionsPhysical[cameraId], yPositionsPhysical[cameraId], 0 };
	}

	public static void main(String[] args) {

		final int[] activeCameras = IntStream.rangeClosed(1, 8).toArray();

		final CameraModel cm = new CameraModel(13, activeCameras);
		setups( args ).forEach( setupId -> {
			System.out.println("setupId  : " + setupId);
			System.out.println("row      : " + cm.setupToRow(setupId));
			System.out.println("column   : " + cm.setupToColumn(setupId));
			final int cameraId = cm.setupToCamera( setupId );
			System.out.println("cameraId : " + cameraId);
			System.out.println("position : " + Arrays.toString(cm.position(cameraId)));
			System.out.println("");
		});

		System.out.println("done");
	}

	private static IntStream setups(String[] range) {

		return Arrays.stream(range).flatMapToInt( s -> {
			if( s.contains("-"))
				return parseRange(s);
			else
				return IntStream.of(Integer.parseInt(s));
		});
	}

	private static IntStream parseRange(String range) {

		String[] startEnd = range.split("-");
		int startInclusive = Integer.parseInt(startEnd[0]);
		int endInclusive = Integer.parseInt(startEnd[1]);
		return IntStream.rangeClosed(startInclusive, endInclusive);
	}

}
