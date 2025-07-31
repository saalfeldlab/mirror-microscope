package org.janelia.saalfeldlab.mirrormicroscope;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;

public class ResaveToTif {

	public static void main(String[] args) {

		final String root = args[0];
		final String dset = args[1];
		final String out = args[2];

		System.out.println("root  : " + root);
		System.out.println("dset  : " + dset);
		System.out.println("output: " + out);

		N5Reader reader = new N5Factory().openReader(root);
		save(N5Utils.open(reader, dset), dset, out);

		System.out.println("done");
		System.exit(0);
	}

	public static <T extends NumericType<T> & NativeType<T>> void save(CachedCellImg<?, ?> img, String name, String out) {

		ImagePlus imp = ImageJFunctions.wrap((RandomAccessibleInterval<T>)img, name);
		imp.setDimensions(1, (int)img.dimension(2), 1);
		imp.getCalibration().pixelWidth = 0.157;
		imp.getCalibration().pixelHeight = 0.157;
		imp.getCalibration().pixelDepth = 1.0;
		IJ.saveAsTiff(imp, out);
	}

}
