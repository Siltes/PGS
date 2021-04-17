package micycle.pgs;

import static micycle.pgs.PGS_Conversion.fromPShape;
import static micycle.pgs.PGS_Conversion.toPShape;
import static processing.core.PConstants.LINES;
import static processing.core.PConstants.ROUND;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.geotools.geometry.jts.JTS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.util.GeometricShapeFactory;

import micycle.pgs.color.RGB;
import micycle.pgs.utility.RandomPolygon;
import processing.core.PConstants;
import processing.core.PShape;
import processing.core.PVector;

/**
 * PGS | Processing Geometry Suite
 * 
 * @author Michael Carleton
 */
public class PGS {

	// TODO check for getCoordinates() in loops (and replace) (if lots of child
	// geometries)
	// TODO use LinearRingIterator when possible (refactor)
	// TODO https://ignf.github.io/CartAGen/docs/algorithms.html
	// TODO take into account strokweight (buffer by stroke amount?)
	// TODO maintain pshape fill, etc on output
	// see https://github.com/IGNF/CartAGen
	// see https://ignf.github.io/CartAGen/docs/algorithms/others/spinalize.html

	// remove ruppertTriangle
	// mitered offset type
	// conversion tests
	// apollonus method
	// SmallestEnclosingCircle
	// add balaban?

	/**
	 * Calling Polygon#union repeatedly is one way to union several Polygons
	 * together. But here’s a trick that can be significantly faster (seconds rather
	 * than minutes) – add the Polygons to a GeometryCollection, then apply a buffer
	 * with zero distance
	 */

	protected static final int CURVE_SAMPLES = 20;

	public static GeometryFactory GEOM_FACTORY = new GeometryFactory(
			new PrecisionModel(PrecisionModel.FLOATING_SINGLE));

	private PGS() {

	}

	/**
	 * Flatten, or merge/union, a shape's children
	 * 
	 * @param shape
	 * @return
	 */
	public static PShape flatten(PShape shape) {
		// TODO iterate over geometries shapes, then create group PShape
		Polygon poly = (Polygon) fromPShape(shape).union().getGeometryN(0);
		return toPShape(poly.getExteriorRing());
	}

	/**
	 * Returns a hole-less version of the shape, or boundary of group of shapes
	 * 
	 * @param shape
	 * @return
	 */
	public static PShape boundary(PShape shape) {
		return toPShape(fromPShape(shape).getBoundary());
	}

	/**
	 * 
	 * @param n    number of vertices
	 * @param xMax
	 * @param yMax
	 * @return
	 */
	public static PShape randomPolygon(int n, double xMax, double yMax) {
		return pshapeFromPVector(RandomPolygon.generateRandomConvexPolygon(n, xMax, yMax));
	}

	/**
	 * Same as getVertices for geometry PShapes; different (subdivides) for circles
	 * etc.
	 * 
	 * @param shape
	 * @return
	 */
	public static PVector[] vertices(PShape shape) {
		Coordinate[] coords = fromPShape(shape).getCoordinates();
		PVector[] vertices = new PVector[coords.length];
		for (int i = 0; i < coords.length; i++) {
			Coordinate coord = coords[i];
			vertices[i] = new PVector((float) coord.x, (float) coord.y);
		}
		return vertices;
	}

	/**
	 * aka envelope
	 * 
	 * @param shape
	 * @return float[] of [X,Y,W,H]
	 */
	public static float[] bounds(PShape shape) {
		// TODO move to ShapeMetrics?
		Envelope e = (Envelope) fromPShape(shape).getEnvelopeInternal();
		return new float[] { (float) e.getMinX(), (float) e.getMinY(), (float) e.getWidth(), (float) e.getHeight() };
	}

	/**
	 * aka envelope
	 * 
	 * @param shape
	 * @return float[] of [X1, Y1, X2, Y2]
	 */
	public static float[] boundCoords(PShape shape) {
		Envelope e = (Envelope) fromPShape(shape).getEnvelopeInternal();
		return new float[] { (float) e.getMinX(), (float) e.getMinY(), (float) e.getMaxX(), (float) e.getMaxY() };
	}

	/**
	 * Creates a supercircle PShape.
	 * 
	 * @param x      centre point X
	 * @param y      centre point Y
	 * @param width
	 * @param height
	 * @param power  circularity of super circle. Values less than 1 create
	 *               star-like shapes; power=1 is a square;
	 * @return
	 */
	public static PShape createSupercircle(double x, double y, double width, double height, double power) {
		GeometricShapeFactory shapeFactory = new GeometricShapeFactory();
		shapeFactory.setNumPoints(CURVE_SAMPLES * 4);
		shapeFactory.setCentre(new Coordinate(x, y));
		shapeFactory.setWidth(width);
		shapeFactory.setHeight(height);
		return toPShape(shapeFactory.createSupercircle(power));
	}

