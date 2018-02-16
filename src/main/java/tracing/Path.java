/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2018 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package tracing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.DoubleStream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.stat.StatUtils;
import org.scijava.vecmath.Color3f;
import org.scijava.vecmath.Point3f;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij3d.Content;
import ij3d.Image3DUniverse;
import ij3d.Pipe;
import pal.math.ConjugateDirectionSearch;
import pal.math.MultivariateFunction;
import tracing.hyperpanes.MultiDThreePanes;
import tracing.util.PointInImage;
import tracing.util.SWCColor;

/**
 * This class represents a traced path. It has methods to manipulate its points
 * (nodes) with sup-pixel accuracy, including drawing them onto threePanes-style
 * image canvases and export as ROIs.
 **/
public class Path implements Comparable<Path> {


	/* Path properties */
	private int points; // n. of nodes
	private int id = -1; // should be assigned by PathAndFillManager
	private int editableNodeIndex = -1;
	private boolean selected;
	private boolean primary = false;
	protected Path startJoins;
	PointInImage startJoinsPoint = null;
	Path endJoins;
	PointInImage endJoinsPoint = null;

	// Paths should always be given a name (since the name
	// identifies them to the 3D viewer)...
	private String name;
	/*
	 * This is a symmetrical relationship, showing all the other paths this one
	 * is joined to...
	 */
	ArrayList<Path> somehowJoins;

	/*
	 * We sometimes impose a tree structure on the Path graph, which is largely
	 * for display purposes. When this is done, we regerated this list. This
	 * should always be a subset of 'somehowJoins'...
	 */
	ArrayList<Path> children;
	/* Spatial calibration definitions */
	protected double x_spacing;
	protected double y_spacing;
	protected double z_spacing;
	protected String spacing_units;

	/* Fitting */
	protected Path fitted; // If this path has a fitted version, this is it.
	protected boolean useFitted = false; // Use the fitted version in preference to this
								// path
	protected Path fittedVersionOf; // If this path is a fitted version of another one,
							// this is the original

	/* Color definitions */
	private Color color;
	protected Color3f realColor;
	protected boolean hasCustomColor = false;

	/* Internal fields */
	private static final int PATH_START = 0;
	private static final int PATH_END = 1;
	private int maxPoints;


	Path(final double x_spacing, final double y_spacing, final double z_spacing, final String spacing_units) {
		this(x_spacing, y_spacing, z_spacing, spacing_units, 128);
	}

	Path(final double x_spacing, final double y_spacing, final double z_spacing, final String spacing_units,
			final int reserve) {
		this.x_spacing = x_spacing;
		this.y_spacing = y_spacing;
		this.z_spacing = z_spacing;
		this.spacing_units = spacing_units;
		points = 0;
		maxPoints = reserve;
		precise_x_positions = new double[maxPoints];
		precise_y_positions = new double[maxPoints];
		precise_z_positions = new double[maxPoints];
		somehowJoins = new ArrayList<>();
		children = new ArrayList<>();
	}

	@Override
	public int compareTo(final Path o) {
		if (id == o.id)
			return 0;
		if (id < o.id)
			return -1;
		return 1;
	}

	public int getID() {
		return id;
	}

	void setID(final int id) {
		this.id = id;
	}

	public Path getStartJoins() {
		return startJoins;
	}

	public PointInImage getStartJoinsPoint() {
		return startJoinsPoint;
	}

	public Path getEndJoins() {
		return endJoins;
	}

	public PointInImage getEndJoinsPoint() {
		return endJoinsPoint;
	}

	public void setName(final String newName) {
		this.name = newName;
	}

	public void setDefaultName() {
		this.name = "Path " + id;
	}

	public String getName() {
		if (name == null) setDefaultName();
		return name;
	}

	protected static String pathsToIDListString(final ArrayList<Path> a) {
		final StringBuffer s = new StringBuffer("");
		final int n = a.size();
		for (int i = 0; i < n; ++i) {
			s.append(a.get(i).getID());
			if (i < n - 1) {
				s.append(",");
			}
		}
		return s.toString();
	}

	public String somehowJoinsAsString() {
		return pathsToIDListString(somehowJoins);
	}

	public String childrenAsString() {
		return pathsToIDListString(children);
	}

	public void setChildren(final Set<Path> pathsLeft) {
		// Set the children of this path in a breadth first fashion:
		children.clear();
		for (final Path c : somehowJoins) {
			if (pathsLeft.contains(c)) {
				children.add(c);
				pathsLeft.remove(c);
			}
		}
		for (final Path c : children)
			c.setChildren(pathsLeft);
	}

