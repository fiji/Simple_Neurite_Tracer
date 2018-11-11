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

package tracing.gui.cmds;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import tracing.analysis.TreeColorizer;
import tracing.gui.GuiUtils;
import tracing.plot.TreePlot3D;

/**
 * Implements Reconstruction Viewer's 'Add Color Legend' command
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Tree Color Coder", initializer = "init")
public class ColorizeReconstructionCmd extends CommonDynamicCmd {

	@Parameter
	private LUTService lutService;

	@Parameter(required = true, label = "Measurement", choices = { //
			TreeColorizer.BRANCH_ORDER, TreeColorizer.LENGTH, TreeColorizer.N_BRANCH_POINTS, //
			TreeColorizer.N_NODES, TreeColorizer.PATH_DISTANCE, TreeColorizer.MEAN_RADIUS, //
			TreeColorizer.NODE_RADIUS, TreeColorizer.X_COORDINATES, TreeColorizer.Y_COORDINATES, //
			TreeColorizer.Z_COORDINATES})
	private String measurementChoice;

	@Parameter(label = "Min", required = false, description = "Set both limits to zero for automatic scaling")
	private float min;

	@Parameter(label = "Max", required = false, description = "Set both limits to zero for automatic scaling")
	private float max;

	@Parameter(label = "LUT", callback = "lutChoiceChanged")
	private String lutChoice;

	@Parameter(required = false, label = "<HTML>&nbsp;", persist = false)
	private ColorTable colorTable;

	@Parameter(required = false)
	private TreePlot3D recViewer;

	@Parameter(required = false)
	private List<String> labels;

	private Map<String, URL> luts;

	private void lutChoiceChanged() {
		try {
			colorTable = lutService.loadLUT(luts.get(lutChoice));
		} catch (final IOException ignored) {
			// move on
		}
	}

	@SuppressWarnings("unused")
	private void init() {
		// see net.imagej.lut.LUTSelector
		luts = lutService.findLUTs();
		final ArrayList<String> choices = new ArrayList<>();
		for (final Map.Entry<String, URL> entry : luts.entrySet()) {
			choices.add(entry.getKey());
		}
		Collections.sort(choices);
		final MutableModuleItem<String> input = getInfo().getMutableInput("lutChoice", String.class);
		input.setChoices(choices);
		lutChoice = input.getDefaultValue();
		if (lutChoice == null || lutChoice.isEmpty())
			lutChoice = choices.get(0);
		lutChoiceChanged();

		if (labels == null) {
			// No trees to be color coded
			getInfo().setLabel("Add Color Legend");
			final MutableModuleItem<String> mInput = getInfo().getMutableInput("measurementChoice", String.class);
			final ArrayList<String> dummyChoice = new ArrayList<String>();
			dummyChoice.add("");
			mInput.setChoices(dummyChoice);
			mInput.setLabel("<HTML>&nbsp;");
			mInput.setVisibility(ItemVisibility.MESSAGE);
			mInput.setPersisted(false);
			final MutableModuleItem<Float> minInput = getInfo().getMutableInput("min", Float.class);
			minInput.setDescription("");
			final MutableModuleItem<Float> maxInput = getInfo().getMutableInput("max", Float.class);
			maxInput.setDescription("");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		try {
			if (recViewer == null) {
				recViewer = sntService.getReconstructionViewer();
			}

			// FIXME: This is not suitable for measurements with negative values
			final boolean validMin = !Double.isNaN(min) && min <= max;
			final boolean validMax = !Double.isNaN(max) && max > 0 && max >= min;

			if (labels == null) {
				// No trees to be color coded. Just add the color bar
				if (!(validMin && validMax)) {
					error("Invalid Limits " + min + "-" + max);
					return;
				}
				recViewer.addColorBarLegend(colorTable, min, (float) max);
				return;
			}

			recViewer.setViewUpdatesEnabled(false);
			final double[] limits = { Double.MAX_VALUE, Double.MIN_VALUE };
			labels.forEach(l -> {
				final double[] minMax = recViewer.colorize(l, measurementChoice, colorTable);
				if (minMax[0] < limits[0])
					limits[0] = minMax[0];
				if (minMax[1] > limits[1])
					limits[1] = minMax[1];
			});
			recViewer.setViewUpdatesEnabled(true);
			if (!validMin) min = (float) limits[0];
			if (!validMax) max = (float) limits[1];
			recViewer.addColorBarLegend(colorTable, min, max);

		} catch (final UnsupportedOperationException | NullPointerException exc) {
			error("SNT's Reconstruction Viewer is not available");
		}

	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ColorizeReconstructionCmd.class, true);
	}

}