	/**
	 * Creates a supershape PShape. The parameters feed into the superformula, which
	 * is a simple 2D analytical expression allowing to draw a wide variety of
	 * geometric and natural shapes (starfish, petals, snowflakes) by choosing
	 * suitable values relevant to few parameters.
	 * 
	 * @param x
	 * @param y
	 * @param width
	 * @param m
	 * @param n1
	 * @param n2
	 * @param n3
	 * @return
	 */
	public static PShape createSuperShape(double x, double y, double width, double m, double n1, double n2, double n3) {
		// http://paulbourke.net/geometry/supershape/
		PShape shape = new PShape(PShape.GEOMETRY);
		shape.setFill(true);
		shape.setFill(RGB.WHITE);
		shape.beginShape();

		int points = 180;
		final double angleInc = Math.PI * 2 / points;
		double angle = 0;
		while (angle < Math.PI * 2) {
			double r;
			double t1, t2;

			t1 = Math.cos(m * angle / 4);
			t1 = Math.abs(t1);
			t1 = Math.pow(t1, n2);

			t2 = Math.sin(m * angle / 4);
			t2 = Math.abs(t2);
			t2 = Math.pow(t2, n3);

			r = Math.pow(t1 + t2, 1 / n1);
			if (Math.abs(r) == 0) {
			} else {
				r = width / r;
//				r *= 50;
				shape.vertex((float) (x + r * Math.cos(angle)), (float) (y + r * Math.sin(angle)));
			}

			angle += angleInc;
		}

		shape.endShape();
		return shape;

	}

	/**
	 * Creates an elliptical arc polygon. The polygon is formed from the specified
	 * arc of an ellipse and the two radii connecting the endpoints to the centre of
	 * the ellipse.
	 * 
	 * @param x           centre point X
	 * @param y           centre point Y
	 * @param width
	 * @param height
	 * @param orientation start angle/orientation in radians (where 0 is 12 o'clock)
	 * @param angle       size of the arc angle in radians
	 * @return
	 */
	public static PShape createArc(double x, double y, double width, double height, double orientation, double angle) {
		GeometricShapeFactory shapeFactory = new GeometricShapeFactory();
		shapeFactory.setNumPoints(CURVE_SAMPLES * 2);
		shapeFactory.setCentre(new Coordinate(x, y));
		shapeFactory.setWidth(width);
		shapeFactory.setHeight(height);
		return toPShape(shapeFactory.createArcPolygon(-Math.PI / 2 + orientation, angle));
	}

	/**
	 * Returns an unclosed list of PVector coordinates making up the shape.
	 * 
	 * @param shape
	 * @return
	 */
	public static List<PVector> toPVectorList(PShape shape) {
		final ArrayList<PVector> vertices = new ArrayList<>();
		for (int i = 0; i < shape.getVertexCount(); i++) {
			vertices.add(shape.getVertex(i));
		}
		if (!vertices.isEmpty() && vertices.get(0).equals(vertices.get(vertices.size() - 1))) {
			vertices.remove(vertices.size() - 1);
		}
		return vertices;
	}

	/**
	 * Create a LINES PShape, ready for vertices.
	 * 
	 * @param strokeColor  nullable
	 * @param strokeCap    nullable default = ROUND
	 * @param strokeWeight nullable. default = 2
	 * @return
	 */
	static PShape prepareLinesPShape(Integer strokeColor, Integer strokeCap, Integer strokeWeight) {
		if (strokeColor == null) {
			strokeColor = RGB.PINK;
		}
		if (strokeCap == null) {
			strokeCap = ROUND;
		}
		if (strokeWeight == null) {
			strokeWeight = 2;
		}
		PShape lines = new PShape();
		lines.setFamily(PShape.GEOMETRY);
		lines.setStrokeCap(strokeCap);
		lines.setStroke(true);
		lines.setStrokeWeight(strokeWeight);
		lines.setStroke(strokeColor);
		lines.beginShape(LINES);
		return lines;
	}

	/**
	 * Euclidean distance between two coordinates
	 */
	protected static double distance(Coordinate a, Coordinate b) {
		double deltaX = a.y - b.y;
		double deltaY = a.x - b.x;
		return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
	}

	/**
	 * Euclidean distance between two points
	 */
	protected static double distance(Point a, Point b) {
		double deltaX = a.getY() - b.getY();
		double deltaY = a.getX() - b.getX();
		return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
	}

	protected static double distance(double x1, double y1, double x2, double y2) {
		double deltaX = y1 - y2;
		double deltaY = x1 - y1;
		return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
	}

	private static void removeCollinearVertices(Geometry g) {
		JTS.removeCollinearVertices(g);
	}

	protected static LineString createLineString(PVector a, PVector b) {
		return GEOM_FACTORY.createLineString(new Coordinate[] { coordFromPVector(a), coordFromPVector(b) });
	}

	static Point createPoint(double x, double y) {
		return GEOM_FACTORY.createPoint(new Coordinate(x, y));
	}

	protected static Point pointFromPVector(PVector p) {
		return GEOM_FACTORY.createPoint(new Coordinate(p.x, p.y));
	}

