package micycle.pgs;

import static micycle.pgs.PGS_Conversion.fromPShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.locationtech.jts.algorithm.construct.MaximumInscribedCircle;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.util.GeometricShapeFactory;
import org.tinfour.common.SimpleTriangle;
import org.tinfour.common.Vertex;
import org.tinfour.standard.IncrementalTin;
import org.tinspin.index.PointDistanceFunction;
import org.tinspin.index.PointEntryDist;
import org.tinspin.index.covertree.CoverTree;

import micycle.pgs.commons.FrontChainPacker;
import processing.core.PShape;
import processing.core.PVector;

/**
 * Circle packings of shapes, subject to varying constraints and patterns of
 * tangencies.
 * <p>
 * Each method produces a circle packing with different characteristics using a
 * different technique; for this reason input arguments vary across the methods.
 * <p>
 * The output of each method is a list of PVectors, each representing one
 * circle: (.x, .y) represent the center point and .z represents radius.
 * <p>
 * Where applicable, packings will include circles that overlap with the shape,
 * rather than only including those circles whose center point lies inside the
 * shape.
 * 
 * @author Michael Carleton
 * @since 1.1.0
 *
 */
public final class PGS_CirclePacking {

	/*
	 * Roadmap (see/implement): 'A LINEARIZED CIRCLE PACKING ALGORITHM'? Integrate
	 * methods from github.com/kensmath/CirclePack. 'A circle packing algorithm'
	 * Charles R. Collins, Kenneth Stephenson. 'A note on circle packing' Young Joon
	 * AHN.
	 */

	private PGS_CirclePacking() {
	}

	/**
	 * Generates a circle packing of the input shape, using the inscribed circles
	 * (or incircles) of triangles from a triangulation of the shape.
	 * <p>
	 * Circles in this packing do not overlap and are contained entirely within the
	 * shape. However, not every circle is necessarily tangent to others.
	 * 
	 * @param shape       the shape from which to generate a circle packing
	 * @param points      the number of random points to insert into the
	 *                    triangulation as steiner points. Larger values lead to
	 *                    more circles that are generally smaller.
	 * @param refinements number of times to refine the underlying triangulation.
	 *                    Larger values lead to more circles that are more regularly
	 *                    spaced and sized. 0...3 is a suitable range for this
	 *                    parameter
	 * @return A list of PVectors, each representing one circle: (.x, .y) represent
	 *         the center point and .z represents radius.
	 */
	public static List<PVector> trinscribedPack(PShape shape, int points, int refinements) {
		final List<PVector> steinerPoints = PGS_Processing.generateRandomPoints(shape, points);
		final IncrementalTin tin = PGS_Triangulation.delaunayTriangulationMesh(shape, steinerPoints, true, refinements, true);
		return StreamSupport.stream(tin.triangles().spliterator(), false).filter(filterBorderTriangles).map(t -> inCircle(t))
				.collect(Collectors.toList());
	}

