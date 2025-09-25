package org.janelia.saalfeldlab.mirrormicroscope;

import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.distortion.SphericalCurvatureZDistortion;

public class OpticalModel {

	public static final double M0 = 0.02891;  // nominal magnification (image to object)
	public static final double M1 = 8.318e-9; // magnification distortion
//	public static final double R = 47.14 * 1000; // um

	public static final double R = 34886.3436009136; // um, scientifically estimated, based on data! <3

	public final double radius;

	public OpticalModel(final double radius) {
		this.radius = radius;
	}

	public OpticalModel() {
		this.radius = R;
	}

	public static Scale3D imageToObject() {
		return new Scale3D(M0, M0, 1);
	}

	public static Scale3D objectToImage() {
		return imageToObject().inverse();
	}

	public InvertibleRealTransform distortionTransform(boolean inverse) {
		if (inverse)
			return new SphericalCurvatureZDistortion(3, 2, radius).inverse();
		else
			return new SphericalCurvatureZDistortion(3, 2, radius);
	}

}