	public double getRealLength() {
		double totalLength = 0;
		for (int i = 1; i < points; ++i) {
			final double xdiff = precise_x_positions[i] - precise_x_positions[i - 1];
			final double ydiff = precise_y_positions[i] - precise_y_positions[i - 1];
			final double zdiff = precise_z_positions[i] - precise_z_positions[i - 1];
			totalLength += Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff * zdiff);
		}
		return totalLength;
	}

	public String getRealLengthString() {
		return String.format("%.4f", getRealLength());
	}

	public void createCircles() {
		if (tangents_x != null || tangents_y != null || tangents_z != null || radiuses != null)
			throw new IllegalArgumentException("Trying to create circles data arrays when at least one is already there");
		tangents_x = new double[maxPoints];
		tangents_y = new double[maxPoints];
		tangents_z = new double[maxPoints];
		radiuses = new double[maxPoints];
	}

	protected void setIsPrimary(final boolean primary) {
		this.primary = primary;
	}

	public boolean isPrimary() {
		return primary;
	}

	/*
	 * We call this if we're going to delete the path represented by this object
	 */
	protected void disconnectFromAll() {
		/*
		 * This path can be connected to other ones either if: - this starts on
		 * other - this ends on other - other starts on this - other ends on
		 * this In any of these cases, we need to also remove this from other's
		 * somehowJoins and other from this's somehowJoins.
		 */
		for (final Path other : somehowJoins) {
			if (other.startJoins != null && other.startJoins == this) {
				other.startJoins = null;
				other.startJoinsPoint = null;
			}
			if (other.endJoins != null && other.endJoins == this) {
				other.endJoins = null;
				other.endJoinsPoint = null;
			}
			final int indexInOtherSomehowJoins = other.somehowJoins.indexOf(this);
			if (indexInOtherSomehowJoins >= 0)
				other.somehowJoins.remove(indexInOtherSomehowJoins);
		}
		somehowJoins.clear();
		startJoins = null;
		startJoinsPoint = null;
		endJoins = null;
		endJoinsPoint = null;
	}

	public void setStartJoin(final Path other, final PointInImage joinPoint) {
		setJoin(PATH_START, other, joinPoint);
	}

	public void setEndJoin(final Path other, final PointInImage joinPoint) {
		setJoin(PATH_END, other, joinPoint);
	}

	/*
	 * This should be the only method that links one path to another
	 */
	protected void setJoin(final int startOrEnd, final Path other, final PointInImage joinPoint) {
		if (other == null) {
			throw new IllegalArgumentException("setJoin should never take a null path");
		}
		if (startOrEnd == PATH_START) {
			// If there was an existing path, that's an error:
			if (startJoins != null)
				throw new IllegalArgumentException("setJoin for START should not replace another join");
			startJoins = other;
			startJoinsPoint = joinPoint;
		} else if (startOrEnd == PATH_END) {
			if (endJoins != null)
				throw new IllegalArgumentException("setJoin for END should not replace another join");
			endJoins = other;
			endJoinsPoint = joinPoint;
		} else {
			SNT.log("BUG: unknown first parameter to setJoin");
		}
		// Also update the somehowJoins list:
		if (somehowJoins.indexOf(other) < 0) {
			somehowJoins.add(other);
		}
		if (other.somehowJoins.indexOf(this) < 0) {
			other.somehowJoins.add(this);
		}
	}

	public void unsetStartJoin() {
		unsetJoin(PATH_START);
	}

	public void unsetEndJoin() {
		unsetJoin(PATH_END);
	}

	void unsetJoin(final int startOrEnd) {
		Path other;
		Path leaveAloneJoin;
		if (startOrEnd == PATH_START) {
			other = startJoins;
			leaveAloneJoin = endJoins;
		} else {
			other = endJoins;
			leaveAloneJoin = startJoins;
		}
		if (other == null) {
			throw new IllegalArgumentException("Don't call unsetJoin if the other Path is already null");
		}
		if (!(other.startJoins == this || other.endJoins == this || leaveAloneJoin == other)) {
			somehowJoins.remove(other);
			other.somehowJoins.remove(this);
		}
		if (startOrEnd == PATH_START) {
			startJoins = null;
			startJoinsPoint = null;
		} else {
			endJoins = null;
			endJoinsPoint = null;
		}
	}

	public double getMinimumSeparation() {
		return Math.min(Math.abs(x_spacing), Math.min(Math.abs(y_spacing), Math.abs(z_spacing)));
	}

	public int size() {
		return points;
	}

	public void getPointDouble(final int i, final double[] p) {

		if ((i < 0) || i >= size()) {
			throw new RuntimeException("BUG: getPointDouble was asked for an out-of-range point: " + i);
		}

		p[0] = precise_x_positions[i];
		p[1] = precise_y_positions[i];
		p[2] = precise_z_positions[i];
	}

	public PointInImage getPointInImage(final int i) {

		if ((i < 0) || i >= size()) {
			throw new IllegalArgumentException("getPointInImage() was asked for an out-of-range point: " + i);
		}

		final PointInImage result = new PointInImage(precise_x_positions[i], precise_y_positions[i],
				precise_z_positions[i]);
		result.onPath = this;
		return result;
	}

	public boolean contains(PointInImage pim) {
		return (DoubleStream.of(precise_x_positions).anyMatch(x -> x == pim.x)
				&& DoubleStream.of(precise_y_positions).anyMatch(y -> y == pim.y)
				&& DoubleStream.of(precise_z_positions).anyMatch(z -> z == pim.z));
	}

	/**
	 * Inserts a node at a specified position.
	 *
	 * @param index the (zero-based) index of the position of the new node
	 * @param point the node to be inserted
	 * @throws IllegalArgumentException if index is out-of-range
	 */
	public void addNode(final int index, final PointInImage point) {
		if (index < 0 || index > size()) throw new IllegalArgumentException(
			"addNode() asked for an out-of-range point: " + index);
		// FIXME: This all would be much easier if we were using Collections/Lists
		precise_x_positions = ArrayUtils.add(precise_x_positions, index, point.x);
		precise_y_positions = ArrayUtils.add(precise_y_positions, index, point.y);
		precise_z_positions = ArrayUtils.add(precise_z_positions, index, point.z);
	}

	/**
	 * Removes the specified node if this path has at least two nodes. Does nothing
	 * if this is a single point path.
	 *
	 * @param index
	 *            the zero-based index of the node to be removed
	 * @throws IllegalArgumentException
	 *             if index is out-of-range
	 */
	public void removeNode(final int index) {
		if (points == 1) return;
		if (index < 0 || index >= points) throw new IllegalArgumentException(
			"removeNode() asked for an out-of-range point: " + index);
		// FIXME: This all would be much easier if we were using Collections/Lists
		final PointInImage p = getPointInImage(index);
		precise_x_positions = ArrayUtils.remove(precise_x_positions, index);
		precise_y_positions = ArrayUtils.remove(precise_y_positions, index);
		precise_z_positions = ArrayUtils.remove(precise_z_positions, index);
		points -= 1;
		if (p.equals(startJoinsPoint)) startJoinsPoint = getPointInImage(0);
		if (p.equals(endJoinsPoint) && points > 0) endJoinsPoint = getPointInImage(points-1);
	}

	/**
	 * Assigns a new location to the specified node.
	 *
	 * @param index the zero-based index of the node to be modified
	 * @param destination the new node location
	 * @throws IllegalArgumentException if index is out-of-range
	 */
	public void moveNode(final int index, final PointInImage destination) {
		if (index < 0 || index >= size()) throw new IllegalArgumentException(
			"moveNode() asked for an out-of-range point: " + index);
		precise_x_positions[index] = destination.x;
		precise_y_positions[index] = destination.y;
		precise_z_positions[index] = destination.z;
	}

	/**
	 * Gets the first node index associated with the specified image coordinates.
	 * Returns -1 if no such node was found.
	 *
	 * @param pim the image position (calibrated coordinates)
	 * @return the index of the first node occurrence or -1 if there is no such
	 *         occurrence
	 */
	public int getNodeIndex(final PointInImage pim) {
		for (int i = 0; i < points; ++i) {
			if (Math.abs(precise_x_positions[i] - pim.x) < x_spacing && Math.abs(
				precise_y_positions[i] - pim.y) < y_spacing && Math.abs(
					precise_z_positions[i] - pim.z) < z_spacing)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Gets the index of the closest node associated with the specified world
	 * coordinates.
	 * 
	 * @param x
	 *            the x-coordinates (spatially calibrated image units)
	 * @param y
	 *            the y-coordinates (spatially calibrated image units)
	 * @param z
	 *            the z-coordinates (spatially calibrated image units)
	 * @param within
	 *            sets the search sensitivity. E.g., Setting it to Double.MAX_VALUE
	 *            (or the image's largest dimension) will always return a valid
	 *            index.
	 * @return the index of the closest node to the specified coordinates. Returns
	 *         -1 if no such node was found.
	 */
	public int indexNearestTo(final double x, final double y, final double z, final double within) {

		if (size() < 1)
			throw new IllegalArgumentException("indexNearestTo called on a Path of size() = 0");

		double minimumDistanceSquared = within * within;
		int indexOfMinimum = -1;

		for (int i = 0; i < size(); ++i) {

			final double diff_x = x - precise_x_positions[i];
			final double diff_y = y - precise_y_positions[i];
			final double diff_z = z - precise_z_positions[i];

			final double thisDistanceSquared = diff_x * diff_x + diff_y * diff_y + diff_z * diff_z;

			if (thisDistanceSquared < minimumDistanceSquared) {
				indexOfMinimum = i;
				minimumDistanceSquared = thisDistanceSquared;
			}
		}

		return indexOfMinimum;
	}

	protected int indexNearestTo2D(final double x, final double y, final double within) {
		double minimumDistanceSquared = within * within;
		int indexOfMinimum = -1;
		for (int i = 0; i < size(); ++i) {
			final double diff_x = x - precise_x_positions[i];
			final double diff_y = y - precise_y_positions[i];
			final double thisDistanceSquared = diff_x * diff_x + diff_y * diff_y;
			if (thisDistanceSquared < minimumDistanceSquared) {
				indexOfMinimum = i;
				minimumDistanceSquared = thisDistanceSquared;
			}
		}
		return indexOfMinimum;
	}

	protected int indexNearestTo(final double x, final double y, final double z) {
		return indexNearestTo(x, y, z, Double.MAX_VALUE);
	}

	/**
	 * Gets the position of the node tagged as 'editable', if any.
	 *
	 * @return the index of the point currently tagged as editable, or -1 if no
	 *         such point exists
	 */
	public int getEditableNodeIndex() {
		return editableNodeIndex;
	}

	/**
	 * Tags the specified point position as 'editable'.
	 *
	 * @param index the index of the point to be tagged. Set it to -1 to for no
	 *          tagging
	 */
	public void setEditableNode(int index) {
		this.editableNodeIndex = index;
	}

	protected boolean isBeingEdited() {
		return editableNodeIndex != -1;
	}

	protected void stopBeingEdited() {
		editableNodeIndex = -1;
	}

	public int getXUnscaled(final int i) {
		return (int) Math.round(getXUnscaledDouble(i));
	}

	public int getYUnscaled(final int i) {
		return (int) Math.round(getYUnscaledDouble(i));
	}

	public int getZUnscaled(final int i) {
		return (int) Math.round(getZUnscaledDouble(i));
	}

	public double getXUnscaledDouble(final int i) {
		if ((i < 0) || i >= size())
			throw new IllegalArgumentException("getXUnscaled was asked for an out-of-range point: " + i);
		return precise_x_positions[i] / x_spacing;
	}

	public double getYUnscaledDouble(final int i) {
		if ((i < 0) || i >= size())
			throw new IllegalArgumentException("getYUnscaled was asked for an out-of-range point: " + i);
		return precise_y_positions[i] / y_spacing;
	}

	public double getZUnscaledDouble(final int i) {
		if ((i < 0) || i >= size())
			throw new IllegalArgumentException("getZUnscaled was asked for an out-of-range point: " + i);
		return precise_z_positions[i] / z_spacing;
	}

	/**
	 * Returns an array [3][npoints] of unscaled coordinates (that is, in
	 * pixels).
	 */
	public double[][] getXYZUnscaled() {
		final double[][] p = new double[3][size()];
		for (int i = p[0].length - 1; i > -1; i--) {
			p[0][i] = precise_x_positions[i] / x_spacing;
			p[1][i] = precise_y_positions[i] / y_spacing;
			p[2][i] = precise_z_positions[i] / z_spacing;
		}
		return p;
	}

	/*
	 * FIXME:
	 *
	 * @Override public Path clone() {
	 *
	 * Path result = new Path( points );
	 *
	 * System.arraycopy( x_positions, 0, result.x_positions, 0, points );
	 * System.arraycopy( y_positions, 0, result.y_positions, 0, points );
	 * System.arraycopy( z_positions, 0, result.z_positions, 0, points );
	 * result.points = points; result.startJoins = startJoins;
	 * result.startJoinsIndex = startJoinsIndex; result.endJoins = endJoins;
	 * result.endJoinsIndex = endJoinsIndex;
	 *
	 * if( radiuses != null ) { this.radiuses = new double[radiuses.length];
	 * System.arraycopy( radiuses, 0, result.radiuses, 0, radiuses.length ); }
	 * if( tangents_x != null ) { this.tangents_x = new
	 * double[tangents_x.length]; System.arraycopy( tangents_x, 0,
	 * result.tangents_x, 0, tangents_x.length ); } if( tangents_y != null ) {
	 * this.tangents_y = new double[tangents_y.length]; System.arraycopy(
	 * tangents_y, 0, result.tangents_y, 0, tangents_y.length ); } if(
	 * tangents_z != null ) { this.tangents_z = new double[tangents_z.length];
	 * System.arraycopy( tangents_z, 0, result.tangents_z, 0, tangents_z.length
	 * ); }
	 *
	 * return result; }
	 */

	protected PointInImage lastPoint() {
		if (points < 1)
			return null;
		else
			return new PointInImage(precise_x_positions[points - 1], precise_y_positions[points - 1],
					precise_z_positions[points - 1]);
	}

	private void expandTo(final int newMaxPoints) {

		final double[] new_precise_x_positions = new double[newMaxPoints];
		final double[] new_precise_y_positions = new double[newMaxPoints];
		final double[] new_precise_z_positions = new double[newMaxPoints];
		System.arraycopy(precise_x_positions, 0, new_precise_x_positions, 0, points);
		System.arraycopy(precise_y_positions, 0, new_precise_y_positions, 0, points);
		System.arraycopy(precise_z_positions, 0, new_precise_z_positions, 0, points);
		precise_x_positions = new_precise_x_positions;
		precise_y_positions = new_precise_y_positions;
		precise_z_positions = new_precise_z_positions;
		if (hasRadii()) {
			final double[] new_tangents_x = new double[newMaxPoints];
			final double[] new_tangents_y = new double[newMaxPoints];
			final double[] new_tangents_z = new double[newMaxPoints];
			final double[] new_radiuses = new double[newMaxPoints];
			System.arraycopy(tangents_x, 0, new_tangents_x, 0, points);
			System.arraycopy(tangents_y, 0, new_tangents_y, 0, points);
			System.arraycopy(tangents_z, 0, new_tangents_z, 0, points);
			System.arraycopy(radiuses, 0, new_radiuses, 0, points);
			tangents_x = new_tangents_x;
			tangents_y = new_tangents_y;
			tangents_z = new_tangents_z;
			radiuses = new_radiuses;
		}
		maxPoints = newMaxPoints;
	}

	protected void add(final Path other) {

		if (other == null) {
			SNT.warn("BUG: Trying to add null Path");
			return;
		}

		// If we're trying to add a path with circles to one
		// that previously had none, add circles to the
		// previous one, and carry on:

		if (other.hasRadii() && !hasRadii()) {
			createCircles();
			final double defaultRadius = getMinimumSeparation() * 2;
			for (int i = 0; i < points; ++i)
				radiuses[i] = defaultRadius;
		}

		if (maxPoints < (points + other.points)) {
			expandTo(points + other.points);
		}

		int toSkip = 0;

		/*
		 * We may want to skip some points at the beginning of the next path if
		 * they're the same as the last point on this path:
		 */

		if (points > 0) {
			final double last_x = precise_x_positions[points - 1];
			final double last_y = precise_y_positions[points - 1];
			final double last_z = precise_z_positions[points - 1];
			while ((other.precise_x_positions[toSkip] == last_x) && (other.precise_y_positions[toSkip] == last_y)
					&& (other.precise_z_positions[toSkip] == last_z)) {
				++toSkip;
			}
		}

		System.arraycopy(other.precise_x_positions, toSkip, precise_x_positions, points, other.points - toSkip);

		System.arraycopy(other.precise_y_positions, toSkip, precise_y_positions, points, other.points - toSkip);

		System.arraycopy(other.precise_z_positions, toSkip, precise_z_positions, points, other.points - toSkip);

		if (hasRadii()) {

			System.arraycopy(other.radiuses, toSkip, radiuses, points, other.points - toSkip);

		}

		if (endJoins != null)
			throw new RuntimeException("BUG: we should never be adding to a path that already endJoins");

		if (other.endJoins != null) {
			setEndJoin(other.endJoins, other.endJoinsPoint);
			other.disconnectFromAll();
		}

		points = points + (other.points - toSkip);

		if (hasRadii()) {
			setGuessedTangents(2);
		}
	}

	protected void unsetPrimaryForConnected(final HashSet<Path> pathsExplored) {
		for (final Path p : somehowJoins) {
			if (pathsExplored.contains(p))
				continue;
			p.setIsPrimary(false);
			pathsExplored.add(p);
			p.unsetPrimaryForConnected(pathsExplored);
		}
	}

	protected Path reversed() {
		final Path c = new Path(x_spacing, y_spacing, z_spacing, spacing_units, points);
		c.points = points;
		for (int i = 0; i < points; ++i) {
			c.precise_x_positions[i] = precise_x_positions[(points - 1) - i];
			c.precise_y_positions[i] = precise_y_positions[(points - 1) - i];
			c.precise_z_positions[i] = precise_z_positions[(points - 1) - i];
		}
		return c;
	}

	public void addPointDouble(final double x, final double y, final double z) {
		if (points >= maxPoints) {
			final int newReserved = (int) (maxPoints * 1.2 + 1);
			expandTo(newReserved);
		}
		precise_x_positions[points] = x;
		precise_y_positions[points] = y;
		precise_z_positions[points++] = z;
	}

	public void drawPathAsPoints(final TracerCanvas canvas, final Graphics2D g, final java.awt.Color c, final int plane,
			final boolean highContrast, final boolean drawDiameter) {
		drawPathAsPoints(canvas, g, c, plane, highContrast, drawDiameter, 0, -1);
	}

	protected void drawPathAsPoints(final TracerCanvas canvas, final Graphics2D g, final java.awt.Color c, final int plane,
			final boolean drawDiameter, final int slice, final int either_side) {
		drawPathAsPoints(canvas, g, c, plane, false, drawDiameter, slice, either_side);
	}

	protected void drawPathAsPoints(final Graphics2D g2, TracerCanvas canvas, SimpleNeuriteTracer snt) {
		final boolean customColor = (hasCustomColor && snt.displayCustomPathColors);
		Color color = snt.deselectedColor;
		if (isSelected() && !customColor) color = snt.selectedColor;
		else if (customColor) color = getColor();
		final int sliceZeroIndexed = canvas.getImage().getZ() - 1;
		int eitherSideParameter = canvas.eitherSide;
		if (!canvas.just_near_slices)
			eitherSideParameter = -1;
		drawPathAsPoints(canvas, g2, color, canvas.getPlane(), customColor,
			snt.drawDiametersXY, sliceZeroIndexed, eitherSideParameter);
	}

	public void drawPathAsPoints(final TracerCanvas canvas, final Graphics2D g2, final java.awt.Color c, final int plane,
			final boolean highContrast, boolean drawDiameter, final int slice, final int either_side) {

		g2.setColor(c);
		int startIndexOfLastDrawnLine = -1;

		if (!hasRadii())
			drawDiameter = false;

		for (int i = 0; i < points; ++i) {

			double previous_x_on_screen = Integer.MIN_VALUE;
			double previous_y_on_screen = Integer.MIN_VALUE;
			double next_x_on_screen = Integer.MIN_VALUE;
			double next_y_on_screen = Integer.MIN_VALUE;
			final boolean notFirstPoint = i > 0;
			final boolean notLastPoint = i < points - 1;
			int slice_of_point = Integer.MIN_VALUE;

			switch (plane) {
			case MultiDThreePanes.XY_PLANE:
				if (notFirstPoint) {
					previous_x_on_screen = canvas.myScreenXDprecise(precise_x_positions[i - 1] / x_spacing);
					previous_y_on_screen = canvas.myScreenYDprecise(precise_y_positions[i - 1] / y_spacing);
				}
				if (notLastPoint) {
					next_x_on_screen = canvas.myScreenXDprecise(precise_x_positions[i + 1] / x_spacing);
					next_y_on_screen = canvas.myScreenYDprecise(precise_y_positions[i + 1] / y_spacing);
				}
				slice_of_point = getZUnscaled(i);
				break;
			case MultiDThreePanes.XZ_PLANE:
				if (notFirstPoint) {
					previous_x_on_screen = canvas.myScreenXDprecise(precise_x_positions[i - 1] / x_spacing);
					previous_y_on_screen = canvas.myScreenYDprecise(precise_z_positions[i - 1] / z_spacing);
				}
				if (notLastPoint) {
					next_x_on_screen = canvas.myScreenXDprecise(precise_x_positions[i + 1] / x_spacing);
					next_y_on_screen = canvas.myScreenYDprecise(precise_z_positions[i + 1] / z_spacing);
				}
				slice_of_point = getYUnscaled(i);
				break;
			case MultiDThreePanes.ZY_PLANE:
				if (notFirstPoint) {
					previous_x_on_screen = canvas.myScreenXDprecise(precise_z_positions[i - 1] / z_spacing);
					previous_y_on_screen = canvas.myScreenYDprecise(precise_y_positions[i - 1] / y_spacing);
				}
				if (notLastPoint) {
					next_x_on_screen = canvas.myScreenXDprecise(precise_z_positions[i + 1] / z_spacing);
					next_y_on_screen = canvas.myScreenYDprecise(precise_y_positions[i + 1] / y_spacing);
				}
				slice_of_point = getXUnscaled(i);
				break;
			default:
				throw new IllegalArgumentException("BUG: Unknown plane! (" + plane + ")");
			}


			final PathNode pn = new PathNode(this, i, canvas);
			final boolean outOfDepthBounds = (either_side >= 0) && (Math.abs(slice_of_point - slice) > either_side);
			g2.setColor(SWCColor.alphaColor(c, (outOfDepthBounds)?50:100));

			// If there was a previous point in this path, draw a line from there to here:
			if (notFirstPoint) {
				// Don't redraw the line if we drew it the previous time, though:
				if (startIndexOfLastDrawnLine != i - 1) {
					g2.draw(new Line2D.Double(previous_x_on_screen, previous_y_on_screen, pn.x, pn.y));
					startIndexOfLastDrawnLine = i - 1;
				}
			}

			// If there's a next point in this path, draw a line from here to there:
			if (notLastPoint) {
				g2.draw(new Line2D.Double(pn.x, pn.y, next_x_on_screen, next_y_on_screen));
				startIndexOfLastDrawnLine = i;
			}

			if (outOfDepthBounds) continue; // draw nothing more for points out-of-bounds

			// If we've been asked to draw the diameters, just do it in XY
			if (drawDiameter && plane == MultiDThreePanes.XY_PLANE) {

				// Cross the tangents with a unit z vector:
				final double n_x = 0;
				final double n_y = 0;
				final double n_z = 1;
				final double t_x = tangents_x[i];
				final double t_y = tangents_y[i];
				final double t_z = tangents_z[i];
				final double cross_x = n_y * t_z - n_z * t_y;
				final double cross_y = n_z * t_x - n_x * t_z;
				// double cross_z = n_x * t_y - n_y * t_x;

				final double sizeInPlane = Math.sqrt(cross_x * cross_x + cross_y * cross_y);
				final double normalized_cross_x = cross_x / sizeInPlane;
				final double normalized_cross_y = cross_y / sizeInPlane;
				final double zdiff = Math.abs((slice - slice_of_point) * z_spacing);
				final double realRadius = radiuses[i];

				if (either_side < 0 || zdiff <= realRadius) {

					double effective_radius;
					if (either_side < 0)
						effective_radius = realRadius;
					else
						effective_radius = Math.sqrt(realRadius * realRadius - zdiff * zdiff);

					final double left_x = precise_x_positions[i] + normalized_cross_x * effective_radius;
					final double left_y = precise_y_positions[i] + normalized_cross_y * effective_radius;
					final double right_x = precise_x_positions[i] - normalized_cross_x * effective_radius;
					final double right_y = precise_y_positions[i] - normalized_cross_y * effective_radius;

					final double left_x_on_screen = canvas.myScreenXDprecise(left_x / x_spacing);
					final double left_y_on_screen = canvas.myScreenYDprecise(left_y / y_spacing);
					final double right_x_on_screen = canvas.myScreenXDprecise(right_x / x_spacing);
					final double right_y_on_screen = canvas.myScreenYDprecise(right_y / y_spacing);

					final double x_on_screen = canvas.myScreenXDprecise(precise_x_positions[i] / x_spacing);
					final double y_on_screen = canvas.myScreenYDprecise(precise_y_positions[i] / y_spacing);

					g2.draw(new Line2D.Double(x_on_screen, y_on_screen, left_x_on_screen, left_y_on_screen));
					g2.draw(new Line2D.Double(x_on_screen, y_on_screen, right_x_on_screen, right_y_on_screen));
				}

			}

			// Draw node
			pn.setEditable(getEditableNodeIndex()==i);
			pn.draw(g2, c);
			g2.setColor(c); // reset color transparencies. Not really needed
		}

	}

	public Color getColor() {
		return color;
	}

	public void setColor(final Color color) {
		this.color = color;
		hasCustomColor = color != null;
		if (fitted != null)
			fitted.setColor(color);
	}

	public void setColorBySWCtype() {
		setColor(getSWCcolor());
	}

	public boolean hasCustomColor() {
		return hasCustomColor && color != null;
	}

	public static Color getSWCcolor(final int swcType) {
		switch (swcType) {
			case Path.SWC_SOMA:
				return Color.BLUE;
			case Path.SWC_DENDRITE:
				return Color.GREEN;
			case Path.SWC_APICAL_DENDRITE:
				return Color.CYAN;
			case Path.SWC_AXON:
				return Color.RED;
			case Path.SWC_FORK_POINT:
				return Color.ORANGE;
			case Path.SWC_END_POINT:
				return Color.PINK;
			case Path.SWC_CUSTOM:
				return Color.YELLOW;
			case Path.SWC_UNDEFINED:
			default:
				return SimpleNeuriteTracer.DEFAULT_DESELECTED_COLOR;
			}
	}

	public Color getSWCcolor() {
		return getSWCcolor(swcType);
	}

	// ------------------------------------------------------------------------
	// FIXME: adapt these for Path rather than SegmentedConnection, down to
	// EOFIT

	private class CircleAttempt implements MultivariateFunction, Comparable<CircleAttempt> {

		double min;
		float[] data;
		float minValueInData;
		float maxValueInData;
		int side;

		public CircleAttempt(final double[] start, final float[] data, final float minValueInData,
				final float maxValueInData, final int side) {

			this.data = data;
			this.minValueInData = minValueInData;
			this.maxValueInData = maxValueInData;
			this.side = side;

			min = Double.MAX_VALUE;
		}

		@Override
		public int compareTo(final CircleAttempt o) {
			if (min < o.min)
				return -1;
			else if (min > o.min)
				return +1;
			else
				return 0;
		}

		@Override
		public int getNumArguments() {
			return 3;
		}

		@Override
		public double getLowerBound(final int n) {
			return 0;
		}

		@Override
		public double getUpperBound(final int n) {
			return side;
		}

		@Override
		public double evaluate(final double[] x) {
			final double badness = evaluateCircle(x[0], x[1], x[2]);

			if (badness < min) {
				x.clone();
				min = badness;
			}

			return badness;
		}

		public double evaluateCircle(final double x, final double y, final double r) {

			final double maximumPointPenalty = (maxValueInData - minValueInData) * (maxValueInData - minValueInData);

			double badness = 0;

			for (int i = 0; i < side; ++i) {
				for (int j = 0; j < side; ++j) {
					final float value = data[j * side + i];
					if (r * r > ((i - x) * (i - x) + (j - y) * (j - y)))
						badness += (maxValueInData - value) * (maxValueInData - value);
					else
						badness += (value - minValueInData) * (value - minValueInData);
				}
			}

			for (double ic = (x - r); ic <= (x + r); ++ic) {
				for (double jc = (y - r); jc <= (y + r); ++jc) {
					if (ic < 0 || ic > side || jc < 0 || jc > side)
						badness += maximumPointPenalty;
				}
			}

			badness /= (side * side);

			return badness;
		}

	}

	public boolean isFittedVersionOfAnotherPath() {
		return fittedVersionOf != null;
	}

	public void setFitted(final Path p) {
		if (fitted != null) {
			throw new RuntimeException("BUG: Trying to set a fitted path when there already is one...");
		}
		fitted = p;
		p.fittedVersionOf = this;
	}

	public void setUseFitted(final boolean useFitted) {
		if (useFitted && fitted == null)
			throw new IllegalArgumentException("setUseFitted(true) called, but 'fitted' member was null");
		this.useFitted = useFitted;
	}

	public boolean getUseFitted() {
		return useFitted;
	}

	public Path getFitted() {
		return fitted;
	}

	public void setGuessedTangents(final int pointsEitherSide) {
		if (tangents_x == null || tangents_y == null || tangents_z == null)
			throw new RuntimeException("BUG: setGuessedTangents called with one of the tangent arrays null");
		final double[] tangent = new double[3];
		for (int i = 0; i < points; ++i) {
			getTangent(i, pointsEitherSide, tangent);
			tangents_x[i] = tangent[0];
			tangents_y[i] = tangent[1];
			tangents_z[i] = tangent[2];
		}
	}

	public void getTangent(final int i, final int pointsEitherSide, final double[] result) {
		int min_index = i - pointsEitherSide;
		if (min_index < 0)
			min_index = 0;

		int max_index = i + pointsEitherSide;
		if (max_index >= points)
			max_index = points - 1;

		result[0] = precise_x_positions[max_index] - precise_x_positions[min_index];
		result[1] = precise_y_positions[max_index] - precise_y_positions[min_index];
		result[2] = precise_z_positions[max_index] - precise_z_positions[min_index];
	}

	private float[] squareNormalToVector(final int side, // The number of samples
														// in x and y in the
														// plane, separated by
														// step
			final double step, // step is in the same units as the _spacing,
								// etc. variables.
			final double ox, /* These are scaled now */
			final double oy, final double oz, final double nx, final double ny, final double nz,
			final double[] x_basis_vector, /*
											 * The basis vectors are returned
											 * here
											 */
			final double[] y_basis_vector, /* they *are* scaled by _spacing */
			final ImagePlus image) {

		final float[] result = new float[side * side];

		final double epsilon = 0.000001;

		/*
		 * To find an arbitrary vector in the normal plane, do the cross product
		 * with (0,0,1), unless the normal is parallel to that, in which case we
		 * cross it with (0,1,0) instead...
		 */

		double ax, ay, az;

		if (Math.abs(nx) < epsilon && Math.abs(ny) < epsilon) {
			// Cross with (0,1,0):
			ax = nz;
			ay = 0;
			az = -nx;
		} else {
			// Cross with (0,0,1):
			ax = -ny;
			ay = nx;
			az = 0;
		}

		/*
		 * Now to find the other vector in that plane, do the cross product of
		 * (ax,ay,az) with (nx,ny,nz)
		 */

		double bx = ay * nz - az * ny;
		double by = az * nx - ax * nz;
		double bz = ax * ny - ay * nx;

		/* Normalize a and b */

		final double a_size = Math.sqrt(ax * ax + ay * ay + az * az);
		ax = ax / a_size;
		ay = ay / a_size;
		az = az / a_size;

		final double b_size = Math.sqrt(bx * bx + by * by + bz * bz);
		bx = bx / b_size;
		by = by / b_size;
		bz = bz / b_size;

		/* Scale them with spacing... */

		final double ax_s = ax * step;
		final double ay_s = ay * step;
		final double az_s = az * step;

		final double bx_s = bx * step;
		final double by_s = by * step;
		final double bz_s = bz * step;

		SNT.log("a (in normal plane) is " + ax + "," + ay + "," + az);
		SNT.log("b (in normal plane) is " + bx + "," + by + "," + bz);


		// a and b must be perpendicular:
		final double a_dot_b = ax * bx + ay * by + az * bz;

		// ... and each must be perpendicular to the normal
		final double a_dot_n = ax * nx + ay * ny + az * nz;
		final double b_dot_n = bx * nx + by * ny + bz * nz;

		SNT.log("a_dot_b: " + a_dot_b);
		SNT.log("a_dot_n: " + a_dot_n);
		SNT.log("b_dot_n: " + b_dot_n);

		final int width = image.getWidth();
		final int height = image.getHeight();
		final int depth = image.getNSlices(); //FIXME: Check hyperstack support
		final float[][] v = new float[depth][];
		final ImageStack s = image.getStack();
		final int imageType = image.getType();
		final int arraySize = width * height;
		if (imageType == ImagePlus.GRAY8 || imageType == ImagePlus.COLOR_256) {
			for (int z = 0; z < depth; ++z) {
				final byte[] bytePixels = (byte[]) s.getPixels(z + 1);
				final float[] fa = new float[arraySize];
				for (int i = 0; i < arraySize; ++i)
					fa[i] = bytePixels[i] & 0xFF;
				v[z] = fa;
			}
		} else if (imageType == ImagePlus.GRAY16) {
			for (int z = 0; z < depth; ++z) {
				final short[] shortPixels = (short[]) s.getPixels(z + 1);
				final float[] fa = new float[arraySize];
				for (int i = 0; i < arraySize; ++i)
					fa[i] = shortPixels[i];
				v[z] = fa;
			}
		} else if (imageType == ImagePlus.GRAY32) {
			for (int z = 0; z < depth; ++z) {
				v[z] = (float[]) s.getPixels(z + 1);
			}
		}

		for (int grid_i = 0; grid_i < side; ++grid_i) {
			for (int grid_j = 0; grid_j < side; ++grid_j) {

				final double midside_grid = ((side - 1) / 2.0f);

				final double gi = midside_grid - grid_i;
				final double gj = midside_grid - grid_j;

				final double vx = ox + gi * ax_s + gj * bx_s;
				final double vy = oy + gi * ay_s + gj * by_s;
				final double vz = oz + gi * az_s + gj * bz_s;

				// So now denormalize to pixel co-ordinates:

				final double image_x = vx / x_spacing;
				final double image_y = vy / y_spacing;
				final double image_z = vz / z_spacing;

				/*
				 * And do a trilinear interpolation to find the value there:
				 */

				final double x_d = image_x - Math.floor(image_x);
				final double y_d = image_y - Math.floor(image_y);
				final double z_d = image_z - Math.floor(image_z);

				final int x_f = (int) Math.floor(image_x);
				final int x_c = (int) Math.ceil(image_x);
				final int y_f = (int) Math.floor(image_y);
				final int y_c = (int) Math.ceil(image_y);
				final int z_f = (int) Math.floor(image_z);
				final int z_c = (int) Math.ceil(image_z);

				/*
				 * Check that these values aren't poking off the edge of the
				 * screen - if so then make them zero.
				 */

				double fff;
				double cff;
				double fcf;
				double ccf;

				double ffc;
				double cfc;
				double fcc;
				double ccc;

				if ((x_f < 0) || (x_c < 0) || (y_f < 0) || (y_c < 0) || (z_f < 0) || (z_c < 0) || (x_f >= width)
						|| (x_c >= width) || (y_f >= height) || (y_c >= height) || (z_f >= depth) || (z_c >= depth)) {

					fff = 0;
					cff = 0;
					fcf = 0;
					ccf = 0;
					ffc = 0;
					cfc = 0;
					fcc = 0;
					ccc = 0;

				} else {

					fff = v[z_f][width * y_f + x_f];
					cff = v[z_c][width * y_f + x_f];

					fcf = v[z_f][width * y_c + x_f];
					ccf = v[z_c][width * y_c + x_f];

					ffc = v[z_f][width * y_f + x_c];
					cfc = v[z_c][width * y_f + x_c];

					fcc = v[z_f][width * y_c + x_c];
					ccc = v[z_c][width * y_c + x_c];

				}

				// Now we should be OK to do the interpolation for real:

				final double i1 = (1 - z_d) * (fff) + (cff) * z_d;
				final double i2 = (1 - z_d) * (fcf) + (ccf) * z_d;

				final double j1 = (1 - z_d) * (ffc) + (cfc) * z_d;
				final double j2 = (1 - z_d) * (fcc) + (ccc) * z_d;

				final double w1 = i1 * (1 - y_d) + i2 * y_d;
				final double w2 = j1 * (1 - y_d) + j2 * y_d;

				final double value_f = w1 * (1 - x_d) + w2 * x_d;

				result[grid_j * side + grid_i] = (float) value_f;
			}
		}

		x_basis_vector[0] = ax_s;
		x_basis_vector[1] = ay_s;
		x_basis_vector[2] = az_s;

		y_basis_vector[0] = bx_s;
		y_basis_vector[1] = by_s;
		y_basis_vector[2] = bz_s;

		return result;
	}

	public Path fitCircles(final int side, final ImagePlus image) {
		return fitCircles(side, image, false, null, -1, null);
	}

	protected Path fitCircles(final int side, final ImagePlus image, boolean display,
			final SimpleNeuriteTracer plugin, final int progressIndex, final MultiTaskProgress progress) {

		final Path fitted = new Path(x_spacing, y_spacing, z_spacing, spacing_units);
		final int totalPoints = size();
		final int pointsEitherSide = 4;

		SNT.log("Started fitting: "+ this.getName() +". Generating normal planes stack....");
		SNT.log("There are: " + totalPoints + " in the stack.");
		SNT.log("Spacing: " + x_spacing + "," + y_spacing + "," + z_spacing +" ("+ spacing_units +")");

		final int width = image.getWidth();
		final int height = image.getHeight();
		final int depth = image.getNSlices();

		final ImageStack stack = new ImageStack(side, side);

		// We assume that the first and the last in the stack are fine;
		final double[] centre_x_positionsUnscaled = new double[totalPoints];
		final double[] centre_y_positionsUnscaled = new double[totalPoints];
		final double[] rs = new double[totalPoints];
		final double[] rsUnscaled = new double[totalPoints];

		final double[] ts_x = new double[totalPoints];
		final double[] ts_y = new double[totalPoints];
		final double[] ts_z = new double[totalPoints];

		final double[] optimized_x = new double[totalPoints];
		final double[] optimized_y = new double[totalPoints];
		final double[] optimized_z = new double[totalPoints];

		final double[] scores = new double[totalPoints];

		final double[] moved = new double[totalPoints];

		final boolean[] valid = new boolean[totalPoints];

		final int[] xs_in_image = new int[totalPoints];
		final int[] ys_in_image = new int[totalPoints];
		final int[] zs_in_image = new int[totalPoints];

		final double scaleInNormalPlane = getMinimumSeparation();

		final double[] tangent = new double[3];

		if (progress != null)
			progress.updateProgress(progressIndex, 0);

		for (int i = 0; i < totalPoints; ++i) {

			getTangent(i, pointsEitherSide, tangent);

			final double x_world = precise_x_positions[i];
			final double y_world = precise_y_positions[i];
			final double z_world = precise_z_positions[i];

			final double[] x_basis_in_plane = new double[3];
			final double[] y_basis_in_plane = new double[3];

			final float[] normalPlane = squareNormalToVector(side, scaleInNormalPlane, // This
																						// is
																						// in
																						// the
																						// same
																						// units
																						// as
																						// the
																						// _spacing,
																						// etc.
																						// variables.
					x_world, // These are scaled now
					y_world, z_world, tangent[0], tangent[1], tangent[2], x_basis_in_plane, y_basis_in_plane, image);

			/*
			 * Now at this stage, try to optimize a circle in there...
			 */

			// n.b. thes aren't normalized
			ts_x[i] = tangent[0];
			ts_y[i] = tangent[1];
			ts_z[i] = tangent[2];

			final ConjugateDirectionSearch optimizer = new ConjugateDirectionSearch();
			// optimizer.prin = 2; // debugging information on
			optimizer.step = side / 4.0;

			final double[] startValues = new double[3];
			startValues[0] = side / 2.0;
			startValues[1] = side / 2.0;
			startValues[2] = 3;

			SNT.log("start search at: " + startValues[0] + "," + startValues[1] + " with radius: "
					+ startValues[2]);

			float minValueInSquare = Float.MAX_VALUE;
			float maxValueInSquare = Float.MIN_VALUE;
			for (int j = 0; j < (side * side); ++j) {
				final float value = normalPlane[j];
				maxValueInSquare = Math.max(value, maxValueInSquare);
				minValueInSquare = Math.min(value, minValueInSquare);
			}

			final CircleAttempt attempt = new CircleAttempt(startValues, normalPlane, minValueInSquare,
					maxValueInSquare, side);

			try {
				optimizer.optimize(attempt, startValues, 2, 2);
			} catch (final ConjugateDirectionSearch.OptimizationError e) {
				return null;
			}

			SNT.log("search optimized to: " + startValues[0] + "," + startValues[1] + " with radius: "
					+ startValues[2]);

			centre_x_positionsUnscaled[i] = startValues[0];
			centre_y_positionsUnscaled[i] = startValues[1];
			rsUnscaled[i] = startValues[2];
			rs[i] = scaleInNormalPlane * rsUnscaled[i];

			scores[i] = attempt.min;

			// Now we calculate the real co-ordinates of the new centre:

			final double x_from_centre_in_plane = startValues[0] - (side / 2.0);
			final double y_from_centre_in_plane = startValues[1] - (side / 2.0);

			moved[i] = scaleInNormalPlane * Math.sqrt(
					x_from_centre_in_plane * x_from_centre_in_plane + y_from_centre_in_plane * y_from_centre_in_plane);

			SNT.log("vector to new centre from original: " + x_from_centre_in_plane + "," + y_from_centre_in_plane);

			double centre_real_x = x_world;
			double centre_real_y = y_world;
			double centre_real_z = z_world;

			SNT.log("original centre in real co-ordinates: " + centre_real_x + "," + centre_real_y + ","
					+ centre_real_z);

			// FIXME: I really think these should be +=, but it seems clear from
			// the results that I've got a sign wrong somewhere :(

			centre_real_x -= x_basis_in_plane[0] * x_from_centre_in_plane
					+ y_basis_in_plane[0] * y_from_centre_in_plane;
			centre_real_y -= x_basis_in_plane[1] * x_from_centre_in_plane
					+ y_basis_in_plane[1] * y_from_centre_in_plane;
			centre_real_z -= x_basis_in_plane[2] * x_from_centre_in_plane
					+ y_basis_in_plane[2] * y_from_centre_in_plane;

			SNT.log("adjusted original centre in real co-ordinates: " + centre_real_x + "," + centre_real_y + ","
					+ centre_real_z);

			optimized_x[i] = centre_real_x;
			optimized_y[i] = centre_real_y;
			optimized_z[i] = centre_real_z;

			int x_in_image = (int) Math.round(centre_real_x / x_spacing);
			int y_in_image = (int) Math.round(centre_real_y / y_spacing);
			int z_in_image = (int) Math.round(centre_real_z / z_spacing);

			SNT.log("gives in image co-ordinates: " + x_in_image + "," + y_in_image + "," + z_in_image);

			if (x_in_image < 0)
				x_in_image = 0;
			if (x_in_image >= width)
				x_in_image = width - 1;
			if (y_in_image < 0)
				y_in_image = 0;
			if (y_in_image >= height)
				y_in_image = height - 1;
			if (z_in_image < 0)
				z_in_image = 0;
			if (z_in_image >= depth)
				z_in_image = depth - 1;

			SNT.log("addingPoint: " + x_in_image + "," + y_in_image + "," + z_in_image);

			xs_in_image[i] = x_in_image;
			ys_in_image[i] = y_in_image;
			zs_in_image[i] = z_in_image;

			SNT.log("Adding a real slice.");

			final FloatProcessor bp = new FloatProcessor(side, side);
			bp.setPixels(normalPlane);
			stack.addSlice("Node "+ (i+1), bp);

			if (progress != null)
				progress.updateProgress(((double) i + 1) / totalPoints, progressIndex);
		}

		/*
		 * Now at each point along the path we calculate the mode of the
		 * radiuses in the nearby region:
		 */

		final int modeEitherSide = 4;
		final double[] modeRadiusesUnscaled = new double[totalPoints];
		final double[] modeRadiuses = new double[totalPoints];
		final double[] valuesForMode = new double[modeEitherSide * 2 + 1];

		for (int i = 0; i < totalPoints; ++i) {
			final int minIndex = i - modeEitherSide;
			final int maxIndex = i + modeEitherSide;
			int c = 0;
			for (int modeIndex = minIndex; modeIndex <= maxIndex; ++modeIndex) {
				if (modeIndex < 0)
					valuesForMode[c] = Double.MIN_VALUE;
				else if (modeIndex >= totalPoints)
					valuesForMode[c] = Double.MAX_VALUE;
				else {
					if (rsUnscaled[modeIndex] < 1)
						valuesForMode[c] = 1;
					else
						valuesForMode[c] = rsUnscaled[modeIndex];
				}
				++c;
			}
			Arrays.sort(valuesForMode);
			modeRadiusesUnscaled[i] = valuesForMode[modeEitherSide];
			modeRadiuses[i] = scaleInNormalPlane * modeRadiusesUnscaled[i];

			valid[i] = moved[i] < modeRadiusesUnscaled[i];
		}

		// Calculate the angle between the vectors from the point to the one on
		// either side:
		final double[] angles = new double[totalPoints];
		// Set the end points to 180 degrees:
		angles[0] = angles[totalPoints - 1] = Math.PI;
		for (int i = 1; i < totalPoints - 1; ++i) {
			// If there's no previously valid one then
			// just use the first:
			int previousValid = 0;
			for (int j = 0; j < i; ++j)
				if (valid[j])
					previousValid = j;
			// If there's no next valid one then just use
			// the first:
			int nextValid = totalPoints - 1;
			for (int j = totalPoints - 1; j > i; --j)
				if (valid[j])
					nextValid = j;
			final double adiffx = optimized_x[previousValid] - optimized_x[i];
			final double adiffy = optimized_y[previousValid] - optimized_y[i];
			final double adiffz = optimized_z[previousValid] - optimized_z[i];
			final double bdiffx = optimized_x[nextValid] - optimized_x[i];
			final double bdiffy = optimized_y[nextValid] - optimized_y[i];
			final double bdiffz = optimized_z[nextValid] - optimized_z[i];
			final double adotb = adiffx * bdiffx + adiffy * bdiffy + adiffz * bdiffz;
			final double asize = Math.sqrt(adiffx * adiffx + adiffy * adiffy + adiffz * adiffz);
			final double bsize = Math.sqrt(bdiffx * bdiffx + bdiffy * bdiffy + bdiffz * bdiffz);
			angles[i] = Math.acos(adotb / (asize * bsize));
			if (angles[i] < (Math.PI / 2))
				valid[i] = false;
		}

		/*
		 * Repeatedly build an array indicating how many other valid circles
		 * each one overlaps with, and remove the worst culprits on each run
		 * until they're all gone... This is horrendously inefficient (O(n^3) in
		 * the worst case) but I'm more sure of its correctness than other
		 * things I've tried, and there should be few overlapping circles.
		 */
		final int[] overlapsWith = new int[totalPoints];
		boolean someStillOverlap = true;
		while (someStillOverlap) {
			someStillOverlap = false;
			int maximumNumberOfOverlaps = -1;
			for (int i = 0; i < totalPoints; ++i) {
				overlapsWith[i] = 0;
				if (!valid[i])
					continue;
				for (int j = 0; j < totalPoints; ++j) {
					if (!valid[j])
						continue;
					if (i == j)
						continue;
					if (circlesOverlap(ts_x[i], ts_y[i], ts_z[i], optimized_x[i], optimized_y[i], optimized_z[i], rs[i],
							ts_x[j], ts_y[j], ts_z[j], optimized_x[j], optimized_y[j], optimized_z[j], rs[j])) {
						++overlapsWith[i];
						someStillOverlap = true;
					}
				}
				if (overlapsWith[i] > maximumNumberOfOverlaps)
					maximumNumberOfOverlaps = overlapsWith[i];
			}
			if (maximumNumberOfOverlaps <= 0) {
				break;
			}
			// Now we've built the array, go through and
			// remove the worst offenders:
			for (int i = 0; i < totalPoints; ++i) {
				if (!valid[i])
					continue;
				int n = totalPoints;
				for (int j = totalPoints - 1; j > i; --j)
					if (valid[j])
						n = j;
				if (overlapsWith[i] == maximumNumberOfOverlaps) {
					// If the next valid one has
					// the same number, and that
					// has a larger radius, remove
					// that one instead...
					if (n < totalPoints && overlapsWith[n] == maximumNumberOfOverlaps && rs[n] > rs[i]) {
						valid[n] = false;
					} else {
						valid[i] = false;
					}
					break;
				}
			}
		}

		int lastValidIndex = 0;

		for (int i = 0; i < totalPoints; ++i) {

			final boolean firstOrLast = (i == 0 || i == (points - 1));

			if (!valid[i]) {
				// The if we're gone too far without a
				// successfully optimized datapoint,
				// add the original one:
				final boolean goneTooFar = i - lastValidIndex >= noMoreThanOneEvery;
				boolean nextValid = false;
				if (i < (points - 1))
					if (valid[i + 1])
						nextValid = true;

				if ((goneTooFar && !nextValid) || firstOrLast) {
					valid[i] = true;
					xs_in_image[i] = getXUnscaled(i);
					ys_in_image[i] = getYUnscaled(i);
					zs_in_image[i] = getZUnscaled(i);
					optimized_x[i] = precise_x_positions[i];
					optimized_y[i] = precise_y_positions[i];
					optimized_z[i] = precise_z_positions[i];
					rsUnscaled[i] = 1;
					rs[i] = scaleInNormalPlane;
					modeRadiusesUnscaled[i] = 1;
					modeRadiuses[i] = scaleInNormalPlane;
					centre_x_positionsUnscaled[i] = side / 2.0;
					centre_y_positionsUnscaled[i] = side / 2.0;
				}
			}

			if (valid[i]) {
				if (rs[i] < scaleInNormalPlane) {
					rsUnscaled[i] = 1;
					rs[i] = scaleInNormalPlane;
				}
				fitted.addPointDouble(optimized_x[i], optimized_y[i], optimized_z[i]);
				lastValidIndex = i;
			}
		}

		final int fittedLength = fitted.size();

		final double[] fitted_ts_x = new double[fittedLength];
		final double[] fitted_ts_y = new double[fittedLength];
		final double[] fitted_ts_z = new double[fittedLength];
		final double[] fitted_rs = new double[fittedLength];
		final double[] fitted_optimized_x = new double[fittedLength];
		final double[] fitted_optimized_y = new double[fittedLength];
		final double[] fitted_optimized_z = new double[fittedLength];

		int added = 0;

		for (int i = 0; i < points; ++i) {
			if (!valid[i])
				continue;
			fitted_ts_x[added] = ts_x[i];
			fitted_ts_y[added] = ts_y[i];
			fitted_ts_z[added] = ts_z[i];
			fitted_rs[added] = rs[i];
			fitted_optimized_x[added] = optimized_x[i];
			fitted_optimized_y[added] = optimized_y[i];
			fitted_optimized_z[added] = optimized_z[i];
			++added;
		}

		if (added != fittedLength)
			throw new IllegalArgumentException("Mismatch of lengths, added=" + added + " and fittedLength=" + fittedLength);

		fitted.setFittedCircles(fitted_ts_x, fitted_ts_y, fitted_ts_z, fitted_rs, fitted_optimized_x,
				fitted_optimized_y, fitted_optimized_z);
		fitted.setName("Fitted Path [" + getID() + "]");
		fitted.setColor(getColor());
		fitted.setSWCType(getSWCType());
		setFitted(fitted);

		if (display) {
			SNT.log("displaying normal plane image");
			final ImagePlus imp = new ImagePlus("Normal Plane " + fitted.name, stack);
			imp.setCalibration(plugin.getImagePlus().getCalibration());
			final NormalPlaneCanvas normalCanvas = new NormalPlaneCanvas(imp, plugin, centre_x_positionsUnscaled,
					centre_y_positionsUnscaled, rsUnscaled, scores, modeRadiusesUnscaled, angles, valid, fitted);
			normalCanvas.showImage();
		}

		return fitted;
	}

	double[] radiuses;

	double[] tangents_x;
	double[] tangents_y;
	double[] tangents_z;

	double[] precise_x_positions;
	double[] precise_y_positions;
	double[] precise_z_positions;

	// http://www.neuronland.org/NLMorphologyConverter/MorphologyFormats/SWC/Spec.html
	public static final int SWC_UNDEFINED = 0;
	public static final int SWC_SOMA = 1;
	public static final int SWC_AXON = 2;
	public static final int SWC_DENDRITE = 3;
	public static final int SWC_APICAL_DENDRITE = 4;
	public static final int SWC_FORK_POINT = 5;
	public static final int SWC_END_POINT = 6;
	public static final int SWC_CUSTOM = 7;
	public static final String SWC_UNDEFINED_LABEL = "undefined";
	public static final String SWC_SOMA_LABEL = "soma";
	public static final String SWC_AXON_LABEL = "axon";
	public static final String SWC_DENDRITE_LABEL = "(basal) dendrite";
	public static final String SWC_APICAL_DENDRITE_LABEL = "apical dendrite";
	public static final String SWC_FORK_POINT_LABEL = "fork point";
	public static final String SWC_END_POINT_LABEL = "end point";
	public static final String SWC_CUSTOM_LABEL = "custom";

	public static final String[] swcTypeNames = getSWCtypeNamesArray();

	int swcType = SWC_UNDEFINED;

	private static String[] getSWCtypeNamesArray() {
		final ArrayList<String> swcTypes = getSWCtypeNames();
		return swcTypes.toArray(new String[swcTypes.size()]);
	}

	public static ArrayList<String> getSWCtypeNames() {
		final ArrayList<String> swcTypes = new ArrayList<>();
		swcTypes.add(SWC_UNDEFINED_LABEL);
		swcTypes.add(SWC_SOMA_LABEL);
		swcTypes.add(SWC_AXON_LABEL);
		swcTypes.add(SWC_DENDRITE_LABEL);
		swcTypes.add(SWC_APICAL_DENDRITE_LABEL);
		swcTypes.add(SWC_FORK_POINT_LABEL);
		swcTypes.add(SWC_END_POINT_LABEL);
		swcTypes.add(SWC_CUSTOM_LABEL);
		return swcTypes;
	}

	public static ArrayList<Integer> getSWCtypes() {
		final ArrayList<Integer> swcTypes = new ArrayList<>();
		swcTypes.add(SWC_UNDEFINED);
		swcTypes.add(SWC_SOMA);
		swcTypes.add(SWC_AXON);
		swcTypes.add(SWC_DENDRITE);
		swcTypes.add(SWC_APICAL_DENDRITE);
		swcTypes.add(SWC_FORK_POINT);
		swcTypes.add(SWC_END_POINT);
		swcTypes.add(SWC_CUSTOM);
		return swcTypes;
	}

	public static String getSWCtypeName(final int type) {
		String typeName;
		switch (type) {
		case SWC_UNDEFINED:
			typeName = SWC_UNDEFINED_LABEL;
			break;
		case SWC_SOMA:
			typeName = SWC_SOMA_LABEL;
			break;
		case SWC_AXON:
			typeName = SWC_AXON_LABEL;
			break;
		case SWC_DENDRITE:
			typeName = SWC_DENDRITE_LABEL;
			break;
		case SWC_APICAL_DENDRITE:
			typeName = SWC_APICAL_DENDRITE_LABEL;
			break;
		case SWC_FORK_POINT:
			typeName = SWC_FORK_POINT_LABEL;
			break;
		case SWC_END_POINT:
			typeName = SWC_END_POINT_LABEL;
			break;
		case SWC_CUSTOM:
			typeName = SWC_CUSTOM_LABEL;
			break;
		default:
			typeName = SWC_UNDEFINED_LABEL;
			break;
		}
		return typeName;
	}

	private boolean circlesOverlap(final double n1x, final double n1y, final double n1z, final double c1x,
			final double c1y, final double c1z, final double radius1, final double n2x, final double n2y,
			final double n2z, final double c2x, final double c2y, final double c2z, final double radius2)
			throws IllegalArgumentException {
		/*
		 * Roughly following the steps described here:
		 * http://local.wasp.uwa.edu.au/~pbourke/geometry/planeplane/
		 */
		final double epsilon = 0.000001;
		/*
		 * Take the cross product of n1 and n2 to see if they are colinear, in
		 * which case there is overlap:
		 */
		final double crossx = n1y * n2z - n1z * n2y;
		final double crossy = n1z * n2x - n1x * n2z;
		final double crossz = n1x * n2y - n1y * n2x;
		if (Math.abs(crossx) < epsilon && Math.abs(crossy) < epsilon && Math.abs(crossz) < epsilon) {
			// Then they don't overlap unless they're in
			// the same plane:
			final double cdiffx = c2x - c1x;
			final double cdiffy = c2y - c1y;
			final double cdiffz = c2z - c1z;
			final double cdiffdotn1 = cdiffx * n1x + cdiffy * n1y + cdiffz * n1z;
			return Math.abs(cdiffdotn1) < epsilon;
		}
		final double n1dotn1 = n1x * n1x + n1y * n1y + n1z * n1z;
		final double n2dotn2 = n2x * n2x + n2y * n2y + n2z * n2z;
		final double n1dotn2 = n1x * n2x + n1y * n2y + n1z * n2z;

		final double det = n1dotn1 * n2dotn2 - n1dotn2 * n1dotn2;
		if (Math.abs(det) < epsilon) {
			SNT.log("WARNING: det was nearly zero: " + det);
			return true;
		}

		// A vector r in the plane is defined by:
		// n1 . r = (n1 . c1) = d1

		final double d1 = n1x * c1x + n1y * c1y + n1z * c1z;
		final double d2 = n2x * c2x + n2y * c2y + n2z * c2z;

		final double constant1 = (d1 * n2dotn2 - d2 * n1dotn2) / det;
		final double constant2 = (d2 * n1dotn1 - d1 * n1dotn2) / det;

		/*
		 * So points on the line, paramaterized by u are now:
		 *
		 * constant1 n1 + constant2 n2 + u ( n1 x n2 )
		 *
		 * To find if the two circles overlap, we need to find the values of u
		 * where each crosses that line, in other words, for the first circle:
		 *
		 * radius1 = |constant1 n1 + constant2 n2 + u ( n1 x n2 ) - c1|
		 *
		 * => 0 = [ (constant1 n1 + constant2 n2 - c1).(constant1 n1 + constant2
		 * n2 - c1) - radius1 ^ 2 ] + [ 2 * ( n1 x n2 ) . ( constant1 n1 +
		 * constant2 n2 - c1 ) ] * u [ ( n1 x n2 ) . ( n1 x n2 ) ] * u^2 ]
		 *
		 * So we solve that quadratic:
		 *
		 */
		final double a1 = crossx * crossx + crossy * crossy + crossz * crossz;
		final double b1 = 2 * (crossx * (constant1 * n1x + constant2 * n2x - c1x)
				+ crossy * (constant1 * n1y + constant2 * n2y - c1y)
				+ crossz * (constant1 * n1z + constant2 * n2z - c1z));
		final double c1 = (constant1 * n1x + constant2 * n2x - c1x) * (constant1 * n1x + constant2 * n2x - c1x)
				+ (constant1 * n1y + constant2 * n2y - c1y) * (constant1 * n1y + constant2 * n2y - c1y)
				+ (constant1 * n1z + constant2 * n2z - c1z) * (constant1 * n1z + constant2 * n2z - c1z)
				- radius1 * radius1;

		final double a2 = crossx * crossx + crossy * crossy + crossz * crossz;
		final double b2 = 2 * (crossx * (constant1 * n1x + constant2 * n2x - c2x)
				+ crossy * (constant1 * n1y + constant2 * n2y - c2y)
				+ crossz * (constant1 * n1z + constant2 * n2z - c2z));
		final double c2 = (constant1 * n1x + constant2 * n2x - c2x) * (constant1 * n1x + constant2 * n2x - c2x)
				+ (constant1 * n1y + constant2 * n2y - c2y) * (constant1 * n1y + constant2 * n2y - c2y)
				+ (constant1 * n1z + constant2 * n2z - c2z) * (constant1 * n1z + constant2 * n2z - c2z)
				- radius2 * radius2;

		// So now calculate the discriminants:
		final double discriminant1 = b1 * b1 - 4 * a1 * c1;
		final double discriminant2 = b2 * b2 - 4 * a2 * c2;

		if (discriminant1 < 0 || discriminant2 < 0) {
			// Then one of the circles doesn't even reach the line:
			return false;
		}

		if (Math.abs(a1) < epsilon) {
			SNT.warn("CirclesOverlap: a1 was nearly zero: " + a1);
			return true;
		}

		final double u1_1 = Math.sqrt(discriminant1) / (2 * a1) - b1 / (2 * a1);
		final double u1_2 = -Math.sqrt(discriminant1) / (2 * a1) - b1 / (2 * a1);

		final double u2_1 = Math.sqrt(discriminant2) / (2 * a2) - b2 / (2 * a2);
		final double u2_2 = -Math.sqrt(discriminant2) / (2 * a2) - b2 / (2 * a2);

		final double u1_smaller = Math.min(u1_1, u1_2);
		final double u1_larger = Math.max(u1_1, u1_2);

		final double u2_smaller = Math.min(u2_1, u2_2);
		final double u2_larger = Math.max(u2_1, u2_2);

		// Non-overlapping cases:
		if (u1_larger < u2_smaller)
			return false;
		if (u2_larger < u1_smaller)
			return false;

		// Totally overlapping cases:
		if (u1_smaller <= u2_smaller && u2_larger <= u1_larger)
			return true;
		if (u2_smaller <= u1_smaller && u1_larger <= u2_larger)
			return true;

		// Partially overlapping cases:
		if (u1_smaller <= u2_smaller && u2_smaller <= u1_larger && u1_larger <= u2_larger)
			return true;
		if (u2_smaller <= u1_smaller && u1_smaller <= u2_larger && u2_larger <= u1_larger)
			return true;

		/*
		 * We only reach here if something has gone badly wrong, so dump helpful
		 * values to aid in debugging:
		 */
		SNT.log("CirclesOverlap seems to have failed: Current settings");
		SNT.log("det: " + det);
		SNT.log("discriminant1: " + discriminant1);
		SNT.log("discriminant2: " + discriminant2);
		SNT.log("n1: (" + n1x + "," + n1y + "," + n1z + ")");
		SNT.log("n2: (" + n2x + "," + n2y + "," + n2z + ")");
		SNT.log("c1: (" + c1x + "," + c1y + "," + c1z + ")");
		SNT.log("c2: (" + c2x + "," + c2y + "," + c2z + ")");
		SNT.log("radius1: " + radius1);
		SNT.log("radius2: " + radius2);

		throw new IllegalArgumentException("Some overlapping case missed: " + "u1_smaller=" + u1_smaller + "u1_larger="
				+ u1_larger + "u2_smaller=" + u2_smaller + "u2_larger=" + u2_larger);
	}

	/**
	 * Gets the path mean radius.
	 *
	 * @return the average radius of the path, or zero if path has no defined
	 *         thickness
	 */
	public double getMeanRadius() {
		if (radiuses == null) return 0;
		return StatUtils.mean(radiuses);
	}

	/**
	 * Checks if path has defined thickness.
	 *
	 * @return true, if the points defining with this path are associated with a
	 *         list of radii
	 */
	public boolean hasRadii() {
		return radiuses != null;
	}

	/** @deprecated see {@link #hasRadii()} */
	@Deprecated
	public boolean hashCircles() {
		return hasRadii();
	}

	public void setFittedCircles(final double[] tangents_x, final double[] tangents_y, final double[] tangents_z,
			final double[] radiuses, final double[] optimized_x, final double[] optimized_y,
			final double[] optimized_z) {

		this.tangents_x = tangents_x.clone();
		this.tangents_y = tangents_y.clone();
		this.tangents_z = tangents_z.clone();

		this.radiuses = radiuses.clone();

		this.precise_x_positions = optimized_x.clone();
		this.precise_y_positions = optimized_y.clone();
		this.precise_z_positions = optimized_z.clone();
	}

	public String realToString() {
		String name = getName();
		if (name == null)
			name = "Path " + id;
		if (size()==1)
		name += (size()==1) ? " [Single Point]" : " [" + getRealLengthString() + " " + spacing_units + "]";
		if (startJoins != null) {
			name += ", starts on " + startJoins.getName();
		}
		if (endJoins != null) {
			name += ", ends on " + endJoins.getName();
		}
		if (swcType != SWC_UNDEFINED)
			name += " [" + swcTypeNames[swcType] + "]";
		return name;
	}

	/**
	 * This toString() method shows details of the path which is actually being
	 * displayed, not necessarily this path object. FIXME: this is probably
	 * horribly confusing.
	 */

	@Override
	public String toString() {
		if (useFitted)
			return fitted.realToString();
		return realToString();
	}

	public void setSWCType(final int newSWCType) {
		setSWCType(newSWCType, true);
	}

	public void setSWCType(final int newSWCType, final boolean alsoSetInFittedVersion) {
		if (newSWCType < 0 || newSWCType >= swcTypeNames.length)
			throw new RuntimeException("BUG: Unknown SWC type " + newSWCType);
		swcType = newSWCType;
		if (alsoSetInFittedVersion) {
			/*
			 * If we've been asked to also set the fitted version, this should
			 * only be called on the non-fitted version of the path, so raise an
			 * error if it's been called on the fitted version by mistake
			 * instead:
			 */
			if (isFittedVersionOfAnotherPath() && fittedVersionOf.getSWCType() != newSWCType)
				throw new RuntimeException("BUG: only call setSWCType on the unfitted path");
			if (fitted != null)
				fitted.setSWCType(newSWCType);
		}
	}

	public int getSWCType() {
		return swcType;
	}

	/*
	 * @Override public String toString() { int n = size(); String result = "";
	 * if( name != null ) result += "\"" + name + "\" "; result += n +
	 * " points"; if( n > 0 ) { result += " from " + x_positions[0] + ", " +
	 * y_positions[0] + ", " + z_positions[0]; result += " to " +
	 * x_positions[n-1] + ", " + y_positions[n-1] + ", " + z_positions[n-1]; }
	 * return result; }
	 */

	/*
	 * These are various fields that have the current 3D representations of this
	 * path. They should only be updated by synchronized methods, currently:
	 *
	 * updateContent3D addTo3DViewer removeFrom3DViewer
	 */
	int paths3DDisplay = 1;
	Content content3D;
	Content content3DExtra;
	ImagePlus content3DMultiColored;
	ImagePlus content3DExtraMultiColored;
	String nameWhenAddedToViewer;
	String nameWhenAddedToViewerExtra;

	synchronized void removeIncludingFittedFrom3DViewer(final Image3DUniverse univ) {
		removeFrom3DViewer(univ);
		if (useFitted)
			fitted.removeFrom3DViewer(univ);
	}

	synchronized void updateContent3D(final Image3DUniverse univ, final boolean visible, final int paths3DDisplay,
			final Color3f color, final ImagePlus colorImage) {

		SNT.log("In updateContent3D, colorImage is: " + colorImage);
		SNT.log("In updateContent3D, color is: " + color);

		// So, go through each of the reasons why we might
		// have to remove (and possibly add back) the path:

		if (!visible) {
			/*
			 * It shouldn't be visible - if any of the contents are non-null,
			 * remove them:
			 */
			removeIncludingFittedFrom3DViewer(univ);
			return;
		}

		// Now we know it should be visible.

		Path pathToUse = null;

		if (useFitted) {
			/*
			 * If the non-fitted versions are currently being displayed, remove
			 * them:
			 */
			removeFrom3DViewer(univ);
			pathToUse = fitted;
		} else {
			/*
			 * If the fitted version is currently being displayed, remove it:
			 */
			if (fitted != null) {
				fitted.removeFrom3DViewer(univ);
			}
			pathToUse = this;
		}

		if (SNT.isDebugMode()) {
			SNT.log("pathToUse is: " + pathToUse);
			SNT.log("  pathToUse.content3D is: " + pathToUse.content3D);
			SNT.log("  pathToUse.content3DExtra is: " + pathToUse.content3DExtra);
			SNT.log("  pathToUse.content3DMultiColored: " + pathToUse.content3DMultiColored);
		}

		// Is the the display (lines-and-discs or surfaces) right?
		if (pathToUse.paths3DDisplay != paths3DDisplay) {
			pathToUse.removeFrom3DViewer(univ);
			pathToUse.paths3DDisplay = paths3DDisplay;
			pathToUse.addTo3DViewer(univ, color, colorImage);
			return;
		}

		/* Were we previously using a colour image, but now not? */

		if (colorImage == null) {
			if ((paths3DDisplay == SimpleNeuriteTracer.DISPLAY_PATHS_LINES_AND_DISCS
					&& pathToUse.content3DExtraMultiColored != null)
					|| (paths3DDisplay == SimpleNeuriteTracer.DISPLAY_PATHS_SURFACE
							&& pathToUse.content3DMultiColored != null)) {
				pathToUse.removeFrom3DViewer(univ);
				pathToUse.addTo3DViewer(univ, color, colorImage);
				return;
			}

			/*
			 * ... or, should we now use a colour image, where previously we
			 * were using a different colour image or no colour image?
			 */

		} else {
			if ((paths3DDisplay == SimpleNeuriteTracer.DISPLAY_PATHS_LINES_AND_DISCS
					&& pathToUse.content3DExtraMultiColored != colorImage)
					|| (paths3DDisplay == SimpleNeuriteTracer.DISPLAY_PATHS_SURFACE
							&& pathToUse.content3DMultiColored != colorImage)) {
				pathToUse.removeFrom3DViewer(univ);
				pathToUse.addTo3DViewer(univ, color, colorImage);
				return;
			}
		}

		// Has the path's representation in the 3D viewer been marked as
		// invalid?

		if (pathToUse.is3DViewInvalid()) {
			pathToUse.removeFrom3DViewer(univ);
			pathToUse.addTo3DViewer(univ, color, colorImage);
			invalid3DMesh = false;
			return;
		}

		// Is the (flat) color wrong?

		if (pathToUse.realColor == null || !pathToUse.realColor.equals(color)) {

			/*
			 * If there's a representation of the path in the 3D viewer anyway,
			 * just set the color, don't recreate it, since the latter takes a
			 * long time:
			 */

			if (pathToUse.content3D != null || pathToUse.content3DExtra != null) {

				if (pathToUse.content3D != null)
					pathToUse.content3D.setColor(color);
				if (pathToUse.content3DExtra != null)
					pathToUse.content3DExtra.setColor(color);
				pathToUse.realColor = color;
				return;

			}
			// ... but if it wasn't in the 3D viewer, recreate it:
			pathToUse.removeFrom3DViewer(univ);
			pathToUse.paths3DDisplay = paths3DDisplay;
			pathToUse.addTo3DViewer(univ, color, colorImage);
			return;
		}

		if (pathToUse.nameWhenAddedToViewer == null || !univ.contains(pathToUse.nameWhenAddedToViewer)) {
			pathToUse.paths3DDisplay = paths3DDisplay;
			pathToUse.addTo3DViewer(univ, color, colorImage);
		}
	}

	/*
	 * FIXME: this should be based on distance between points in the path, not a
	 * static number:
	 */
	public static final int noMoreThanOneEvery = 2;

	synchronized public void removeFrom3DViewer(final Image3DUniverse univ) {
		if (content3D != null) {
			univ.removeContent(nameWhenAddedToViewer);
			content3D = null;
		}
		if (content3DExtra != null) {
			univ.removeContent(nameWhenAddedToViewerExtra);
			content3DExtra = null;
		}
	}

	public java.util.List<Point3f> getPoint3fList() {
		final ArrayList<Point3f> linePoints = new ArrayList<>();
		for (int i = 0; i < points; ++i) {
			linePoints.add(new Point3f((float) precise_x_positions[i], (float) precise_y_positions[i],
					(float) precise_z_positions[i]));
		}
		return linePoints;
	}

	protected boolean invalid3DMesh = false;

	public void invalidate3DView() {
		invalid3DMesh = true;
	}

	public boolean is3DViewInvalid() {
		return invalid3DMesh;
	}

	public Content addAsLinesTo3DViewer(final Image3DUniverse univ, final Color c, final ImagePlus colorImage) {
		return addAsLinesTo3DViewer(univ, new Color3f(c), colorImage);
	}

	public Content addAsLinesTo3DViewer(final Image3DUniverse univ, final Color3f c, final ImagePlus colorImage) {
		final String safeName = univ.getSafeContentName(getName() + " as lines");
		return univ.addLineMesh(getPoint3fList(), c, safeName, true);
	}

	public Content addDiscsTo3DViewer(final Image3DUniverse univ, final Color c, final ImagePlus colorImage) {
		return addDiscsTo3DViewer(univ, new Color3f(c), colorImage);
	}

	public Content addDiscsTo3DViewer(final Image3DUniverse univ, final Color3f c, final ImagePlus colorImage) {
		if (!hasRadii())
			return null;

		final Color3f[] originalColors = Pipe.getPointColors(precise_x_positions, precise_y_positions,
				precise_z_positions, c, colorImage);

		final List<Color3f> meshColors = new ArrayList<>();

		final int edges = 8;
		final List<Point3f> allTriangles = new ArrayList<>(edges * points);
		for (int i = 0; i < points; ++i) {
			final List<Point3f> discMesh = customnode.MeshMaker.createDisc(precise_x_positions[i],
					precise_y_positions[i], precise_z_positions[i], tangents_x[i], tangents_y[i], tangents_z[i],
					radiuses[i], 8);
			final int pointsInDiscMesh = discMesh.size();
			for (int j = 0; j < pointsInDiscMesh; ++j)
				meshColors.add(originalColors[i]);
			allTriangles.addAll(discMesh);
		}
		return univ.addTriangleMesh(allTriangles, meshColors, univ.getSafeContentName("Discs for path " + getName()));
	}

	synchronized public void addTo3DViewer(final Image3DUniverse univ, final Color c, final ImagePlus colorImage) {
		if (c == null)
			throw new RuntimeException("In addTo3DViewer, Color can no longer be null");
		addTo3DViewer(univ, new Color3f(c), colorImage);
	}

	synchronized public void addTo3DViewer(final Image3DUniverse univ, final Color3f c, final ImagePlus colorImage) {
		if (c == null)
			throw new RuntimeException("In addTo3DViewer, Color3f can no longer be null");

		realColor = (c == null) ? new Color3f(Color.magenta) : c;

		if (points <= 1) {
			content3D = null;
			content3DExtra = null;
			return;
		}

		if (paths3DDisplay == SimpleNeuriteTracer.DISPLAY_PATHS_LINES
				|| paths3DDisplay == SimpleNeuriteTracer.DISPLAY_PATHS_LINES_AND_DISCS) {
			content3D = addAsLinesTo3DViewer(univ, realColor, colorImage);
			content3D.setLocked(true);
			nameWhenAddedToViewer = content3D.getName();
			if (paths3DDisplay == SimpleNeuriteTracer.DISPLAY_PATHS_LINES_AND_DISCS) {
				content3DExtra = addDiscsTo3DViewer(univ, realColor, colorImage);
				content3DExtraMultiColored = colorImage;
				if (content3DExtra == null) {
					nameWhenAddedToViewerExtra = null;
				} else {
					content3DExtra.setLocked(true);
					nameWhenAddedToViewerExtra = content3DExtra.getName();
				}
			}
			// univ.resetView();
			return;
		}

		int pointsToUse = -1;

		double[] x_points_d = new double[points];
		double[] y_points_d = new double[points];
		double[] z_points_d = new double[points];
		double[] radiuses_d = new double[points];

		if (hasRadii()) {
			int added = 0;
			int lastIndexAdded = -noMoreThanOneEvery;
			for (int i = 0; i < points; ++i) {
				if ((points <= noMoreThanOneEvery) || (i - lastIndexAdded >= noMoreThanOneEvery)) {
					x_points_d[added] = precise_x_positions[i];
					y_points_d[added] = precise_y_positions[i];
					z_points_d[added] = precise_z_positions[i];
					radiuses_d[added] = radiuses[i];
					lastIndexAdded = i;
					++added;
				}
			}
			pointsToUse = added;
		} else {
			for (int i = 0; i < points; ++i) {
				x_points_d[i] = precise_x_positions[i];
				y_points_d[i] = precise_y_positions[i];
				z_points_d[i] = precise_z_positions[i];
				radiuses_d[i] = getMinimumSeparation() * 2;
			}
			pointsToUse = points;
		}

		if (pointsToUse == 2) {
			// If there are only two points, then makeTube
			// fails, so interpolate:
			final double[] x_points_d_new = new double[3];
			final double[] y_points_d_new = new double[3];
			final double[] z_points_d_new = new double[3];
			final double[] radiuses_d_new = new double[3];

			x_points_d_new[0] = x_points_d[0];
			y_points_d_new[0] = y_points_d[0];
			z_points_d_new[0] = z_points_d[0];
			radiuses_d_new[0] = radiuses_d[0];

			x_points_d_new[1] = (x_points_d[0] + x_points_d[1]) / 2;
			y_points_d_new[1] = (y_points_d[0] + y_points_d[1]) / 2;
			z_points_d_new[1] = (z_points_d[0] + z_points_d[1]) / 2;
			radiuses_d_new[1] = (radiuses_d[0] + radiuses_d[1]) / 2;

			x_points_d_new[2] = x_points_d[1];
			y_points_d_new[2] = y_points_d[1];
			z_points_d_new[2] = z_points_d[1];
			radiuses_d_new[2] = radiuses_d[1];

			x_points_d = x_points_d_new;
			y_points_d = y_points_d_new;
			z_points_d = z_points_d_new;
			radiuses_d = radiuses_d_new;

			pointsToUse = 3;
		}

		final double[] x_points_d_trimmed = new double[pointsToUse];
		final double[] y_points_d_trimmed = new double[pointsToUse];
		final double[] z_points_d_trimmed = new double[pointsToUse];
		final double[] radiuses_d_trimmed = new double[pointsToUse];

		System.arraycopy(x_points_d, 0, x_points_d_trimmed, 0, pointsToUse);
		System.arraycopy(y_points_d, 0, y_points_d_trimmed, 0, pointsToUse);
		System.arraycopy(z_points_d, 0, z_points_d_trimmed, 0, pointsToUse);
		System.arraycopy(radiuses_d, 0, radiuses_d_trimmed, 0, pointsToUse);

		/*
		 * Work out whether to resample or not. I've found that the resampling
		 * is only really required in cases where the points are at adjacent
		 * voxels. So, work out the mean distance between all the points but in
		 * image co-ordinates - if there are points only at adjacent voxels this
		 * will be between 1 and sqrt(3) ~= 1.73. However, after the "fitting"
		 * process here, we might remove many of these points, so I'll say that
		 * we won't resample if the mean is rather higher - above 3. Hopefully
		 * this is a good compromise...
		 */

		double total_length_in_image_space = 0;
		for (int i = 1; i < pointsToUse; ++i) {
			final double x_diff = (x_points_d_trimmed[i] - x_points_d_trimmed[i - 1]) / x_spacing;
			final double y_diff = (y_points_d_trimmed[i] - y_points_d_trimmed[i - 1]) / y_spacing;
			final double z_diff = (z_points_d_trimmed[i] - z_points_d_trimmed[i - 1]) / z_spacing;
			total_length_in_image_space += Math.sqrt(x_diff * x_diff + y_diff * y_diff + z_diff * z_diff);
		}
		final double mean_inter_point_distance_in_image_space = total_length_in_image_space / (pointsToUse - 1);
		SNT.log("For path " + this + ", got mean_inter_point_distance_in_image_space: "
					+ mean_inter_point_distance_in_image_space);
		final boolean resample = mean_inter_point_distance_in_image_space < 3;

		SNT.log("... so" + (resample ? "" : " not") + " resampling");

		final ArrayList<Color3f> tubeColors = new ArrayList<>();

		final double[][][] allPoints = Pipe.makeTube(x_points_d_trimmed, y_points_d_trimmed, z_points_d_trimmed,
				radiuses_d_trimmed, resample ? 2 : 1, // resample - 1 means just
														// "use mean distance
														// between points", 3 is
														// three times that,
														// etc.
				12, // "parallels" (12 means cross-sections are dodecagons)
				resample, // do_resample
				realColor, colorImage, tubeColors);

		if (allPoints == null) {
			content3D = null;
			content3DExtra = null;
			return;
		}

		// Make tube adds an extra point at the beginning and end:

		final List<Color3f> vertexColorList = new ArrayList<>();
		final List<Point3f> triangles = Pipe.generateTriangles(allPoints, 1, // scale
				tubeColors, vertexColorList);

		nameWhenAddedToViewer = univ.getSafeContentName(getName());
		// univ.resetView();
		content3D = univ.addTriangleMesh(triangles, vertexColorList, nameWhenAddedToViewer);
		content3D.setLocked(true);
		content3DMultiColored = colorImage;

		content3DExtra = null;
		nameWhenAddedToViewerExtra = null;

		// univ.resetView();
		return;
	}

	public void setSelected(final boolean newSelectedStatus) {
		selected = newSelectedStatus;
	}

	public boolean isSelected() {
		return selected;
	}

	//TODO: this should be renamed
	public boolean versionInUse() {
		if (fittedVersionOf != null)
			return fittedVersionOf.useFitted;
		return !useFitted;
	}

	/**
	 * The volume of each part of the fitted path most accurately would be the
	 * volume of a convex hull of two arbitrarily oriented and sized circles in
	 * space. This is tough to work out analytically, and this precision isn't
	 * really warranted given the errors introduced in the fitting process, the
	 * tracing in the first place, etc. So, this method produces an approximate
	 * volume assuming that the volume of each of these parts is that of a
	 * truncated cone, with circles of the same size (i.e. as if the circles had
	 * simply been reoriented to be parallel and have a common normal vector)
	 *
	 * For more accurate measurements of the volumes of a neuron, you should use
	 * the filling interface.
	 */

	public double getApproximateFittedVolume() {
		if (!hasRadii()) {
			return -1;
		}

		double totalVolume = 0;

		for (int i = 0; i < points - 1; ++i) {

			final double xdiff = precise_x_positions[i + 1] - precise_x_positions[i];
			final double ydiff = precise_y_positions[i + 1] - precise_y_positions[i];
			final double zdiff = precise_z_positions[i + 1] - precise_z_positions[i];
			final double h = Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff * zdiff);
			final double r1 = radiuses[i];
			final double r2 = radiuses[i + 1];
			// See http://en.wikipedia.org/wiki/Frustum
			final double partVolume = (Math.PI * h * (r1 * r1 + r2 * r2 + r1 * r2)) / 3.0;
			totalVolume += partVolume;
		}

		return totalVolume;
	}

	/*
	 * This doesn't deal with the startJoins, endJoins or fitted fields, since
	 * they involve other paths which were probably also transformed by the
	 * caller.
	 */

	public Path transform(final PathTransformer transformation, final ImagePlus template, final ImagePlus model) {

		double templatePixelWidth = 1;
		double templatePixelHeight = 1;
		double templatePixelDepth = 1;
		String templateUnits = "pixels";

		final Calibration templateCalibration = template.getCalibration();
		if (templateCalibration != null) {
			templatePixelWidth = templateCalibration.pixelWidth;
			templatePixelHeight = templateCalibration.pixelHeight;
			templatePixelDepth = templateCalibration.pixelDepth;
			templateUnits = templateCalibration.getUnits();
		}

		final Path result = new Path(templatePixelWidth, templatePixelHeight, templatePixelDepth, templateUnits,
				size());
		final double[] transformed = new double[3];

		// Actually, just say you'll have to refit all the
		// previously fitted paths...

		for (int i = 0; i < points; ++i) {
			final double original_x = precise_x_positions[i];
			final double original_y = precise_y_positions[i];
			final double original_z = precise_z_positions[i];
			transformation.transformPoint(original_x, original_y, original_z, transformed);
			final double new_x = transformed[0];
			final double new_y = transformed[1];
			final double new_z = transformed[2];
			if (Double.isNaN(new_x) || Double.isNaN(new_y) || Double.isNaN(new_z))
				continue;
			result.addPointDouble(new_x, new_y, new_z);
		}

		result.primary = primary;
		result.id = id;
		result.selected = selected;
		result.name = name;

		result.x_spacing = x_spacing;
		result.y_spacing = y_spacing;
		result.z_spacing = z_spacing;
		result.spacing_units = spacing_units;

		result.swcType = swcType;

		return result;
	}

	/**
	 * Returns the points which are indicated to be a join, either in this Path
	 * object, or any other that starts or ends on it.
	 */
	public List<PointInImage> findJoinedPoints() {
		final ArrayList<PointInImage> result = new ArrayList<>();
		if (startJoins != null) {
			result.add(startJoinsPoint);
		}
		if (endJoins != null) {
			result.add(endJoinsPoint);
		}
		for (final Path other : somehowJoins) {
			if (other.startJoins == this) {
				result.add(other.startJoinsPoint);
			}
			if (other.endJoins == this) {
				result.add(other.endJoinsPoint);
			}
		}
		return result;
	}

	/**
	 * Returns the indices of points which are indicated to be a join, either in
	 * this path object, or any other that starts or ends on it.
	 */
	public Set<Integer> findJoinedPointIndices() {
		final HashSet<Integer> result = new HashSet<>();
		for (final PointInImage point : findJoinedPoints()) {
			result.add(indexNearestTo(point.x, point.y, point.z));
		}
		return result;
	}

	synchronized public void downsample(final double maximumAllowedDeviation) {
		// We should only downsample between the fixed points, i.e.
		// where this neuron joins others
		final Set<Integer> fixedPointSet = findJoinedPointIndices();
		// Add the start and end points:
		fixedPointSet.add(0);
		fixedPointSet.add(points - 1);
		final Integer[] fixedPoints = fixedPointSet.toArray(new Integer[0]);
		Arrays.sort(fixedPoints);
		int lastIndex = -1;
		int totalDroppedPoints = 0;
		for (final int fpi : fixedPoints) {
			if (lastIndex >= 0) {
				final int start = lastIndex - totalDroppedPoints;
				final int end = fpi - totalDroppedPoints;
				// Now downsample between those points:
				final ArrayList<SimplePoint> forDownsampling = new ArrayList<>();
				for (int i = start; i <= end; ++i) {
					forDownsampling.add(
							new SimplePoint(precise_x_positions[i], precise_y_positions[i], precise_z_positions[i], i));
				}
				final ArrayList<SimplePoint> downsampled = PathDownsampler.downsample(forDownsampling,
						maximumAllowedDeviation);

				// Now update x_points, y_points, z_points:
				final int pointsDroppedThisTime = forDownsampling.size() - downsampled.size();
				totalDroppedPoints += pointsDroppedThisTime;
				final int newLength = points - pointsDroppedThisTime;
				final double[] new_x_points = new double[maxPoints];
				final double[] new_y_points = new double[maxPoints];
				final double[] new_z_points = new double[maxPoints];
				// First copy the elements before 'start' verbatim:
				System.arraycopy(precise_x_positions, 0, new_x_points, 0, start);
				System.arraycopy(precise_y_positions, 0, new_y_points, 0, start);
				System.arraycopy(precise_z_positions, 0, new_z_points, 0, start);
				// Now copy in the downsampled points:
				final int downsampledLength = downsampled.size();
				for (int i = 0; i < downsampledLength; ++i) {
					final SimplePoint sp = downsampled.get(i);
					new_x_points[start + i] = sp.x;
					new_y_points[start + i] = sp.y;
					new_z_points[start + i] = sp.z;
				}
				System.arraycopy(precise_x_positions, end, new_x_points, (start + downsampledLength) - 1, points - end);
				System.arraycopy(precise_y_positions, end, new_y_points, (start + downsampledLength) - 1, points - end);
				System.arraycopy(precise_z_positions, end, new_z_points, (start + downsampledLength) - 1, points - end);

				double[] new_radiuses = null;
				if (hasRadii()) {
					new_radiuses = new double[maxPoints];
					System.arraycopy(radiuses, 0, new_radiuses, 0, start);
					for (int i = 0; i < downsampledLength; ++i) {
						final SimplePoint sp = downsampled.get(i);
						// Find a first and last index in the original radius
						// array to
						// take a mean over:
						int firstRadiusIndex, lastRadiusIndex, n = 0;
						double total = 0;
						if (i == 0) {
							// This is the first point:
							final SimplePoint spNext = downsampled.get(i + 1);
							firstRadiusIndex = sp.originalIndex;
							lastRadiusIndex = (sp.originalIndex + spNext.originalIndex) / 2;
						} else if (i == downsampledLength - 1) {
							// The this is the last point:
							final SimplePoint spPrevious = downsampled.get(i - 1);
							firstRadiusIndex = (spPrevious.originalIndex + sp.originalIndex) / 2;
							lastRadiusIndex = sp.originalIndex;
						} else {
							final SimplePoint spPrevious = downsampled.get(i - 1);
							final SimplePoint spNext = downsampled.get(i + 1);
							firstRadiusIndex = (sp.originalIndex + spPrevious.originalIndex) / 2;
							lastRadiusIndex = (sp.originalIndex + spNext.originalIndex) / 2;
						}
						for (int j = firstRadiusIndex; j <= lastRadiusIndex; ++j) {
							total += radiuses[j];
							++n;
						}
						new_radiuses[start + i] = total / n;
					}
					System.arraycopy(radiuses, end, new_radiuses, (start + downsampledLength) - 1, points - end);
				}

				// Now update all of those fields:
				points = newLength;
				precise_x_positions = new_x_points;
				precise_y_positions = new_y_points;
				precise_z_positions = new_z_points;
				radiuses = new_radiuses;
				if (hasRadii()) {
					setGuessedTangents(2);
				}
			}
			lastIndex = fpi;
		}
		invalidate3DView();
	}

}