	/**
	 * Generates a random circle packing of the input shape by generating random
	 * points one-by-one and calculating the maximum radius a circle at each point
	 * can have (such that it's tangent to its nearest circle or a shape vertex).
	 * 
	 * <p>
	 * Notably, the {@code points} argument defines the number of random point
	 * attempts (or circle attempts), and not the number of circles in the final
	 * packing output, since a point is rejected if it lies in an existing circle or
	 * whose nearest circle is less than minRadius distance away. In other words,
	 * {@code points} defines the maximum number of circles the packing can have; in
	 * practice, the packing will contain somewhat fewer circles.
	 * 
	 * <p>
	 * Circles in this packing do not overlap and are contained entirely within the
	 * shape. However, not every circle is necessarily tangent to other circles (in
	 * which case, such a circle will be tangent to a shape vertex).
	 * 
	 * @param shape             the shape from which to generate a circle packing
	 * @param points            number of random points to generate (this is not the
	 *                          number of circles in the packing).
	 * @param minRadius         filter (however not simply applied at the end, so
	 *                          affects how the packing operates during packing)
	 * @param triangulatePoints when true, triangulates an initial random point set
	 *                          and uses triangle centroids as the random point set
	 *                          instead; this results in a packing that covers the
	 *                          shape more evenly (particularly when points is
	 *                          small), which is sometimes desirable
	 * @return A list of PVectors, each representing one circle: (.x, .y) represent
	 *         the center point and .z represents radius.
	 */
	public static List<PVector> stochasticPack(final PShape shape, final int points, final double minRadius, boolean triangulatePoints) {

		final CoverTree<PVector> tree = CoverTree.create(3, 2, circleDistanceMetric);
		final List<PVector> out = new ArrayList<>();

		List<PVector> steinerPoints = PGS_Processing.generateRandomPoints(shape, points);
		if (triangulatePoints) {
			final IncrementalTin tin = PGS_Triangulation.delaunayTriangulationMesh(shape, steinerPoints, true, 1, true);
			steinerPoints = StreamSupport.stream(tin.triangles().spliterator(), false).filter(filterBorderTriangles)
					.map(PGS_CirclePacking::centroid).collect(Collectors.toList());
		}

		// Model shape vertices as circles of radius 0, to constrain packed circles
		// within shape edge
		final List<PVector> vertices = PGS_Conversion.toPVector(shape);
		Collections.shuffle(vertices); // shuffle vertices to reduce tree imbalance during insertion
		vertices.forEach(p -> tree.insert(new double[] { p.x, p.y, 0 }, p));

		/**
		 * "To find the circle nearest to a center (x, y), do a proximity search at (x,
		 * y, R), where R is greater than or equal to the maximum radius of a circle."
		 */
		float largestR = 0; // the radius of the largest circle in the tree

		for (PVector p : steinerPoints) {
			final PointEntryDist<PVector> nn = tree.query1NN(new double[] { p.x, p.y, largestR }); // find nearest-neighbour circle

			/**
			 * nn.dist() does not return the radius (since it's a distance metric used to
			 * find nearest circle), so calculate maximum radius for candidate circle using
			 * 2d euclidean distance between center points minus radius of nearest circle.
			 */
			final float dx = p.x - nn.value().x;
			final float dy = p.y - nn.value().y;
			final float radius = (float) (Math.sqrt(dx * dx + dy * dy) - nn.value().z);
			if (radius > minRadius) {
				largestR = (radius >= largestR) ? radius : largestR;
				p.z = radius;
				tree.insert(new double[] { p.x, p.y, radius }, p); // insert circle into tree
				out.add(p);
			}
		}
		return out;
	}

	/**
	 * Generates a random circle packing of tangental circles with varying radii
	 * that overlap the given shape.
	 * <p>
	 * The method name describes the name of the packing algorithm, rather than any
	 * particular characteristic of the circle packing.
	 * <p>
	 * You may set radiusMin = radiusMax for a packing of equal-sized circles using
	 * this approach.
	 * 
	 * @param shape     the shape from which to generate a circle packing
	 * @param radiusMin minimum radius of circles in the packing
	 * @param radiusMax maximum radius of circles in the packing
	 * @return A list of PVectors, each representing one circle: (.x, .y) represent
	 *         the center point and .z represents radius.
	 */
	public static List<PVector> frontChainPack(PShape shape, double radiusMin, double radiusMax) {
		radiusMin = Math.max(1f, Math.min(radiusMin, radiusMax)); // choose min and constrain
		radiusMax = Math.max(1f, Math.max(radiusMin, radiusMax)); // choose max and constrain
		final Geometry g = fromPShape(shape);
		final Envelope e = g.getEnvelopeInternal();
		IndexedPointInAreaLocator pointLocator;

		final FrontChainPacker packer = new FrontChainPacker((float) e.getWidth(), (float) e.getHeight(), (float) radiusMin,
				(float) radiusMax, (float) e.getMinX(), (float) e.getMinY());

		if (radiusMin == radiusMax) {
			// if every circle same radius, use faster contains check
			pointLocator = new IndexedPointInAreaLocator(g.buffer(radiusMax));
			packer.getCircles().removeIf(p -> pointLocator.locate(PGS.coordFromPVector(p)) == Location.EXTERIOR);
		} else {
			pointLocator = new IndexedPointInAreaLocator(g);
			final PreparedGeometry cache = PreparedGeometryFactory.prepare(g);
			final GeometricShapeFactory circleFactory = new GeometricShapeFactory();
			circleFactory.setNumPoints(8); // approximate circles using octagon for intersects check
			packer.getCircles().removeIf(p -> {
				// first test whether shape contains circle center point (somewhat faster)
				if (pointLocator.locate(PGS.coordFromPVector(p)) != Location.EXTERIOR) {
					return false;
				}

				// if center point not in circle, check whether circle overlaps with shape using
				// intersects() (somewhat slower)
				circleFactory.setCentre(PGS.coordFromPVector(p));
				circleFactory.setSize(p.z * 2); // set diameter
				return !cache.intersects(circleFactory.createCircle());
			});
		}

		return packer.getCircles();
	}

