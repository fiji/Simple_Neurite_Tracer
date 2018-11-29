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

import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import tracing.SNT;
import tracing.SNTService;
import tracing.SNTUI;
import tracing.SimpleNeuriteTracer;

/**
 * Command class for GUI commands extending DynamicCommand
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false)
public class CommonDynamicCmd extends DynamicCommand {

	@Parameter
	protected StatusService statusService;

	@Parameter
	protected UIService uiService;

	@Parameter
	protected SNTService sntService;

	protected SimpleNeuriteTracer snt;
	protected SNTUI ui;

	protected void status(final String statusMsg, final boolean temporaryMsg) {
		if (ui == null) {
			statusService.showStatus(statusMsg);
		}
		else {
			ui.showStatus(statusMsg, temporaryMsg);
		}
		SNT.log("Rebuilding canvases...");
	}

	protected void error(final String msg) {
		if (snt != null) {
			snt.error(msg);
		}
		else {
			cancel("<HTML>" + msg); // Linux: UIService uses legacy java.awt dialog if
															// not HTML
		}
	}

	protected void msg(final String msg, final String title) {
		if (snt != null) {
			snt.showMsg(msg, title);
		}
		else {
			uiService.showDialog(msg, title);
		}
	}

	@Override
	public void run() {
		// do nothing by default
	}

}
