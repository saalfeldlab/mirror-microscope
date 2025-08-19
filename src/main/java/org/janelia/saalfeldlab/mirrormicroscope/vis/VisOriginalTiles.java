package org.janelia.saalfeldlab.mirrormicroscope.vis;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

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

	public static final double[][] FACTORS = new double[][]{
	    { 1, 1, 1 },
	    { 2, 2, 1 },
	    { 4, 4, 2 },
	    { 8, 8, 4 },
	    { 16, 16, 8 }
	};

//	public static final double[][] FACTORS = new double[][]{
//	    { 1, 1, 1 },
//	    { 2, 2, 2 },
//	    { 4, 4, 4 },
//	    { 8, 8, 8 },
//	    { 16, 16, 16 },
//	};

	public static void main(String[] args) {

//		final int[] indexList = {
//				340, 341, 342,
//				350, 351, 352, 
//				360, 361, 362,
//				370, 371, 372,
//				380, 381, 382,
//				390, 301, 392,
//				400, 401, 402,
//		};

//		final int[] indexList = IntStream.range(199, 401).toArray();
//		final int[] indexList = IntStream.range(390, 410).toArray();
//		final int[] indexList = IntStream.range(0, 200).toArray();
//		final int[] indexList = IntStream.range(0, 200).toArray();
//		final int[] indexList = IntStream.range(0, 600).toArray();
		final int[] indexList = new int[] {392, 402};
		final int N = indexList.length;


		final String basePath = args[0];
		final String offsetsFile = args[1];
		final String dsetPattern = args[2];
		final String levelPattern = args[3];

//		final String dsetPattern = "setup%d/timepoint0";
//		final String levelPattern = "s%d";

		final double[] baseResolution = new double[]{0.157, 0.157, 1.0};

		final N5Reader n5 = N5Factory.createReader(basePath);
		System.out.println("n5: " + n5);

		// load offsets
		final double[][] offsets = loadOffsets(600, offsetsFile);

		BdvOptions opts = BdvOptions.options().numRenderingThreads(48);
		for (int j = 0; j < N; j++) {

			final int i = indexList[j];
			final String baseDset = String.format(dsetPattern, i);
			final RandomAccessibleIntervalMipmapSource<?> src = createSource(n5, baseDset, levelPattern, baseResolution, offsets[i]);
			final BdvStackSource<?> bdv = BdvFunctions.show(src, opts);
			bdv.setDisplayRange(0, 1000);
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

			final String datasetPath = base + "/" + String.format(levelPattern, level);
			System.out.println("dset path: " + datasetPath);
			
			if (!n5.exists(datasetPath)) {
				System.out.println("  does not exist, breaking");
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
				scaleFactors[0], 0, 0, offset[0],
				0, scaleFactors[1], 0, offset[1],
				0, 0, scaleFactors[2], offset[2]
			);
			AffineTransform3D physicalRes = new AffineTransform3D();
			physicalRes.set(
				resolution[0], 0, 0, 0,
				0, resolution[1], 0, 0,
				0, 0, resolution[2], 0
			);

			transform.preConcatenate(physicalRes);

			transforms.add(transform);
		}
		
		if (mipmapLevels.isEmpty()) {
			System.out.println("  no mipmap levels, breaking");
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