	protected static Coordinate coordFromPoint(Point p) {
		return new Coordinate(p.getX(), p.getY());
	}

	protected static Coordinate coordFromPVector(PVector p) {
		return new Coordinate(p.x, p.y);
	}

	/**
	 * cirumcircle center must lie inside triangle
	 * 
	 * @param a
	 * @param b
	 * @param c
	 * @return
	 */
	private static double smallestSide(Coordinate a, Coordinate b, Coordinate c) {
		double ab = Math.sqrt((b.y - a.y) * (b.y - a.y) + (b.x - a.x) * (b.x - a.x));
		double bc = Math.sqrt((c.y - b.y) * (c.y - b.y) + (c.x - b.x) * (c.x - b.x));
		double ca = Math.sqrt((a.y - c.y) * (a.y - c.y) + (a.x - c.x) * (a.x - c.x));
		return Math.min(Math.min(ab, bc), ca);
	}

	/**
	 * Reflection-based workaround to get the fill color of a PShape (this field is
	 * usually private).
	 */
	protected static final int getPShapeFillColor(final PShape sh) {
		try {
			final java.lang.reflect.Field f = PShape.class.getDeclaredField("fillColor");
			f.setAccessible(true);
			return f.getInt(sh);
		} catch (ReflectiveOperationException cause) {
			throw new RuntimeException(cause);
		}
	}

	/**
	 * Generate a simple polygon (no holes) from the given coordinate list. Used by
	 * randomPolygon().
	 */
	public static PShape pshapeFromPVector(List<PVector> coords) {
		PShape shape = new PShape();
		shape.setFamily(PShape.GEOMETRY);
		shape.setStroke(true);
		shape.setStrokeWeight(2);
		shape.setStroke(0);
		shape.setFill(-123712);
		shape.setFill(true);
		shape.beginShape();

		for (PVector v : coords) {
			shape.vertex(v.x, v.y);
		}

		shape.endShape(PConstants.CLOSE);
		return shape;
	}

	/**
	 * From com.badlogic.gdx
	 */
	private static boolean isClockwise(float[] polygon, int offset, int count) {
		if (count <= 2) {
			return false;
		}
		float area = 0;
		int last = offset + count - 2;
		float x1 = polygon[last], y1 = polygon[last + 1];
		for (int i = offset; i <= last; i += 2) {
			float x2 = polygon[i], y2 = polygon[i + 1];
			area += x1 * y2 - x2 * y1;
			x1 = x2;
			y1 = y2;
		}
		return area < 0;
	}

	/**
	 * Requires a closed hole
	 * 
	 * @param points
	 * @return
	 */
	static boolean isClockwise(List<PVector> points) {
		boolean closed = true;
		if (points.get(0).equals(points.get(points.size() - 1))) {
			closed = false;
			points.add(points.get(0)); // mutate list
		}
		double area = 0;

		for (int i = 0; i < (points.size()); i++) {
			int j = (i + 1) % points.size();
			area += points.get(i).x * points.get(j).y;
			area -= points.get(j).x * points.get(i).y;
		}

		if (!closed) {
			points.remove(points.size() - 1); // undo mutation
		}

		return (area < 0);
	}

	/**
	 * Uniquely encodes two numbers (order-dependent) into a single natural number.
	 */
	static double cantorPairing(double a, double b) {
		a = (a >= 0.0 ? 2.0 * a : (-2.0 * a) - 1.0); // enable negative input values
		b = (b >= 0.0 ? 2.0 * b : (-2.0 * b) - 1.0); // enable negative input values
		return (a + b) * (a + b + 1) / 2 + a;
	}

	static double cantorPairing(int a, int b) {
		return (a + b) * (a + b + 1) / 2 + a; // TODO check /2. integer div?
	}

	/**
	 * Provides convenient iteration of exterior and linear rings (if any) of a JTS
	 * geometry.
	 * 
	 * @author Michael Carleton
	 */
	protected static class LinearRingIterator implements Iterable<LinearRing> {

		private LinearRing[] array;
		private int size;

		/**
		 * Constructs the iterator for the given geometry. The first ring returned by
		 * the iterator is the exterior ring; all other rings (if any) are interior
		 * rings.
		 * 
		 * @param g input geometry
		 */
		public LinearRingIterator(Geometry g) {
			Polygon poly = (Polygon) g;
			this.size = 1 + poly.getNumInteriorRing();
			this.array = new LinearRing[size];
			array[0] = poly.getExteriorRing();
			for (int i = 0; i < poly.getNumInteriorRing(); i++) {
				array[i + 1] = poly.getInteriorRingN(i);
			}
		}

		@Override
		public Iterator<LinearRing> iterator() {
			Iterator<LinearRing> it = new Iterator<LinearRing>() {

				private int currentIndex = 0;

				@Override
				public boolean hasNext() {
					return currentIndex < size;
				}

				@Override
				public LinearRing next() {
					return array[currentIndex++];
				}

				@Override
				public void remove() {
					throw new UnsupportedOperationException();
				}
			};
			return it;
		}
	}

}