	/**
	 * Generates a circle packing consisting of n maximum inscribed circles,
	 * computed iteratively, starting with the biggest circle (the shape's global
	 * maximum inscribed circle).
	 * 
	 * Pack with (the n biggest circles) circles that are the maximum size at each
	 * stage. Starts with . Pack with successive maximumly inscribed circles.
	 * <p>
	 * <b>Warning:</b><br/>
	 * This method is much slower than the other circle packing methods. Processing
	 * with n > 100 may take several seconds.
	 * </p>
	 * 
	 * @param shape     the shape from which to generate a circle packing
	 * @param n         number of maximum inscribed circles to return, starting with
	 *                  the largest inscribed circle
	 * @param tolerance inscribed circle tolerance. A value of a 1 is recommended
	 *                  for a good result. Larger values decrease precision and may
	 *                  reduce how "maximum" each circle is (may also lead to a
	 *                  modest speedup)
	 * @return A list of PVectors, each representing one circle: (.x, .y) represent
	 *         the center point and .z represents radius.
	 */
	public static List<PVector> maximumInscribedPack(PShape shape, int n, double tolerance) {
		tolerance = Math.max(0.5, tolerance);
		Geometry geometry = fromPShape(shape);
		final GeometricShapeFactory shapeFactory = new GeometricShapeFactory();
		shapeFactory.setNumPoints(20);

		final List<PVector> out = new ArrayList<>();

		for (int i = 0; i < n; i++) {
			final MaximumInscribedCircle mic = new MaximumInscribedCircle(geometry, tolerance);
			final double d, x, y;
			x = mic.getCenter().getX();
			y = mic.getCenter().getY();
			d = mic.getRadiusLine().getLength() * 2;
			out.add(new PVector((float) x, (float) y, (float) d / 2));
			shapeFactory.setCentre(new Coordinate(x, y));
			shapeFactory.setSize(d);
			geometry = geometry.difference(shapeFactory.createEllipse());
		}
		return out;
	}

	/**
	 * Generates a tiled circle packing consisting of equal-sized circles arranged
	 * in a square lattice (or grid) bounded by the input shape.
	 * <p>
	 * Circles are included in the packing if they overlap with the given shape.
	 * 
	 * @param shape    the shape from which to generate a circle packing
	 * @param diameter diameter of every circle in the packing
	 * @return A list of PVectors, each representing one circle: (.x, .y) represent
	 *         the center point and .z represents radius.
	 * @see #hexLatticePack(PShape, double)
	 */
	public static List<PVector> squareLatticePack(PShape shape, double diameter) {
		diameter = Math.max(diameter, 0.1);
		final double radius = diameter / 2;

		final Geometry g = fromPShape(shape);
		final Envelope e = g.getEnvelopeInternal();
		// buffer the geometry to use InAreaLocator to test circles for overlap (this
		// works because all circles have the same diameter)
		final IndexedPointInAreaLocator pointLocator = new IndexedPointInAreaLocator(g.buffer(radius * 0.95));
		final double w = e.getWidth() + diameter + e.getMinX();
		final double h = e.getHeight() + diameter + e.getMinY();

		final List<PVector> out = new ArrayList<>();

		for (double x = e.getMinX(); x < w; x += diameter) {
			for (double y = e.getMinY(); y < h; y += diameter) {
				if (pointLocator.locate(new Coordinate(x, y)) != Location.EXTERIOR) {
					out.add(new PVector((float) x, (float) y, (float) radius));
				}
			}
		}
		return out;
	}

