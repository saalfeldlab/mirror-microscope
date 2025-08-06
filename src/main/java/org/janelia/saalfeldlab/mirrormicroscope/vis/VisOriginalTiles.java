package org.janelia.saalfeldlab.mirrormicroscope.vis;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;

public class VisOriginalTiles {

	public static final double[][] FACTORS = new double[][] {
	    { 1, 1, 1 },
	    { 2, 2, 1 },
	    { 4, 4, 2 },
	    { 8, 8, 4 },
	    { 16, 16, 8 }
	};

	public static void main(String[] args) {

		// final int N = 600;
		final int N = 3;
		final String dsetPattern = "setup%d";
		final String levelPattern = "%d";
		final double[] baseResolution = new double[]{0.157, 0.157, 1.0};

		final String basePath = args[0];
		final String offsetsFile = args[1];

		final N5Reader n5 = N5Factory.createReader(basePath);

		// load offsets
		final double[][] offsets = loadOffsets(N, offsetsFile);

		BdvOptions opts = BdvOptions.options();
		for (int i = 0; i < N; i++) {

			final String baseDset = String.format(dsetPattern, i);
			final RandomAccessibleIntervalMipmapSource<?> src = createSource(n5, baseDset, levelPattern, baseResolution, offsets[i]);
			final BdvStackSource<?> bdv = BdvFunctions.show(src, opts);
			opts = opts.addTo(bdv);
		}
	}

	private static double[][] loadOffsets( final int N, final String offsetsFile ) {
		
		double[][] offsets = new double[N][];
		int i = 0;
		try (BufferedReader br = new BufferedReader(new FileReader(new File(offsetsFile)))) {

			String line ;
			while ((line = br.readLine()) != null && i < N) {

				offsets[i++] = Arrays.stream(line.split(","))
						.mapToDouble(Double::parseDouble)
						.toArray();
			}

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return offsets;
	}
	
	public static <T extends NativeType<T> & NumericType<T>> RandomAccessibleIntervalMipmapSource<T> createSource( 
			N5Reader n5, String base, String levelPattern, 
			double[] resolution,
			double[] offset ) {

		List<RandomAccessibleInterval<T>> mipmapLevels = new ArrayList<>();
		List<AffineTransform3D> transforms = new ArrayList<>();
		
		// Use FACTORS to determine number of levels and scale factors
		for (int level = 0; level < FACTORS.length; level++) {
			String datasetPath = base + "/" + String.format(levelPattern, level);
			
			if (!n5.exists(datasetPath)) {
				break;
			}

			// 2. load a RandomAccessibleInterval for each level
			RandomAccessibleInterval<T> img = N5Utils.open(n5, datasetPath);
			mipmapLevels.add(img);
			
			// Get scale factors from FACTORS array
			double[] scaleFactors = FACTORS[level];
			
			// Create transform for this level
			AffineTransform3D transform = new AffineTransform3D();
			transform.set(
				resolution[0] * scaleFactors[0], 0, 0, offset[0],
				0, resolution[1] * scaleFactors[1], 0, offset[1],
				0, 0, resolution[2] * scaleFactors[2], offset[2]
			);
			transforms.add(transform);
		}
		
		if (mipmapLevels.isEmpty()) {
			return null;
		}

		// 3. build a RandomAccessibleIntervalMipmapSource using the given resolution and offset
		RandomAccessibleInterval<T>[] mipmapArray = mipmapLevels.toArray(new RandomAccessibleInterval[0]);
		AffineTransform3D[] transformArray = transforms.toArray(new AffineTransform3D[0]);

		FinalVoxelDimensions voxDims = new FinalVoxelDimensions("um", 
				transformArray[0].get(0,0),
				transformArray[0].get(1,1),
				transformArray[0].get(2,2));

		return new RandomAccessibleIntervalMipmapSource(
			mipmapArray,
			(T) mipmapLevels.get(0).getType().createVariable(),
			transformArray,
			voxDims,
			base,
			true
		);
	}

}
