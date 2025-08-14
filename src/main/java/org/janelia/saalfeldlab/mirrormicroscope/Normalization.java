package org.janelia.saalfeldlab.mirrormicroscope;

import net.imglib2.Interval;
import net.imglib2.RealPoint;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.util.Intervals;

public class Normalization {
	
	
	public static double[] minMaxOffsetsPoint(InvertibleRealTransform distortion, RealPoint p) {

	    // Ensure we have at least 3 dimensions
	    if (p.numDimensions() < 3)
	        throw new IllegalArgumentException("Interval must have at least 3 dimensions for z-translation computation");

		RealPoint transformedPoint = new RealPoint( p.numDimensions() );

		// Apply distortion transformation
		distortion.apply( p, transformedPoint );

		// Calculate z-translation (difference in z-coordinate)
		double zTranslation = transformedPoint.getDoublePosition( 2 ) - p.getDoublePosition( 2 );

	    return new double[]{zTranslation, zTranslation};
	}

	public static double[] minMaxOffsetsMiddle(InvertibleRealTransform distortion, Interval interval) {

		final RealPoint middle = new RealPoint(interval.numDimensions());
		for (int i = 0; i < interval.numDimensions(); i++)
			middle.setPosition((interval.realMax(i) - interval.realMin(i)) / 2.0, i);

		return minMaxOffsetsPoint( distortion, middle);
	}
	
	public static double[] minMaxOffsetsCorners(InvertibleRealTransform distortion, Interval interval) {

	    // Ensure we have at least 3 dimensions
	    if (interval.numDimensions() < 3)
	        throw new IllegalArgumentException("Interval must have at least 3 dimensions for z-translation computation");

	    double minZTranslation = Double.POSITIVE_INFINITY;
	    double maxZTranslation = Double.NEGATIVE_INFINITY;

		// only need to check corners
		IntervalIterator iterator = new IntervalIterator( Intervals.createMinMax( 0, 0, 0, 1, 1, 1 ) );
		RealPoint cornerPoint = new RealPoint( interval.numDimensions() );
		RealPoint transformedPoint = new RealPoint( interval.numDimensions() );

		while ( iterator.hasNext() )
		{
			iterator.fwd();
			corner(interval, iterator, cornerPoint);

			// Apply distortion transformation
			distortion.apply( cornerPoint, transformedPoint );

			// Calculate z-translation (difference in z-coordinate)
			double zTranslation = transformedPoint.getDoublePosition( 2 ) - cornerPoint.getDoublePosition( 2 );

		    // Update min/max
	        minZTranslation = Math.min(minZTranslation, zTranslation);
	        maxZTranslation = Math.max(maxZTranslation, zTranslation);
		}

	    return new double[]{minZTranslation, maxZTranslation};
	}

	public static void corner(Interval interval, IntervalIterator cornerIterator, RealPoint p) {

		int nd = interval.numDimensions();
		for ( int i = 0; i < nd; i++ )
		{
			if ( cornerIterator.getLongPosition( i ) > 0 )
				p.setPosition( interval.max( i ), i );
			else
				p.setPosition( interval.min( i ), i );
		}
	}


}