	/**
	 * Generates a tiled circle packing consisting of equal-sized circles arranged
	 * in a hexagonal lattice bounded by the input shape.
	 * <p>
	 * Circles are included in the packing if they overlap with the given shape.
	 * 
	 * @param shape    the shape from which to generate a circle packing
	 * @param diameter diameter of every circle in the packing
	 * @return A list of PVectors, each representing one circle: (.x, .y) represent
	 *         the center point and .z represents radius.
	 * @see #squareLatticePack(PShape, double)
	 */
	public static List<PVector> hexLatticePack(PShape shape, double diameter) {
		diameter = Math.max(diameter, 0.1);
		final double radius = diameter / 2d;

		final Geometry g = fromPShape(shape);
		final Envelope e = g.getEnvelopeInternal();
		// buffer the geometry to use InAreaLocator to test circles for overlap (this
		// works because all circles have the same diameter)
		final IndexedPointInAreaLocator pointLocator = new IndexedPointInAreaLocator(g.buffer(radius * 0.95));
		final double w = e.getWidth() + diameter + e.getMinX();
		final double h = e.getHeight() + diameter + e.getMinY();

		final List<PVector> out = new ArrayList<>();

		final double z = radius * Math.sqrt(3); // hex distance between successive columns
		double offset = 0;
		for (double x = e.getMinX(); x < w; x += z) {
			offset = (offset == radius) ? 0 : radius;
			for (double y = e.getMinY() - offset; y < h; y += diameter) {
				if (pointLocator.locate(new Coordinate(x, y)) != Location.EXTERIOR) {
					out.add(new PVector((float) x, (float) y, (float) radius));
				}
			}
		}
		return out;
	}

	/**
	 * Computes the incircle of a triangle; the largest circle contained in a given
	 * triangle.
	 * 
	 * @param t triangle
	 * @return PVector, where x & y represent incenter coordinates, and z represents
	 *         incircle radius.
	 */
	private static PVector inCircle(SimpleTriangle t) {
		final double a = t.getEdgeA().getLength();
		final double b = t.getEdgeB().getLength();
		final double c = t.getEdgeC().getLength();

		double inCenterX = t.getVertexA().x * a + t.getVertexB().x * b + t.getVertexC().x * c;
		inCenterX /= (a + b + c);
		double inCenterY = t.getVertexA().y * a + t.getVertexB().y * b + t.getVertexC().y * c;
		inCenterY /= (a + b + c);

		final double s = (a + b + c) / 2; // semiPerimeter

		final double r = Math.sqrt(((s - a) * (s - b) * (s - c)) / s);

		return new PVector((float) inCenterX, (float) inCenterY, (float) r);
	}

	private static PVector centroid(SimpleTriangle t) {
		final Vertex a = t.getVertexA();
		final Vertex b = t.getVertexB();
		final Vertex c = t.getVertexC();
		double x = a.x + b.x + c.x;
		x /= 3;
		double y = a.y + b.y + c.y;
		y /= 3;
		return new PVector((float) x, (float) y);
	}

	/**
	 * Reduce the 2D nearest circle problem to the 3D point problem using this
	 * distance metric. From https://stackoverflow.com/a/21975136/
	 */
	private static final PointDistanceFunction circleDistanceMetric = (p1, p2) -> {
		final double dx = p1[0] - p2[0];
		final double dy = p1[1] - p2[1];
		return Math.sqrt(dx * dx + dy * dy) + Math.abs(p1[2] - p2[2]); // negative if inside
	};

	/**
	 * A streams filter to remove triangulation triangles that share at least one
	 * edge with the shape edge.
	 */
	private static final Predicate<SimpleTriangle> filterBorderTriangles = t -> t.getContainingRegion() != null
			&& !t.getEdgeA().isConstrainedRegionBorder() && !t.getEdgeB().isConstrainedRegionBorder()
			&& !t.getEdgeC().isConstrainedRegionBorder();

}
