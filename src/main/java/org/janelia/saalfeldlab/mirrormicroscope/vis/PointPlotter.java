package org.janelia.saalfeldlab.mirrormicroscope.vis;


import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Mostly written by Claude code and not reviewed carefully -JB
 */
public class PointPlotter extends JPanel {

    private double[][] points;
    private int[] categories;
    private Map<Integer, Color> colorMap;
    private int width, height;
    private double minX, maxX, minY, maxY;
    private double scale;
    private static final int PADDING = 50;
    private static final int CIRCLE_RADIUS = 6;

    public PointPlotter(double[][] points, int[] categories) {
    	this( points, categories, 400, 1200);
    }
    
    public PointPlotter(double[][] points, int[] categories,
    		int width, int height) {
        if (points.length != categories.length) {
            throw new IllegalArgumentException("Points and categories must ha\n"
            		+ "\n"
            		+ "import javax.swing.*;ve same length");
        }
        
        this.points = points;
        this.categories = categories;
        this.width = width;
        this.height = height;

        this.colorMap = generateColorMap();
        calculateBounds();
        setPreferredSize(new Dimension(width, height));
        setBackground(Color.WHITE);
    }
    
    private Map<Integer, Color> generateColorMap() {
        Set<Integer> uniqueCategories = new HashSet<>();
        for (int cat : categories) {
            uniqueCategories.add(cat);
        }
        
        Map<Integer, Color> map = new HashMap<>();
        Color[] colors = {
            Color.RED, Color.BLUE, Color.GREEN, Color.ORANGE, 
            Color.MAGENTA, Color.CYAN, Color.PINK, Color.YELLOW,
            new Color(128, 0, 128), new Color(0, 128, 128)
        };
        
        int idx = 0;
        for (int cat : uniqueCategories) {
            map.put(cat, colors[idx % colors.length]);
            idx++;
        }
        
        return map;
    }
    
    private void calculateBounds() {
        minX = minY = Double.MAX_VALUE;
        maxX = maxY = Double.MIN_VALUE;
        
        for (double[] point : points) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
        }
      
        double xRange = maxX - minX;
        double yRange = maxY - minY;

        // Consider padding when determining scale
		scale = Math.min(
				(width - 2 * PADDING) / (1.2 * xRange),
				(height - 2 * PADDING) / (1.2 * yRange));

    }

    private int scaleX(double x) {
        return PADDING + (int) ((x - minX) * scale);
    }

    private int scaleY(double y) {
        return PADDING + (int) ((y - minY) * scale);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, 
                            RenderingHints.VALUE_ANTIALIAS_ON);
  
        // Draw points
        for (int i = 0; i < points.length; i++) {

            int x = scaleX(points[i][0]);
            int y = scaleY(points[i][1]);

            g2d.setColor(colorMap.get(categories[i]));
            Ellipse2D circle = new Ellipse2D.Double(
                x - CIRCLE_RADIUS, y - CIRCLE_RADIUS, 
                CIRCLE_RADIUS * 2, CIRCLE_RADIUS * 2
            );
            g2d.fill(circle);
        }

    }

    public static void savePlotToPNG(double[][] points, int[] categories, 
                                     String outputPath, int width, int height) 
                                     throws IOException {
        // Create a buffered image
        BufferedImage image = new BufferedImage(width, height, 
                                                BufferedImage.TYPE_INT_ARGB);
        
        // Create a PointPlotter panel
        PointPlotter plotter = new PointPlotter(points, categories);
        plotter.setSize(width, height);
        
        // Get graphics context and paint to it
        Graphics2D g2d = image.createGraphics();
        plotter.paintComponent(g2d);
        g2d.dispose();
        
        // Write to file
        File outputFile = new File(outputPath);
        ImageIO.write(image, "PNG", outputFile);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java PointPlotter <output_path.png>");
            System.exit(1);
        }
        
        String outputPath = args[0];
        
        // Example usage
        double[][] points = {
            {1.0, 2.0}, {2.0, 3.5}, {3.0, 1.5}, {4.0, 4.0},
            {1.5, 3.0}, {2.5, 2.0}, {3.5, 3.5}, {4.5, 2.5},
            {0.5, 1.0}, {5.0, 4.5}
        };
        
        int[] categories = {0, 1, 0, 1, 2, 2, 1, 0, 2, 1};
        
        try {
            savePlotToPNG(points, categories, outputPath, 800, 600);
            System.out.println("Plot saved to: " + outputPath);
        } catch (IOException e) {
            System.err.println("Error saving plot: " + e.getMessage());
            System.exit(1);
        }
    }

}