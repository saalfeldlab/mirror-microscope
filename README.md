# mirror-microscope

Resources for processing images acquired by the Keller lab mirror microscope. 

## Transformation model

The model of the forward distortion is such that the "z" position of a pixel changes
as a function of its position relative to the mirror.

$$ 
z_d = z + (R - \sqrt(R^2 - r^2))
$$

To correct for this distortion, we simply apply the inverse of the transformation above:

$$ 
z = z_d - (R - \sqrt(R^2 - r^2))
$$

where $r = x^2 + y^2$, and $x$ and $y$ are the physical coordinates of the camera pixel,
and $R$ is a constant related to the optical system (radius of curvature of the mirror).

The next section describes how to compute the physical coordinates of a given pixel 
for a given camera.

## Camera model / location

The ten cameras are "stacked" along the y-dimension. Camera 1 is at the bottom (negative y)
and camera 10 is at the top (positive y). The physical extent of each camera in y is 402 um. 
The spacing between the top of one camera and the top of an adjacent camera is 1312um.
The distance between the top of camera 10 and the bottom of camera 1 is `12210um = (9*1312) + 402`.


Camera images comprise 4096 pixels in x and 2560 pixels in y.
x- and y- resolution are equal to 0.157um = (402 um / 2560 pix).


## Term

* *"horizontal"* refers to the y-dimension
* *"verical"* refers to the y-dimension
