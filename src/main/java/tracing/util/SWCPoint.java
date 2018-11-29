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

package tracing.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import tracing.Path;
import tracing.SNT;

/**
 * Defines a node in an SWC reconstruction. The SWC file format is detailed
 * <a href=
 * "http://www.neuronland.org/NLMorphologyConverter/MorphologyFormats/SWC/Spec.html">here</a>
 *
 * @author Tiago Ferreira
 */
public class SWCPoint implements SNTPoint, Comparable<SWCPoint> {

	/** The sample number of this node */
	public final int id;

	/**
	 * The SWC-type flag of this node ({@link Path#SWC_SOMA},
	 * {@link Path#SWC_DENDRITE}, etc.)
	 */
	public final int type;

	/** The parent id of this node */
	public final int parent;

	/** The cartesian coordinate of this node */
	public double x, y, z;

	/** The radius of reconstructed structure at this node */
	public double radius;

	/**
	 * The list holding the subsequent nodes in the reconstructed structure after
	 * this one.
	 */
	public final List<SWCPoint> nextPoints;

	/** The preceding node (if any) */
	public SWCPoint previousPoint;

	/** The Path associated with this node (if any) */
	public Path onPath = null;

	public SWCPoint(final int id, final int type, final double x, final double y,
		final double z, final double radius, final int parent)
	{
		nextPoints = new ArrayList<>();
		this.id = id;
		this.type = type;
		this.x = x;
		this.y = y;
		this.z = z;
		this.radius = radius;
		this.parent = parent;
	}

	/**
	 * Gets this node as a {@link PointInImage} object.
	 *
	 * @return the PointInImage node.
	 */
	public PointInImage getPointInImage() {
		final PointInImage pim = new PointInImage(x, y, z);
		pim.onPath = onPath;
		return pim;
	}

	/**
	 * Returns the X-distance from previous point.
	 *
	 * @return the X-distance from previous point or {@code Double.NaN} if no
	 *         previousPoint exists.
	 */
	public double xSeparationFromPreviousPoint() {
		return (previousPoint == null) ? Double.NaN : Math.abs(this.x -
			previousPoint.x);
	}

	/**
	 * Returns the Y-distance from previous point.
	 *
	 * @return the Y-distance from previous point or {@code Double.NaN} if no
	 *         previousPoint exists.
	 */
	public double ySeparationFromPreviousPoint() {
		return (previousPoint == null) ? Double.NaN : Math.abs(this.y -
			previousPoint.y);
	}

	/**
	 * Returns the Z-distance from previous point.
	 *
	 * @return the Z-distance from previous point or {@code Double.NaN} if no
	 *         previousPoint exists.
	 */
	public double zSeparationFromPreviousPoint() {
		return (previousPoint == null) ? Double.NaN : (this.z - previousPoint.z);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "SWCPoint [" + id + "] " + Path.getSWCtypeName(type, false) + " " +
			"(" + x + "," + y + "," + z + ") " + "radius: " + radius + ", " +
			"[previous: " + parent + "]";
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	@Override
	public int compareTo(final SWCPoint o) {
		return (id < o.id) ? -1 : ((id > o.id) ? 1 : 0);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(final Object o) {
		if (this == o) return true;
		if (o == null) return false;
		if (!(o instanceof SWCPoint)) return false;
		return this.id == ((SWCPoint) o).id;
	}

	/**
	 * Converts a collection of SWC points into a Reader.
	 *
	 * @param points the collection of SWC points to be converted into a space/
	 *          tab separated String. Points should be sorted by sample number to
	 *          ensure valid connectivity.
	 * @return the Reader
	 */
	public static StringReader collectionAsReader(
		final Collection<SWCPoint> points)
	{
		final StringBuilder sb = new StringBuilder();
		for (final SWCPoint p : points) {
			sb.append(p.id).append("\t") //
				.append(p.type).append("\t") //
				.append(String.format("%.6f", p.x)).append(" ") //
				.append(String.format("%.6f", p.y)).append(" ") //
				.append(String.format("%.6f", p.z)).append(" ") //
				.append(String.format("%.6f", p.radius)).append("\t") //
				.append(p.parent).append(System.lineSeparator());
		}
		return new StringReader(sb.toString());
	}

	/**
	 * Prints a list of points as space-separated values.
	 *
	 * @param points the collections of SWC points to be printed.
	 * @param pw the PrintWriter to write to.
	 * @see SWCPoint#collectionAsReader(Collection)
	 */
	public static void flush(final Collection<SWCPoint> points,
		final PrintWriter pw)
	{
		try (BufferedReader br = new BufferedReader(collectionAsReader(points))) {
			br.lines().forEach(pw::println);
		}
		catch (final IOException e) {
			SNT.error("IO Error", e);
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see tracing.util.SNTPoint#getX()
	 */
	@Override
	public double getX() {
		return x;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see tracing.util.SNTPoint#getY()
	 */
	@Override
	public double getY() {
		return y;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see tracing.util.SNTPoint#getZ()
	 */
	@Override
	public double getZ() {
		return z;
	}

}
